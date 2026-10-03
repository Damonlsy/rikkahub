package me.rerere.rikkahub.ui.pages.coreading

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.ChatAvatarShape
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.decodeAvatarBitmap
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

/**
 * 一起读书页面与 App 的桥（JS interface）。
 *
 * 页面通过 window.Coreading 调用：
 * - bootstrap(): 找到/创建「一起读书」会话（挂当前助手），返回会话历史
 * - send(text): 向该会话发消息（真实 ChatService 生成，与日常聊天同一条会话）
 * - poll(): 轮询生成进度（轻量消息列表）
 * - setReadingContext(json): 更新阅读上下文（会话 system prompt + 助手记忆）
 * - stop(): 停止当前生成
 */
class CoreadingBridge(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    /** 入口带过来的「原来那个聊天框」；非空时直接用它，不新建会话。 */
    private val conversationId: String? = null,
) {
    @Serializable
    data class BridgeMessage(val role: String, val text: String)

    /** 头像：type = image / emoji / dummy，value 为图片 URL 或表情内容 */
    @Serializable
    data class BridgeAvatar(val type: String, val value: String = "")

    @Serializable
    data class BridgeState(
        val isGenerating: Boolean = false,
        val messages: List<BridgeMessage> = emptyList(),
    )

    @Serializable
    data class BridgeBootstrap(
        val conversationId: String,
        val assistantName: String,
        val assistantAvatar: BridgeAvatar = BridgeAvatar("dummy"),
        val userName: String = "",
        val userAvatar: BridgeAvatar = BridgeAvatar("dummy"),
        val chatAvatarShape: ChatAvatarShape = ChatAvatarShape.CIRCLE,
        val state: BridgeState,
    )

    @Serializable
    data class ReadingContext(
        val book: String = "",
        val author: String = "",
        val chapter: String = "",
        val page: String = "",
    )

    private fun errorJson(message: String): String =
        """{"error":${JsonInstant.encodeToString(message)}}"""

    private suspend fun Uuid.conversationSnapshot(): Pair<Conversation, Boolean> {
        val conversation = conversationRepo.getConversationById(this)
            ?: error("conversation not found")
        val generating = chatService.getGenerationJobStateFlow(this).first() != null
        return conversation to generating
    }

    private suspend fun currentState(conversation: Conversation, generating: Boolean) = BridgeState(
        isGenerating = generating,
        messages = conversation.currentMessages.mapNotNull { it.toBridgeMessage() },
    )

    private fun UIMessage.toBridgeMessage(): BridgeMessage? {
        val text = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
            .trim()
        if (text.isEmpty()) return null
        return BridgeMessage(
            role = role.name.lowercase(),
            text = text,
        )
    }

    /**
     * 解析要用的会话：入口带了 conversationId 就用原来那个聊天框；
     * 没带（异常回落）才走「找/建标题为一起读书的会话」的老逻辑。
     */
    private suspend fun findOrCreateConversation(): Conversation {
        conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { pinned ->
                conversationRepo.getConversationById(pinned)?.let { return it }
            }
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val existing = conversationRepo.getConversationsOfAssistant(assistant.id)
            .first()
            .firstOrNull { it.title == TITLE }
        if (existing != null) {
            return conversationRepo.getConversationById(existing.id) ?: error("conversation not found")
        }
        val created = Conversation(
            assistantId = assistant.id,
            title = TITLE,
            messageNodes = emptyList(),
        )
        conversationRepo.insertConversation(created)
        return created
    }

    private fun Avatar.toBridgeAvatar(): BridgeAvatar = when (this) {
        is Avatar.Image -> when {
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:image/") -> {
                BridgeAvatar("image", url)
            }
            else -> decodeAvatarBitmap(context, url, maxSize = 256)?.let { bitmap ->
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                BridgeAvatar("image", "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
            } ?: BridgeAvatar("dummy")
        }
        is Avatar.Emoji -> BridgeAvatar("emoji", content)
        is Avatar.Dummy -> BridgeAvatar("dummy")
    }

    @JavascriptInterface
    fun bootstrap(): String = runBlocking {
        runCatching {
            val conversation = findOrCreateConversation()
            lastConversationId = conversation.id
            chatService.initializeConversation(conversation.id)
            val (loaded, generating) = conversation.id.conversationSnapshot()
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(loaded.assistantId) ?: settings.getCurrentAssistant()
            JsonInstant.encodeToString(
                BridgeBootstrap(
                    conversationId = conversation.id.toString(),
                    assistantName = assistant.name,
                    assistantAvatar = assistant.avatar.toBridgeAvatar(),
                    userName = settings.displaySetting.userNickname.ifBlank { "我" },
                    userAvatar = settings.displaySetting.userAvatar.toBridgeAvatar(),
                    chatAvatarShape = settings.displaySetting.chatAvatarShape,
                    state = currentState(loaded, generating),
                )
            )
        }.getOrElse { errorJson(it.message ?: "bootstrap failed") }
    }

    @JavascriptInterface
    fun poll(): String = runBlocking {
        runCatching {
            val conversationId = lastConversationId ?: return@runCatching errorJson("not bootstrapped")
            val (conversation, generating) = conversationId.conversationSnapshot()
            JsonInstant.encodeToString(currentState(conversation, generating))
        }.getOrElse { errorJson(it.message ?: "poll failed") }
    }

    @JavascriptInterface
    fun send(text: String): String = runBlocking {
        runCatching {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@runCatching errorJson("empty message")
            val conversationId = lastConversationId ?: findOrCreateConversation().id.also {
                lastConversationId = it
            }
            chatService.initializeConversation(conversationId)
            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(text = trimmed)),
                answer = true,
            )
            """{"ok":true}"""
        }.getOrElse { errorJson(it.message ?: "send failed") }
    }

    @JavascriptInterface
    fun stop(): String = runBlocking {
        runCatching {
            val conversationId = lastConversationId ?: return@runCatching """{"ok":false}"""
            chatService.stopGeneration(conversationId)
            """{"ok":true}"""
        }.getOrElse { errorJson(it.message ?: "stop failed") }
    }

    /**
     * 阅读进度写入助手记忆（[一起读书] 前缀，原位更新）——本会话和日常聊天都能想起读到哪了。
     * 不再改动会话的 customSystemPrompt：现在读书就在用户原来的聊天框里进行，不能污染它的系统提示。
     */
    @JavascriptInterface
    fun setReadingContext(json: String): String = runBlocking {
        runCatching {
            val ctx = JsonInstant.decodeFromString<ReadingContext>(json)
            // 保证会话已解析（进页面第一步就会 bootstrap，这里只是兜底）
            lastConversationId ?: findOrCreateConversation().also { lastConversationId = it.id }

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val memoryText = buildString {
                append("[一起读书] 我和助手正在共读《${ctx.book}》")
                if (ctx.chapter.isNotBlank()) append("，${ctx.chapter}")
                if (ctx.page.isNotBlank()) append("（第 ${ctx.page} 页）")
                append("。")
            }
            val memories = memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            val existing = memories.firstOrNull { it.content.startsWith(MEMORY_PREFIX) }
            if (existing != null) {
                if (existing.content != memoryText) {
                    memoryRepository.updateContent(existing.id, memoryText)
                }
            } else {
                memoryRepository.addMemory(assistant.id.toString(), memoryText)
            }
            """{"ok":true}"""
        }.getOrElse { errorJson(it.message ?: "setReadingContext failed") }
    }

    @Volatile
    private var lastConversationId: Uuid? = null

    companion object {
        const val TITLE = "一起读书"
        const val MEMORY_PREFIX = "[一起读书]"
    }
}
