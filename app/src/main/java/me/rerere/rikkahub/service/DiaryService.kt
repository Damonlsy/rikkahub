package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

/**
 * Fork feature (Damonlsy): lets the AI write diary entries and comment on them.
 *
 * Reuses the configured "fast model" so it works without extra setup, mirroring
 * the one-shot text generation used by conversation title generation.
 */
class DiaryService(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    private suspend fun generate(prompt: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId) ?: return@runCatching null
            val provider = model.findProvider(settings.providers) ?: return@runCatching null
            val handler = providerManager.getProviderByType(provider)
            val result = handler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt = prompt)),
                params = backgroundTextGenerationParams(
                    model,
                    Uuid.random(),
                    settings.fastModelReasoningLevel,
                ),
            )
            result.message.toText().trim()
        }.getOrNull()
    }

    /** Returns (title, content) or null on failure. */
    suspend fun writeDiary(): Pair<String, String>? {
        val raw = generate(
            "请你以第一人称写一篇今天的日记。要求：真实自然，有情绪和具体细节，150 字左右。" +
                "严格按下面的格式输出，不要任何多余内容：\n" +
                "标题：<一句话标题>\n" +
                "内容：<正文>"
        ) ?: return null
        return parseTitleContent(raw)
    }

    /** Generates a short comment for an existing diary entry. */
    suspend fun comment(
        diaryTitle: String,
        diaryContent: String,
        existingComments: List<String>,
    ): String? {
        val context = buildString {
            appendLine("日记标题：$diaryTitle")
            appendLine("日记内容：$diaryContent")
            if (existingComments.isNotEmpty()) {
                appendLine("已有评论：")
                existingComments.forEach { appendLine("- $it") }
            }
        }
        return generate(
            "$context\n请你以朋友的口吻写一句简短、真诚的评论（30 字以内），只输出评论内容本身。"
        )?.takeIf { it.isNotBlank() }
    }

    private fun parseTitleContent(raw: String): Pair<String, String> {
        val title = Regex("标题[:：]\\s*(.+)").find(raw)?.groupValues?.get(1)?.trim()
        val content = Regex("内容[:：]\\s*([\\s\\S]+)").find(raw)?.groupValues?.get(1)?.trim()
        if (!title.isNullOrBlank() && !content.isNullOrBlank()) return title to content
        val fallbackTitle = raw.lineSequence().firstOrNull()?.trim()?.take(20).orEmpty()
        return (fallbackTitle.ifBlank { "日记" }) to raw
    }
}
