package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CompressedContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.formatNumber

// 消息节点数量警告阈值（最后防线：到这里已经很危险了）
const val MESSAGE_NODE_WARNING_THRESHOLD = 768
const val LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD = 300_000

// 软提醒：顶栏出现“压缩”小点，提示该压缩上下文了
const val SOFT_MESSAGE_NODE_THRESHOLD = 80
const val SOFT_INPUT_TOKEN_THRESHOLD = 60_000

// 强提醒：小点变红，并弹出对话框催你压缩
const val HARD_MESSAGE_NODE_THRESHOLD = 300
const val HARD_INPUT_TOKEN_THRESHOLD = 150_000

data class ConversationSizeInfo(
    // 未折叠的部分：压缩不会删记录，总量永远不降，判定必须按“上次压缩之后”的条数算
    val nodeCount: Int,
    val lastAssistantInputTokens: Int,
    // 真实总条数（仅用于展示/排查）
    val totalNodeCount: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedInputTokenThreshold: Boolean,
    val showWarning: Boolean,
    val softWarning: Boolean,
    val hardWarning: Boolean
)

private val DefaultSizeInfo = ConversationSizeInfo(
    nodeCount = 0,
    lastAssistantInputTokens = 0,
    totalNodeCount = 0,
    exceedNodeCountThreshold = false,
    exceedInputTokenThreshold = false,
    showWarning = false,
    softWarning = false,
    hardWarning = false
)

@Composable
fun rememberConversationSizeInfo(
    conversation: Conversation,
    compressedContext: CompressedContext? = null,
): ConversationSizeInfo {
    return remember(conversation.messageNodes, compressedContext) {
        computeConversationSizeInfo(conversation.messageNodes, compressedContext)
    }
}

/**
 * 纯函数，便于单测：只统计「上次压缩之后」未折叠的那部分
 * （压缩不删记录，按总条数判定的话压缩完永远是红的）。
 */
internal fun computeConversationSizeInfo(
    nodes: List<MessageNode>,
    compressedContext: CompressedContext?,
): ConversationSizeInfo {
    val totalNodeCount = nodes.size

    // 折叠到了哪一条：找到边界就按边界算，找不到（被删除/切分支）就按当初折叠的条数估
    val foldedUntil = compressedContext?.let { ctx ->
        val boundaryIndex = nodes.indexOfFirst {
            it.currentMessage.id.toString() == ctx.boundaryMessageId
        }
        if (boundaryIndex >= 0) boundaryIndex else (ctx.compressedCount - 1).coerceAtLeast(-1)
    } ?: -1
    val start = (foldedUntil + 1).coerceIn(0, totalNodeCount)

    val nodeCount = totalNodeCount - start
    // token 也只认“压缩之后”产生的 assistant 用量，压缩前那次的 prompt 是旧账，不能拿来判红
    val lastAssistantInputTokens = nodes.subList(start, totalNodeCount).asReversed()
        .map { it.currentMessage }
        .firstOrNull { it.role == MessageRole.ASSISTANT }
        ?.usage
        ?.promptTokens
        ?: 0

    val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
    val exceedInputTokenThreshold = lastAssistantInputTokens > LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD
    val softWarning = nodeCount >= SOFT_MESSAGE_NODE_THRESHOLD ||
            lastAssistantInputTokens >= SOFT_INPUT_TOKEN_THRESHOLD
    val hardWarning = nodeCount >= HARD_MESSAGE_NODE_THRESHOLD ||
            lastAssistantInputTokens >= HARD_INPUT_TOKEN_THRESHOLD ||
            exceedNodeCountThreshold ||
            exceedInputTokenThreshold
    return ConversationSizeInfo(
        nodeCount = nodeCount,
        lastAssistantInputTokens = lastAssistantInputTokens,
        totalNodeCount = totalNodeCount,
        exceedNodeCountThreshold = exceedNodeCountThreshold,
        exceedInputTokenThreshold = exceedInputTokenThreshold,
        showWarning = hardWarning,
        softWarning = softWarning,
        hardWarning = hardWarning
    )
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit,
    onCompress: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.chat_size_dialog_content,
                    sizeInfo.nodeCount,
                    sizeInfo.lastAssistantInputTokens.formatNumber(),
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onCompress?.invoke()
            }) {
                Text(
                    stringResource(
                        if (onCompress != null) R.string.chat_page_compress_context else R.string.confirm
                    )
                )
            }
        },
        dismissButton = if (onCompress != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        } else null
    )
}
