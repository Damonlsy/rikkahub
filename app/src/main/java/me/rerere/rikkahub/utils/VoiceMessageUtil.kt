package me.rerere.rikkahub.utils

import android.media.MediaMetadataRetriever
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Text part metadata flag marking a system-style call record message. */
const val CALL_META_KEY = "call"
const val VOICE_META_DURATION_MS = "duration_ms"
const val VOICE_META_AI_VOICE = "ai_voice"
const val VOICE_META_HIDE_TEXT = "hide_text"

fun buildCallParts(text: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject { put(CALL_META_KEY, true) },
    ),
)

fun UIMessage.isCallMessage(): Boolean = parts.any { part ->
    part is UIMessagePart.Text && (part.metadata?.get(CALL_META_KEY) as? JsonPrimitive)?.booleanOrNull == true
}

fun voiceAudioMetadata(durationMs: Long, aiVoice: Boolean? = null): JsonObject = buildJsonObject {
    put(VOICE_META_DURATION_MS, durationMs)
    if (aiVoice != null) {
        put(VOICE_META_AI_VOICE, aiVoice)
    }
}

fun voiceAudioDurationMs(metadata: JsonObject?): Long? =
    (metadata?.get(VOICE_META_DURATION_MS) as? JsonPrimitive)?.longOrNull

fun isVoiceHiddenText(metadata: JsonObject?): Boolean =
    (metadata?.get(VOICE_META_HIDE_TEXT) as? JsonPrimitive)?.booleanOrNull == true

fun UIMessagePart.Text.withVoiceHidden(): UIMessagePart.Text {
    val base = metadata ?: JsonObject(emptyMap())
    return copy(metadata = JsonObject(base.toMap() + (VOICE_META_HIDE_TEXT to JsonPrimitive(true))))
}

/** Wrap raw PCM into a minimal WAV container (mirrors the speech module's AudioPlayer). */
fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val out = ByteArrayOutputStream(44 + pcm.size)

    fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte(),
    )

    with(out) {
        write("RIFF".toByteArray())
        write(intToBytes(36 + pcm.size))
        write("WAVE".toByteArray())
        write("fmt ".toByteArray())
        write(intToBytes(16))
        write(shortToBytes(1))
        write(shortToBytes(channels.toShort()))
        write(intToBytes(sampleRate))
        write(intToBytes(byteRate))
        write(shortToBytes((channels * bitsPerSample / 8).toShort()))
        write(shortToBytes(bitsPerSample.toShort()))
        write("data".toByteArray())
        write(intToBytes(pcm.size))
        write(pcm)
    }
    return out.toByteArray()
}

/** Best-effort duration probe for a local audio file; null when unavailable. */
fun probeAudioDurationMs(file: File): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/** "125" seconds -> "02:05" */
fun formatCallDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
