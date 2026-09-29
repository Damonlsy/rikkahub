package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Damonlsy fork：AI 语音回复 auto 模式的提示注入。
 *
 * 只在 设置→语音→AI语音回复 = auto 时，往系统提示词末尾加一段说明：
 * 告诉模型它可以用 `[语音]` 标记主动要求生成语音条（客户端会合成并去掉标记）。
 * 其他模式（off/text_and_voice/voice_only）不注入。
 */
object VoiceReplyHintTransformer : InputMessageTransformer {
    private const val HINT = """
<voice_reply>
本客户端支持语音回复：在某些情况下回复会自动附带一条合成的语音（例如用户发了语音消息或明确要求语音）。
如果你想主动用语音回复，就在这条回复文本的最开头单独放一个标记 [语音]（只放标记，不要解释），客户端会生成语音条并把标记从聊天里去掉；不想发语音就不要加这个标记。
普通文字回复不受影响。
</voice_reply>
"""

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.settings.displaySetting.aiVoiceReplyMode != "auto") return messages

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$HINT")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(HINT).copy(isSynthetic = true)) + messages
        }
    }

    private fun UIMessage.appendText(extra: String): UIMessage {
        val updatedParts = parts.toMutableList()
        val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
        return if (firstTextIndex >= 0) {
            val text = updatedParts[firstTextIndex] as UIMessagePart.Text
            updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
            copy(parts = updatedParts)
        } else {
            copy(parts = updatedParts + UIMessagePart.Text(extra))
        }
    }
}
