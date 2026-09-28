package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.CompressedContext
import me.rerere.rikkahub.data.datastore.ConversationCompressionStore
import kotlin.uuid.Uuid

/**
 * 上下文压缩投影
 *
 * 聊天记录本身不改写（UI 里仍然能看到全部历史），这里只在「发给模型之前」
 * 把压缩点之前的旧消息折叠成一段摘要：模型只读到摘要 + 压缩点之后的原始消息。
 *
 * 必须排在其它 input transformer 之前，这样后面那些往 system prompt 里追加内容的
 * 转换器看到的已经是折叠后的消息列表。
 */
class CompressedContextTransformer(
    private val conversationId: Uuid,
    private val compressionStore: ConversationCompressionStore,
) : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val state = compressionStore.get(conversationId) ?: return messages
        return foldCompressedContext(messages, state)
    }
}

/**
 * 纯函数，便于单测：
 * 把 [state] 记录的折叠点之前的历史换成摘要消息，其余消息原样保留。
 */
internal fun foldCompressedContext(
    messages: List<UIMessage>,
    state: CompressedContext?,
): List<UIMessage> {
    if (state == null || state.summary.isBlank()) return messages
    if (messages.size < 2) return messages

    // system prompt 是合成消息，永远不属于被压缩的历史
    val firstHistoryIndex = if (messages.firstOrNull()?.role == MessageRole.SYSTEM) 1 else 0
    if (firstHistoryIndex >= messages.size - 1) return messages

    val boundaryIndex = messages.indexOfFirst { it.id.toString() == state.boundaryMessageId }
    val cutIndex = if (boundaryIndex >= firstHistoryIndex) {
        boundaryIndex + 1
    } else {
        // 边界消息已经不存在（被删除、切分支，或已被 contextMessageLimit 从头部截掉）
        // 退化成按当初压缩的条数折叠
        firstHistoryIndex + state.compressedCount
    }.coerceAtMost(messages.size - 1)

    // 折叠后至少要留一条真实历史（当前这轮的输入必须还在）
    if (cutIndex <= firstHistoryIndex) return messages

    // system prompt（如果有）在折叠段之前，原样保留
    val head = messages.subList(0, firstHistoryIndex)
    val rest = messages.subList(cutIndex, messages.size)
    val summaryMessage = UIMessage.user(renderCompressedSummary(state)).copy(isSynthetic = true)

    return buildList {
        addAll(head)
        add(summaryMessage)
        addAll(rest)
    }
}

private fun renderCompressedSummary(state: CompressedContext): String = buildString {
    appendLine("<compressed_context>")
    appendLine(
        "下面是本对话更早消息的压缩摘要。" +
            "原始聊天记录完整保留在界面上，只是因为太长而折叠起来，" +
            "回答时请以这段摘要 + 后面未折叠的消息为准。"
    )
    appendLine()
    append(state.summary)
    appendLine()
    append("</compressed_context>")
}
