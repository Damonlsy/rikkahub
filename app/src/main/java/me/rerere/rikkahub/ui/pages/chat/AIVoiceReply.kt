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

/** auto 模式下触发语音回复的文字关键词 */
private val AUTO_VOICE_TRIGGERS = listOf(
    "读给我听", "念给我听", "说给我听", "讲给我听",
    "读出来", "念出来", "朗读", "播报", "语音回复",
)

/**
 * AI 主动发语音的标记：auto 模式下回复文本带 `[语音]` / `[voice]` 就合成语音条，
 * 合成与展示时都会把标记本身去掉。提示词由 [me.rerere.rikkahub.data.ai.transformers.VoiceReplyHintTransformer] 注入。
 */
val VOICE_REPLY_MARKER = Regex("""\[语音]|\[voice]""", RegexOption.IGNORE_CASE)

/**
 * AI 语音回复 auto 模式：判断这条待发送消息是否要求下一条 AI 回复合成语音条。
 * 触发条件：用户发了语音消息，或文字里带触发词。
 */
fun Settings.shouldAutoVoiceReply(parts: List<UIMessagePart>): Boolean {
    if (displaySetting.aiVoiceReplyMode != "auto") return false
    if (parts.any { it is UIMessagePart.Audio }) return true
    val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    return AUTO_VOICE_TRIGGERS.any { text.contains(it) }
}

/**
 * AI 语音回复：生成结束后把回复文本合成为语音条，追加到最后一条助手消息上。
 * 设置由 [Settings.displaySetting.aiVoiceReplyMode] 控制：
 * off（关闭）/ auto（仅触发时）/ text_and_voice（每条）/ voice_only（每条，隐藏文字）。
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

            // auto：发送时被触发（语音消息/触发词），或 AI 自己在回复里放了 [语音] 标记
            if (mode == "auto") {
                val triggered = vm.pendingAutoVoice || VOICE_REPLY_MARKER.containsMatchIn(rawText)
                vm.consumeAutoVoiceTrigger()
                if (!triggered) return@collect
            }

            val text = rawText.replace(VOICE_REPLY_MARKER, "").stripMarkdown()
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
