package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.CompressedContext
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSizeInfoTest {

    @Test
    fun `without compression every message counts`() {
        val info = computeConversationSizeInfo(nodes(100), null)

        assertEquals(100, info.nodeCount)
        assertEquals(100, info.totalNodeCount)
        assertTrue(info.softWarning)
        assertFalse(info.hardWarning)
    }

    @Test
    fun `hard warning starts at 300 messages`() {
        assertFalse(computeConversationSizeInfo(nodes(299), null).hardWarning)
        assertTrue(computeConversationSizeInfo(nodes(300), null).hardWarning)
    }

    @Test
    fun `after compression only messages after the boundary count`() {
        val nodes = nodes(500)
        val info = computeConversationSizeInfo(nodes, compressedAt(nodes, boundaryIndex = 460))

        // 500 条里前 461 条已被折叠，只剩 39 条要判
        assertEquals(39, info.nodeCount)
        assertEquals(500, info.totalNodeCount)
        assertFalse(info.softWarning)
        assertFalse(info.hardWarning)
        assertFalse(info.showWarning)
    }

    @Test
    fun `missing boundary falls back to compressed count`() {
        val nodes = nodes(500)
        val state = CompressedContext(
            summary = "summary",
            boundaryMessageId = Uuid.random().toString(),
            compressedCount = 461,
            updatedAt = 0,
        )

        val info = computeConversationSizeInfo(nodes, state)

        assertEquals(39, info.nodeCount)
        assertFalse(info.hardWarning)
    }

    @Test
    fun `stale tokens from before compression are ignored`() {
        val nodes = withAssistantUsage(nodes(500), index = 100, promptTokens = 250_000)
        val info = computeConversationSizeInfo(nodes, compressedAt(nodes, boundaryIndex = 460))

        assertEquals(0, info.lastAssistantInputTokens)
        assertFalse(info.hardWarning)
    }

    @Test
    fun `tokens generated after compression still count`() {
        val nodes = withAssistantUsage(nodes(500), index = 480, promptTokens = 70_000)
        val info = computeConversationSizeInfo(nodes, compressedAt(nodes, boundaryIndex = 460))

        assertEquals(70_000, info.lastAssistantInputTokens)
        assertTrue(info.softWarning)
        assertFalse(info.hardWarning)
    }

    private fun nodes(count: Int): List<MessageNode> =
        (0 until count).map { MessageNode.of(UIMessage.user("m$it")) }

    private fun compressedAt(nodes: List<MessageNode>, boundaryIndex: Int) = CompressedContext(
        summary = "summary",
        boundaryMessageId = nodes[boundaryIndex].currentMessage.id.toString(),
        compressedCount = boundaryIndex + 1,
        updatedAt = 0,
    )

    private fun withAssistantUsage(
        nodes: List<MessageNode>,
        index: Int,
        promptTokens: Int,
    ): List<MessageNode> {
        val updated = nodes.toMutableList()
        updated[index] = MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("answer")),
                usage = TokenUsage(promptTokens = promptTokens),
            )
        )
        return updated
    }
}
