package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.StickerDAO
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StickerClassifier"

/** 打标进度：正在识别第 done 张，共 total 张；空闲时为 null。 */
data class StickerClassifyProgress(val done: Int, val total: Int)

/**
 * 表情包识图打标：用 OCR/视觉模型看一遍每张表情包，写入分类和一句话描述，
 * 之后 AI 在工具库存里能直接看到「这张图是什么」，不用瞎猜。
 *
 * 用法：设置页按钮手动触发、表情包导入后自动触发。重复调用只处理还没打过标的。
 */
class StickerClassifier(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val dao: StickerDAO,
) {
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)

    private val _progress = MutableStateFlow<StickerClassifyProgress?>(null)
    val progress: StateFlow<StickerClassifyProgress?> = _progress.asStateFlow()

    /**
     * 识别所有还没打过标（description 为空）的表情包，已完成的跳过。
     * 已有任务在跑时直接返回。
     */
    suspend fun classifyPending() {
        if (!running.compareAndSet(false, true)) return
        try {
            val todo = dao.getAll().filter { it.description.isBlank() }
            if (todo.isEmpty()) {
                Log.i(TAG, "classifyPending: nothing to do")
                return
            }
            if (!mutex.tryLock()) return
            _progress.value = StickerClassifyProgress(0, todo.size)
            try {
                val settings = settingsStore.settingsFlow.value
                val model = settings.findModelById(settings.ocrModelId) ?: run {
                    Log.w(TAG, "classifyPending: no vision/OCR model configured, skip")
                    return
                }
                val providerSetting = model.findProvider(settings.providers) ?: run {
                    Log.w(TAG, "classifyPending: provider not found for OCR model ${model.id}")
                    return
                }
                if (Modality.IMAGE !in model.inputModalities) {
                    Log.w(TAG, "classifyPending: model ${model.id} has no image input")
                    return
                }
                val provider = providerManager.getProviderByType(providerSetting)
                todo.forEachIndexed { index, sticker ->
                    runCatching {
                        val result = provider.generateText(
                            providerSetting = providerSetting,
                            messages = listOf(
                                UIMessage.system(CLASSIFY_PROMPT),
                                UIMessage(
                                    role = MessageRole.USER,
                                    parts = listOf(UIMessagePart.Image(sticker.uri)),
                                ),
                            ),
                            params = TextGenerationParams(
                                model = model,
                                customHeaders = model.customHeaders,
                                customBody = model.customBodies,
                            ),
                        )
                        val parsed = parseTag(result.message.toText()) ?: run {
                            Log.w(TAG, "classifyPending: unparseable output for ${sticker.id}")
                            return@runCatching
                        }
                        dao.upsert(
                            sticker.copy(
                                category = parsed.first,
                                description = parsed.second,
                            )
                        )
                    }.onFailure {
                        Log.w(TAG, "classifyPending: failed for ${sticker.id}: $it")
                    }
                    _progress.value = StickerClassifyProgress(index + 1, todo.size)
                }
                Log.i(TAG, "classifyPending: done ${todo.size} stickers")
            } finally {
                mutex.unlock()
            }
        } finally {
            running.set(false)
            _progress.value = null
        }
    }

    private fun parseTag(text: String): Pair<String, String>? = runCatching {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start !in 0 until end) return null
        val obj = JSONObject(text.substring(start, end + 1))
        val category = obj.optString("category").trim()
        val description = obj.optString("description").trim()
        if (description.isBlank()) null else category to description
    }.getOrNull()

    companion object {
        private const val CLASSIFY_PROMPT = """
你在给聊天表情包打标签。看图后只输出一个 JSON 对象，不要任何其他文字或代码块标记：
{"category":"分类","description":"一句话描述"}

category 只能从这些里面选一个：搞笑, 可爱, 无语, 生气, 难过, 庆祝, 吃货, 打工, 谈钱, 撒娇, 阴阳怪气, 恭敬, 愤怒, 其他
description 要求：40 字以内，写清画面里的人物/动物在做什么、什么神态，如果图上有文字必须把文字写进去。
这条描述会帮 AI 在聊天时按气氛挑表情包，请写得能区分不同图。
"""
    }
}
