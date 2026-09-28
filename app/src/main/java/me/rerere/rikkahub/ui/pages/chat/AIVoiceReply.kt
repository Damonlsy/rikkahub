package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.isCallMessage
import me.rerere.rikkahub.utils.pcmToWav
import me.rerere.rikkahub.utils.probeAudioDurationMs
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.rikkahub.utils.voiceAudioMetadata
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSManager
import org.koin.compose.koinInject

private const val TAG = "AIVoiceReply"

/**
 * AI 语音回复：生成结束后把回复文本合成为语音条，追加到最后一条助手消息上。
 * 设置三态由 [Settings.displaySetting.aiVoiceReplyMode] 控制（off/text_and_voice/voice_only）。
 */
@Composable
fun AIVoiceReply(
    vm: ChatVM,
    setting: Settings,
    conversation: Conversation,
) {
    val filesManager: FilesManager = koinInject()
    val ttsManager: TTSManager = koinInject()
    val currentConversation by rememberUpdatedState(conversation)
    val updatedSetting by rememberUpdatedState(setting)

    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { conversationId ->
            if (conversationId != currentConversation.id) return@collect

            val settings = updatedSetting
            val mode = settings.displaySetting.aiVoiceReplyMode
            if (mode == "off") return@collect
            // 语音模式自己负责朗读，这里不重复合成
            if (vm.voiceSession.state.value.isActive) return@collect
            val provider = settings.getSelectedTTSProvider() ?: return@collect

            val target = currentConversation.currentMessages.lastOrNull() ?: return@collect
            if (target.role != MessageRole.ASSISTANT || target.isCallMessage()) return@collect
            // 幂等：同一条消息只挂一次语音条
            if (target.parts.any { it is UIMessagePart.Audio }) return@collect

            val rawText = target.toText()
            if (rawText.isBlank()) return@collect
            val text = rawText.stripMarkdown()
            if (text.isBlank()) return@collect

            runCatching {
                val synthesizer = TtsSynthesizer(ttsManager)
                val chunks = TextChunker().split(text)
                val audioParts = mutableListOf<UIMessagePart>()

                chunks.forEachIndexed { index, chunk ->
                    val response = synthesizer.synthesize(
                        provider,
                        TtsChunk(index = index, text = chunk.text),
                    )
                    val bytes = if (response.format == AudioFormat.PCM) {
                        pcmToWav(response.audioData, response.sampleRate ?: 24_000)
                    } else {
                        response.audioData
                    }
                    val (extension, mimeType) = when (response.format) {
                        AudioFormat.PCM, AudioFormat.WAV -> "wav" to "audio/wav"
                        AudioFormat.MP3 -> "mp3" to "audio/mpeg"
                        AudioFormat.OGG, AudioFormat.OPUS -> "ogg" to "audio/ogg"
                        AudioFormat.AAC -> "aac" to "audio/aac"
                    }
                    val managed = filesManager.saveUploadFromBytes(
                        bytes = bytes,
                        displayName = "ai_voice_${System.currentTimeMillis()}_$index.$extension",
                        mimeType = mimeType,
                    )
                    val file = filesManager.getFile(managed)
                    val durationMs = response.duration
                        ?.let { (it * 1000).toLong() }
                        ?: probeAudioDurationMs(file)
                        ?: 0L
                    audioParts += UIMessagePart.Audio(
                        url = file.absolutePath,
                        metadata = voiceAudioMetadata(
                            durationMs = durationMs,
                            aiVoice = true,
                        ),
                    )
                }

                if (audioParts.isEmpty()) return@runCatching
                vm.appendAssistantMessageParts(
                    messageId = target.id,
                    parts = audioParts,
                    hideText = mode == "voice_only",
                )
            }.onFailure {
                Log.w(TAG, "voice synthesis failed", it)
            }
        }
    }
}
