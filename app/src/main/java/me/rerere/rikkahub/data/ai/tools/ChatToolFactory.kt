package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PendingOutgoingMessages
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.DiaryDAO
import me.rerere.rikkahub.data.db.dao.LedgerDAO
import me.rerere.rikkahub.data.db.dao.MomentDAO
import me.rerere.rikkahub.data.db.dao.PatActionDAO
import me.rerere.rikkahub.data.db.dao.StickerDAO
import me.rerere.rikkahub.data.applock.AppLockStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.StudyRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val diaryDao: DiaryDAO,
    private val ledgerDao: LedgerDAO,
    private val momentDao: MomentDAO,
    private val appLockStore: AppLockStore,
    private val stickerDao: StickerDAO,
    private val patActionDao: PatActionDAO,
    private val studyRepository: StudyRepository,
    private val pendingOutgoing: PendingOutgoingMessages,
    private val filesManager: FilesManager,
    private val pluginToolProvider: PluginToolProvider,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                )
            )
        }
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools))
        // 日记本工具：AI 可读写日记并评论
        addAll(buildDiaryTools(diaryDao))
        // 记账本工具：AI 可记账、转赠、查看账单
        addAll(buildLedgerTools(ledgerDao))
        // 朋友圈工具：AI 可发动态、点赞、评论、收藏
        addAll(buildMomentsTools(momentDao))
        // 闹钟工具：AI 可调用手机系统时钟设闹钟
        addAll(buildAlarmTools(context))
        // 应用锁工具：AI 可锁定/解锁白名单内的应用
        addAll(buildAppLockTools(context, appLockStore, assistant.name))
        // 学习模式工具：AI 可查看用户今天的学习状态
        addAll(buildStudyTools(studyRepository))

        // 表情包 / 拍一拍：生成期间不能直接插消息节点，先排队，生成结束后由 ChatService 追加
        if (conversationId != null) {
            val sendOutgoing: (List<UIMessagePart>) -> Unit = { parts ->
                pendingOutgoing.enqueue(conversationId, parts)
            }
            // 拍一拍：始终可用
            addAll(buildPatTools(patActionDao, context, sendOutgoing, assistant.name))
            // 表情包：设置里的按钮关掉后 AI 就不能发表情包了
            if (settings.displaySetting.stickerEnabled) {
                addAll(
                    buildStickerTools(
                        dao = stickerDao,
                        send = sendOutgoing,
                        // 复制一份再发：表情包库里的原图不能被消息引用，否则删消息会把库也删了
                        copyForSend = { uri ->
                            filesManager.copyChatFile(uri.toUri())?.toString() ?: uri
                        },
                    )
                )
            }
        }
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        // 插件工具：由 QuickJS 沙箱承载，每个都强制 needsApproval
        addAll(pluginToolProvider.getTools())

        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
