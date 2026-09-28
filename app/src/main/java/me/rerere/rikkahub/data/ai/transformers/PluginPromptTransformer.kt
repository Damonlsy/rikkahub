package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

/**
 * 插件提示词注入转换器
 *
 * 把插件声明的"能力总览"和开启了 inject_as_prompt 的 promptTemplate 追加到
 * 系统提示词末尾，让模型知道有哪些插件工具可主动调用。
 *
 * 上游做法是给 GenerationLoop.generateText 加一个 pluginPromptInjections 参数；
 * 本分支复用既有的 InputMessageTransformer 机制，避免改动生成循环的签名。
 */
class PluginPromptTransformer(
    private val pluginToolProvider: PluginToolProvider,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val injections = try {
            pluginToolProvider.getPluginPromptInjections()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect plugin prompt injections", e)
            emptyList()
        }
        if (injections.isEmpty()) return messages

        val content = injections.joinToString("\n")

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex >= 0) {
            val systemMessage = messages[systemIndex]
            val newParts = systemMessage.parts.toMutableList()
            val firstTextIndex = newParts.indexOfFirst { it is UIMessagePart.Text }
            if (firstTextIndex >= 0) {
                val text = newParts[firstTextIndex] as UIMessagePart.Text
                newParts[firstTextIndex] = text.copy(text = text.text + "\n" + content)
            } else {
                newParts.add(0, UIMessagePart.Text(content))
            }

            val updated = messages.toMutableList()
            updated[systemIndex] = systemMessage.copy(
                parts = newParts,
                isSynthetic = true,
            )
            return updated
        }

        // 没有系统消息时，创建一个新的系统消息放在最前面
        val updated = messages.toMutableList()
        updated.add(0, UIMessage.system(content).copy(isSynthetic = true))
        return updated
    }

    companion object {
        private const val TAG = "PluginPromptTransformer"
    }
}
