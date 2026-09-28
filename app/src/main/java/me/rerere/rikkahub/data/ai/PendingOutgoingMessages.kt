package me.rerere.rikkahub.data.ai

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart

/**
 * 生成过程中的「AI 主动发出的消息」（表情包 / 拍一拍）不能直接插进 messageNodes：
 * [me.rerere.rikkahub.data.model.Conversation.updateCurrentMessages] 是按下标映射的，
 * 生成期间插节点会把流式回复写错位置。所以先在这里排队，等这一轮生成结束再一次性追加。
 */
class PendingOutgoingMessages {
    private val queues = ConcurrentHashMap<Uuid, MutableList<List<UIMessagePart>>>()

    fun enqueue(conversationId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return
        queues.computeIfAbsent(conversationId) { mutableListOf() }.let { queue ->
            synchronized(queue) { queue.add(parts.toList()) }
        }
    }

    fun drain(conversationId: Uuid): List<List<UIMessagePart>> {
        val queue = queues.remove(conversationId) ?: return emptyList()
        return synchronized(queue) { queue.toList() }
    }
}
