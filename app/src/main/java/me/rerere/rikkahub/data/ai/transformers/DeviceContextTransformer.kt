package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.buildDeviceContextBlock

/**
 * Damonlsy fork：设备上下文注入转换器
 *
 * 每次生成前把「当前时间 / 电量 / 天气 / 最近用过的应用」拼成一段 <device_context>
 * 追加到系统提示词末尾。这样模型不用调工具就能看到这些信息，
 * 而且因为内容只存在于系统提示词里，聊天界面不会显示它。
 */
object DeviceContextTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val block = buildDeviceContextBlock(ctx.context, ctx.settings.deviceContext)
            ?: return messages

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$block")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(block).copy(isSynthetic = true)) + messages
        }
    }

    private fun UIMessage.appendText(extra: String): UIMessage {
        val updatedParts = parts.toMutableList()
        val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
        if (firstTextIndex >= 0) {
            val text = updatedParts[firstTextIndex] as UIMessagePart.Text
            updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
        } else {
            updatedParts.add(UIMessagePart.Text(extra))
        }
        return copy(parts = updatedParts)
    }
}
