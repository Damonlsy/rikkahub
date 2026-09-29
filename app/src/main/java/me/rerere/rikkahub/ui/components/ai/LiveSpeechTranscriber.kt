package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Damonlsy fork：按住录音的同时用手机自带的系统识别服务（Google 语音等）出文字，
 * 给语音消息附带转写文本。不走网络配置、不需要 API key，属于「本机识别」兜底；
 * 配了模型 ASR 的用户仍然优先走文件转写（见 ChatPage）。
 *
 * 注意：SpeechRecognizer 必须在主线程创建和调用（Compose 手势/重组都在主线程，满足）。
 * 文本取识别过程中的最新结果（partial 或 final），结束时最多再等 [AWAIT_FINAL_MS] 拿 final。
 */
internal class LiveSpeechTranscriber(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var latestText: String = ""

    @Volatile
    private var finalReceived = false

    @Volatile
    private var failed = false

    /** 是否有可用的系统识别服务。 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 开始听写；不可用/启动失败返回 false（调用方可以继续录音，只是拿不到文字）。 */
    fun start(): Boolean {
        if (failed) return false
        if (!isAvailable()) {
            failed = true
            Log.w(TAG, "recognition service not available")
            return false
        }
        return try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
            this.recognizer = recognizer
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            failed = true
            false
        }
    }

    /** 停掉识别并返回最新文本（没有则 null）。幂等，可重复调用。 */
    fun stop(): String? {
        val active = recognizer
        recognizer = null
        if (active != null) {
            runCatching { active.cancel() }
            runCatching { active.destroy() }
        }
        return latestText.trim().ifBlank { null }
    }

    /**
     * 录音结束后短暂等一下 final 结果（Google 通常几百毫秒内回），
     * 超时就用当前 partial 文本。
     */
    suspend fun awaitFinal(timeoutMs: Long = AWAIT_FINAL_MS): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!finalReceived && !failed && recognizer != null && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return stop()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            // 常见：1 网络、2 客户端、3 音频、5 无匹配、6 超时、7 权限、8 忙。
            // 保留已拿到的 partial 文本，只是不再等 final。
            Log.w(TAG, "recognition error: $error")
            failed = true
        }

        override fun onResults(results: Bundle?) = consume(results, final = true)
        override fun onPartialResults(partialResults: Bundle?) = consume(partialResults, final = false)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun consume(bundle: Bundle?, final: Boolean) {
            val text = bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) {
                latestText = text
            }
            if (final) {
                finalReceived = true
            }
        }
    }

    companion object {
        private const val TAG = "LiveSpeechTranscriber"
        private const val AWAIT_FINAL_MS = 700L
    }
}
