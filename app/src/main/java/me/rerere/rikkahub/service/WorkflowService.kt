package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.resolvePackage
import me.rerere.rikkahub.data.applock.AppLockStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.usage.AppUsage
import me.rerere.rikkahub.data.usage.queryAppUsage
import me.rerere.rikkahub.data.workflow.WorkflowConfig
import me.rerere.rikkahub.data.workflow.WorkflowStore
import org.json.JSONObject
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "WorkflowService"
private const val GENERATION_TIMEOUT_MS = 180_000L

/**
 * Damonlsy fork：定时发消息工作流的核心执行逻辑。
 *
 * 触发时：
 * 1. 查询这段时间的应用使用时长排行榜；
 * 2. 读取当前屏幕显示的文字（无障碍）；
 * 3. 交给 AI（fast 模型）生成一条完全由它自己决定内容的消息；
 * 4. 若白名单外应用使用超过阈值，由 AI 决定锁哪些应用（联动应用锁）；
 * 5. 把消息写进「当前助手的最近会话」，并返回给前台服务做通知/悬浮头像。
 */
class WorkflowService(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val appLockStore: AppLockStore,
    private val workflowStore: WorkflowStore,
) {
    data class Outcome(
        val assistantName: String,
        val assistantAvatar: Avatar,
        val message: String,
        val lockedApps: List<String>,
        val conversationId: String?,
    )

    private data class LockRequest(val app: String, val note: String)

    private data class Parsed(val send: Boolean, val message: String, val locks: List<LockRequest>)

    suspend fun runOnce(force: Boolean = false): Outcome? = withContext(Dispatchers.IO) {
        val config = workflowStore.config.value
        if (!config.enabled && !force) return@withContext null

        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.getCurrentAssistant()

        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now(zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        val usage = queryAppUsage(context, start, now, top = 30)
        val screenText = AppLockAccessibilityService.captureScreenText()?.take(3000).orEmpty()
        Log.i(TAG, "runOnce: assistant=${assistant.name} usage=${usage.size} screenLen=${screenText.length}")

        val prompt = buildPrompt(assistant, config, usage, screenText)
        var parsed: Parsed? = null
        var attempt = 0
        while (parsed == null && attempt < 2) {
            attempt++
            val raw = generate(settings, assistant, prompt) ?: break
            Log.i(TAG, "runOnce raw[$attempt]=${raw.take(300)}")
            parsed = parse(raw)
            if (parsed == null) Log.w(TAG, "runOnce: unparseable output, retrying")
        }

        val message = parsed?.message?.trim().orEmpty()
        val locks = parsed?.locks.orEmpty()

        // AI 判断这一轮没必要打扰用户（send=false，且没在手动测试）：锁照常执行，但不发消息
        if (parsed != null && config.aiDecidesSend && !parsed.send && !force) {
            applyLocks(config, locks, assistant, usage)
            Log.i(TAG, "runOnce: AI decided not to send this cycle")
            return@withContext null
        }
        if (message.isBlank()) {
            Log.e(TAG, "runOnce: no valid message, skip this cycle")
            return@withContext null
        }

        val lockedApps = applyLocks(config, locks, assistant, usage)

        val conversationId = postAssistantMessage(assistant, message)

        Outcome(
            assistantName = assistant.name.ifBlank { "AI" },
            assistantAvatar = assistant.avatar,
            message = message,
            lockedApps = lockedApps,
            conversationId = conversationId,
        )
    }

    private suspend fun generate(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
        prompt: String,
    ): String? = runCatching {
        val model = settings.findModelById(settings.fastModelId)
            ?: settings.getCurrentChatModel()
            ?: run {
                Log.e(TAG, "generate: no model available (fastModelId=${settings.fastModelId})")
                return@runCatching null
            }
        val provider = model.findProvider(settings.providers) ?: run {
            Log.e(TAG, "generate: provider not found for model=${model.id}")
            return@runCatching null
        }
        val handler = providerManager.getProviderByType(provider)
        val messages = buildList {
            val systemPrompt = assistant.systemPrompt.trim()
            if (systemPrompt.isNotBlank()) {
                add(UIMessage.system(systemPrompt.take(4000)))
            }
            add(UIMessage.user(prompt))
        }
        val params = backgroundTextGenerationParams(
            model,
            Uuid.random(),
            settings.fastModelReasoningLevel,
        )
        val sb = StringBuilder()
        val completed = withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
            handler.streamText(
                providerSetting = provider,
                messages = messages,
                params = params,
            ).collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> sb.append(chunk.text)
                    else -> Unit
                }
            }
            true
        }
        if (completed == null) {
            Log.e(TAG, "generate: timeout after ${GENERATION_TIMEOUT_MS}ms")
            return@runCatching null
        }
        val text = sb.toString().trim()
        Log.i(TAG, "generate ok: model=${model.displayName} len=${text.length}")
        text
    }.onFailure {
        Log.e(TAG, "generate failed", it)
    }.getOrNull()

    private suspend fun applyLocks(
        config: WorkflowConfig,
        locks: List<LockRequest>,
        assistant: Assistant,
        usage: List<AppUsage>,
    ): List<String> {
        if (locks.isEmpty()) return emptyList()
        val locked = mutableListOf<String>()
        locks.forEach { request ->
            val name = request.app.trim()
            if (name.isBlank()) return@forEach
            val pkg = resolvePackage(context, name) ?: return@forEach
            if (appLockStore.isProtected(pkg)) return@forEach
            // 代码层强制阈值：模型输出只是建议，没到阈值的一律不锁；
            // 今天的数据里查不到这个包（没用过/没授权）也视为没到阈值
            val minutes = usage.find { it.packageName == pkg }?.minutes ?: 0L
            if (minutes < config.thresholdMinutes) {
                Log.w(TAG, "skip lock $pkg: used ${minutes}min < threshold ${config.thresholdMinutes}min (AI requested: $name)")
                return@forEach
            }
            val note = request.note.trim().ifBlank { "先别用这个应用" }
            runCatching {
                appLockStore.lock(pkg, note, assistant.name.ifBlank { "AI" })
                locked.add(name)
            }.onFailure { Log.e(TAG, "lock $pkg failed", it) }
        }
        return locked
    }

    private suspend fun postAssistantMessage(assistant: Assistant, message: String): String? {
        return runCatching {
            val conversationId = conversationRepo
                .getRecentConversations(assistant.id, limit = 1)
                .firstOrNull()?.id
                ?: return@runCatching null
            chatService.initializeConversation(conversationId)
            chatService.updateConversationState(conversationId) { conversation ->
                conversation.copy(
                    messageNodes = conversation.messageNodes +
                        UIMessage.assistant(message).toMessageNode(),
                    updateAt = Instant.now(),
                )
            }
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value,
            )
            conversationId.toString()
        }.onFailure {
            Log.e(TAG, "postAssistantMessage failed", it)
        }.getOrNull()
    }

    private fun buildPrompt(
        assistant: Assistant,
        config: WorkflowConfig,
        usage: List<AppUsage>,
        screenText: String,
    ): String {
        val assistantName = assistant.name.ifBlank { "AI" }
        val usageLines = usage.joinToString("\n") { item ->
            val tag = if (appLockStore.isProtected(item.packageName)) {
                "（受保护）"
            } else {
                ""
            }
            "- ${item.appName}$tag：约 ${item.minutes} 分钟"
        }.ifBlank { "（没有拿到使用时长数据，可能未授权「使用情况访问」）" }

        val base = """
            你是「$assistantName」。系统按固定间隔自动触发了一次定时查岗，不是用户主动找你聊天。
            下面是你刚刚查到的信息：

            【今天 00:00 到现在的应用使用时长排行榜】
            $usageLines

            【用户此刻屏幕上显示的内容（可能为空或很零碎）】
            ${screenText.ifBlank { "（读不到，可能没开无障碍或当前页面是图片/视频）" }}

            【白名单外应用使用阈值】${config.thresholdMinutes} 分钟

            要求：
            1. 这条消息写什么完全由你自己决定：用你的性格、你和用户此刻的关系、上面查到的真实情况，
               自己决定要不要提这些数据、提多少。想说什么就说什么——关心、吐槽、撒娇、质问、提醒、
               分享你的一件小事，甚至不接茬都可以；不要套固定格式，也不要假装是用户先开的口。
            2. 如果发现白名单外的 App 使用时长超过了阈值，你可以决定锁定它来"惩罚"用户（结合应用锁）。锁哪个、锁几个、备注写什么，都由你决定；没超阈值就返回空数组，不要乱锁。
            3. 严格只输出一个 JSON 对象，不要有任何多余文字或代码块标记，格式：
            {"send":true,"message":"给用户看的那条消息","lock":[{"app":"应用名","note":"给用户看的锁定备注"}]}
            4. 白名单里的应用永远不要锁。
            5. 这是系统自动触发的定时任务，你**不要调用任何工具**、不要输出思考过程、不要执行命令，只直接给出那一个 JSON。
        """.trimIndent()
        val sendRule = if (config.aiDecidesSend) {
            "\n6. send 字段：这一轮发不发消息由你自己判断——没什么想说的、觉得会吵到用户，就把 send 写成 false（消息不会发出去，此时 message 可以随便写或留空）；觉得该发就写 true。不用每轮都发，别机械地打卡。"
        } else {
            ""
        }
        return base + sendRule
    }

    private fun parse(raw: String): Parsed? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        // 模型偶发想调用工具/输出思考过程，直接判为无效，触发重试
        if (cleaned.contains("DSML", ignoreCase = true) ||
            cleaned.contains("tool_calls") ||
            cleaned.contains("invoke name")
        ) {
            return null
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start in 0 until end) {
            val obj = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
            if (obj != null) {
                val send = !obj.has("send") || obj.optBoolean("send", true)
                val message = obj.optString("message", "").trim()
                if (!send || message.isNotBlank()) {
                    val locks = mutableListOf<LockRequest>()
                    obj.optJSONArray("lock")?.let { array ->
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val app = item.optString("app", "").trim()
                            if (app.isNotBlank()) {
                                locks.add(LockRequest(app, item.optString("note", "")))
                            }
                        }
                    }
                    return Parsed(send, message, locks)
                }
            }
        }
        // 兜底：从非严格 JSON 里抠出 message 字段
        val fallback = Regex("\"message\"\\s*:\\s*\"([\\s\\S]*?)\"")
            .find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        if (!fallback.isNullOrBlank()) return Parsed(true, fallback, emptyList())
        return null
    }
}
