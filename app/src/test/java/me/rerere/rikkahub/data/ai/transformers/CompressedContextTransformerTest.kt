package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.CompressedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompressedContextTransformerTest {

    @Test
    fun `null state should keep messages untouched`() {
        val messages = conversation()

        val result = foldCompressedContext(messages, null)

        assertSame(messages, result)
    }

    @Test
    fun `history before boundary should be replaced by summary`() {
        val messages = conversation()
        val boundary = messages[2]
        val state = CompressedContext(
            summary = "earlier the user asked about weather",
            boundaryMessageId = boundary.id.toString(),
            compressedCount = 2,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.USER, // the synthetic summary
                MessageRole.USER,
                MessageRole.ASSISTANT,
            ),
            result.map { it.role },
        )
        val summary = result[1]
        assertTrue(summary.isSynthetic)
        assertTrue(summary.parts.any { part -> part.toString().contains("<compressed_context>") })
        assertTrue(summary.parts.any { part -> part.toString().contains("earlier the user asked about weather") })
    }

    @Test
    fun `recent messages after boundary should stay verbatim`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "summary",
            boundaryMessageId = messages[2].id.toString(),
            compressedCount = 2,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        assertEquals(messages.drop(3), result.drop(result.size - (messages.size - 3)))
    }

    @Test
    fun `missing boundary should fall back to compressed count`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "summary",
            boundaryMessageId = Uuid.random().toString(),
            compressedCount = 2,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        // 0 = system, then 1..2 folded away, summary takes their place
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.USER,
                MessageRole.ASSISTANT,
            ),
            result.map { it.role },
        )
        assertTrue(result[1].isSynthetic)
    }

    @Test
    fun `summary should come right after system prompt`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "summary",
            boundaryMessageId = messages[1].id.toString(),
            compressedCount = 1,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        assertEquals(MessageRole.SYSTEM, result.first().role)
        assertEquals(MessageRole.USER, result[1].role)
        assertTrue(result[1].isSynthetic)
    }

    @Test
    fun `boundary at the last message should keep it and never empty the list`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "summary",
            boundaryMessageId = messages.last().id.toString(),
            compressedCount = 99,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        assertEquals(messages.last(), result.last())
        assertTrue(result.any { it.isSynthetic })
    }

    @Test
    fun `blank summary should keep messages untouched`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "  ",
            boundaryMessageId = messages[1].id.toString(),
            compressedCount = 1,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)

        assertSame(messages, result)
    }

    @Test
    fun `conversation without history should keep messages untouched`() {
        val messages = listOf(UIMessage.user("only one"))

        val result = foldCompressedContext(
            messages,
            CompressedContext("s", messages[0].id.toString(), 1, 0),
        )

        assertSame(messages, result)
    }

    @Test
    fun `summary text should be wrapped for the model`() {
        val messages = conversation()
        val state = CompressedContext(
            summary = "the user prefers short answers",
            boundaryMessageId = messages[2].id.toString(),
            compressedCount = 2,
            updatedAt = 0,
        )

        val result = foldCompressedContext(messages, state)
        val text = result[1].parts.joinToString("\n") { it.toString() }

        assertTrue(text.contains("<compressed_context>"))
        assertTrue(text.contains("</compressed_context>"))
        assertFalse(text.contains("New user question"))
    }

    private fun conversation(): List<UIMessage> {
        val system = UIMessage.system("system prompt")
        val m1 = UIMessage.user("hello")
        val m2 = UIMessage.assistant("hi")
        val m3 = UIMessage.user("what is the weather")
        val m4 = UIMessage.assistant("sunny")
        return listOf(system, m1, m2, m3, m4)
    }
}
