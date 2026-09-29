package me.rerere.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Damonlsy fork：把一段录好的音频文件转成文字（语音消息转文字用）。
 *
 * 与 [ASRController]（麦克风实时流式）不同，这里是拿现成文件一次性识别：
 * - OpenAI Realtime：直接把原文件丢给 /v1/audio/transcriptions
 * - DashScope：解码成 WAV 后走 qwen3-asr-flash 的 multimodal-generation 接口
 * - Step：解码成 PCM 后走 /v1/audio/asr/sse（与实时接口同格式）
 * - MiMo：解码成 WAV 后走 chat/completions（与实时接口同格式）
 * - Volcengine：暂不支持文件转写，返回 null（调用方按"没转出来"处理）
 *
 * 返回识别文本；失败/不支持返回 null。
 */
object FileASRTranscriber {
    private const val TAG = "FileASRTranscriber"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun transcribe(provider: ASRProviderSetting, audioFile: File): String? =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists() || audioFile.length() == 0L) return@withContext null
            runCatching {
                when (provider) {
                    is ASRProviderSetting.OpenAIRealtime -> transcribeOpenAI(provider, audioFile)
                    is ASRProviderSetting.DashScope -> transcribeDashScope(provider, audioFile)
                    is ASRProviderSetting.Step -> transcribeStep(provider, audioFile)
                    is ASRProviderSetting.MiMo -> transcribeMiMo(provider, audioFile)
                    else -> {
                        Log.w(TAG, "unsupported ASR provider for file transcription: ${provider::class.simpleName}")
                        null
                    }
                }
            }.onFailure {
                Log.w(TAG, "transcribe failed: provider=${provider::class.simpleName} file=${audioFile.name} size=${audioFile.length()}", it)
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "transcribe returned empty: provider=${provider::class.simpleName}")
                    null
                }
        }

    // ---- OpenAI（gpt-4o-transcribe 等）：原文件 multipart 直传 ----

    private fun transcribeOpenAI(provider: ASRProviderSetting.OpenAIRealtime, file: File): String? {
        val base = provider.websocketUrl
            .substringBefore('?')
            .replaceFirst("wss://", "https://")
            .replaceFirst("http://", "https://")
        val endpoint = base.substringBeforeLast("/", missingDelimiterValue = "") + "/audio/transcriptions"
            .ifBlank { "https://api.openai.com/v1/audio/transcriptions" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model.ifBlank { "gpt-4o-transcribe" })
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .post(body)
            .build()
        val text = executeJson(request).optString("text", "").trim()
        return text.ifBlank { null }
    }

    // ---- DashScope（qwen3-asr-flash）：WAV Data URL ----

    private fun transcribeDashScope(provider: ASRProviderSetting.DashScope, file: File): String? {
        val host = provider.websocketUrl
            .substringAfter("wss://", "")
            .substringAfter("https://", "")
            .substringBefore("/")
            .ifBlank { "dashscope.aliyuncs.com" }
        val endpoint = "https://$host/api/v1/services/aigc/multimodal-generation/generation"
        val pcm = decodeToPcm16Mono(file, 16_000) ?: return null
        val wav = pcm16ToWav(pcm, 16_000)
        val dataUri = "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)
        val content = JSONArray().put(
            JSONObject().put("audio", dataUri)
        )
        val message = JSONObject().put("role", "user").put("content", content)
        val body = JSONObject()
            .put("model", provider.model.removeSuffix("-realtime").ifBlank { "qwen3-asr-flash" })
            .put("input", JSONObject().put("messages", JSONArray().put(message)))
        if (provider.language.isNotBlank()) {
            body.put("parameters", JSONObject().put("asr_options", JSONObject().put("language", provider.language)))
        }
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val json = executeJson(request)
        // 兼容 output.choices（原生）与 choices（兼容模式）两种返回
        val messageJson = json.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return null
        return flattenContent(messageJson.opt("content"))
    }

    private fun flattenContent(content: Any?): String? = when (content) {
        is String -> content.trim().ifBlank { null }
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val item = content.opt(i)
                val piece = when (item) {
                    is JSONObject -> item.optString("text", "")
                    else -> item?.toString().orEmpty()
                }
                if (piece.isNotBlank()) append(piece)
            }
        }.trim().ifBlank { null }
        else -> null
    }

    // ---- Step：PCM Data URL 走 SSE 端点（与实时接口同格式）----

    private fun transcribeStep(provider: ASRProviderSetting.Step, file: File): String? {
        val pcm = decodeToPcm16Mono(file, provider.sampleRate) ?: return null
        val transcription = JSONObject()
            .put("model", provider.model)
            .put("enable_itn", provider.enableItn)
            .put("enable_timestamp", false)
        if (provider.language.isNotBlank() && provider.language != "auto") {
            transcription.put("language", provider.language)
        }
        val body = JSONObject()
            .put(
                "audio",
                JSONObject()
                    .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    .put(
                        "input",
                        JSONObject()
                            .put("transcription", transcription)
                            .put(
                                "format",
                                JSONObject()
                                    .put("type", "pcm")
                                    .put("codec", "pcm_s16le")
                                    .put("rate", provider.sampleRate)
                                    .put("bits", 16)
                                    .put("channel", 1)
                            )
                    )
            )
        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/v1/audio/asr/sse")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return executeSse(request)
    }

    // ---- MiMo：WAV Data URL 走 chat/completions（与实时接口同格式）----

    private fun transcribeMiMo(provider: ASRProviderSetting.MiMo, file: File): String? {
        val pcm = decodeToPcm16Mono(file, provider.sampleRate) ?: return null
        val wav = pcm16ToWav(pcm, provider.sampleRate)
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val message = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "input_audio")
                        .put(
                            "input_audio",
                            JSONObject().put("data", "data:audio/wav;base64,$b64")
                        )
                )
            )
        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().put(message))
        if (provider.language.isNotBlank() && provider.language != "auto") {
            body.put("asr_options", JSONObject().put("language", provider.language))
        }
        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("api-key", provider.apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = executeJson(request)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content", "")?.trim()
        return text?.ifBlank { null }
    }

    // ---- HTTP 工具 ----

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("ASR HTTP ${resp.code}: ${body.take(500)}")
            }
            return runCatching { JSONObject(body) }
                .getOrElse { throw IOException("ASR response is not JSON: ${body.take(500)}") }
        }
    }

    /** 解析 Step 风格的 SSE 转写响应，返回识别文本。 */
    private fun executeSse(request: Request): String? {
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("ASR HTTP ${resp.code}: ${resp.body.string().take(500)}")
            }
            val transcript = StringBuilder()
            var eventType: String? = null
            val dataLines = mutableListOf<String>()

            fun dispatch(): Boolean {
                if (eventType == null && dataLines.isEmpty()) return false
                val data = dataLines.joinToString("\n")
                val type = eventType ?: runCatching { JSONObject(data).optString("type") }.getOrNull()
                eventType = null
                dataLines.clear()
                if (data == "[DONE]") return true
                val json = runCatching { JSONObject(data) }.getOrNull()
                return when (type) {
                    "transcript.text.done" -> {
                        val finalText = extractText(json, "")
                        if (finalText.isNotBlank()) {
                            transcript.clear()
                            transcript.append(finalText)
                        }
                        true
                    }
                    "error" -> throw IOException("ASR error: ${extractText(json, data)}")
                    else -> {
                        val piece = extractText(json, "")
                        if (piece.isNotBlank()) transcript.append(piece)
                        false
                    }
                }
            }

            val source: BufferedSource = resp.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (dispatch()) break
                    continue
                }
                if (line.startsWith(":")) continue
                val sep = line.indexOf(':')
                val field = if (sep == -1) line else line.substring(0, sep)
                val value = if (sep == -1) "" else line.substring(sep + 1).removePrefix(" ")
                when (field) {
                    "event" -> eventType = value
                    "data" -> dataLines.add(value)
                }
            }
            dispatch()
            return transcript.toString().trim().ifBlank { null }
        }
    }

    private fun extractText(json: JSONObject?, fallback: String): String {
        if (json == null) return fallback
        for (key in listOf("delta", "text", "content", "transcript")) {
            val value = json.opt(key) ?: continue
            if (value is JSONObject) {
                val nested = extractText(value, "")
                if (nested.isNotBlank()) return nested
            } else {
                val text = value.toString()
                if (text.isNotBlank()) return text
            }
        }
        for (key in listOf("data", "result", "transcript")) {
            val nested = json.optJSONObject(key) ?: continue
            val value = extractText(nested, "")
            if (value.isNotBlank()) return value
        }
        return fallback
    }

    // ---- 音频解码：文件 -> PCM16 单声道 ----

    /**
     * 解码任意 MediaCodec 支持的音频（m4a/aac/mp3/wav...）为 16bit 单声道 PCM，
     * 并按 [targetSampleRate] 线性重采样。失败返回 null。
     */
    internal fun decodeToPcm16Mono(file: File, targetSampleRate: Int): ByteArray? = runCatching {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return@runCatching null
        }

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return@runCatching null
        val srcRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
        val srcChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        if (srcRate <= 0) {
            extractor.release()
            return@runCatching null
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        extractor.selectTrack(trackIndex)

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk)
                            out.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val pcm = out.toByteArray()
        if (pcm.isEmpty()) return@runCatching null
        val mono = downmixToMono(pcm, srcChannels)
        val resampled = if (srcRate != targetSampleRate) {
            resamplePcm16(mono, srcRate, targetSampleRate)
        } else {
            mono
        }
        resampled.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 多声道交错 PCM16 -> 单声道（取平均）。 */
    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val frameSize = channels * 2
        val frames = pcm.size / frameSize
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val offset = f * frameSize + c * 2
                val sample = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
                sum += sample.toInt().toShort().toInt()
            }
            val avg = (sum / channels).toShort()
            out[f * 2] = (avg.toInt() and 0xFF).toByte()
            out[f * 2 + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** PCM16 线性重采样（对 ASR 足够）。 */
    private fun resamplePcm16(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcFrames = pcm.size / 2
        if (srcFrames == 0) return pcm
        val dstFrames = ((srcFrames.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(dstFrames * 2)
        for (i in 0 until dstFrames) {
            val srcPos = i * (srcFrames - 1).toDouble() / (dstFrames - 1).coerceAtLeast(1)
            val left = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val right = (left + 1).coerceAtMost(srcFrames - 1)
            val frac = srcPos - left
            val s0 = readSample(pcm, left)
            val s1 = readSample(pcm, right)
            val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
            out[i * 2] = (sample.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(pcm: ByteArray, frame: Int): Double {
        val offset = frame * 2
        val raw = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
        return raw.toShort().toInt().toDouble()
    }

    /** PCM16 单声道 -> WAV 容器。 */
    internal fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun intBytes(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )

        fun shortBytes(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
        )

        with(out) {
            write("RIFF".toByteArray())
            write(intBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intBytes(16))
            write(shortBytes(1))
            write(shortBytes(1))
            write(intBytes(sampleRate))
            write(intBytes(byteRate))
            write(shortBytes(2))
            write(shortBytes(16))
            write("data".toByteArray())
            write(intBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }
}
