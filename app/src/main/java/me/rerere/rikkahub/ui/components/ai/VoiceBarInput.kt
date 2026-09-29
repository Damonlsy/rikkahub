package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.rikkahub.R
import java.io.File
import java.util.Locale

private const val MAX_RECORD_MS = 60_000L
private const val MIN_RECORD_MS = 300L
private const val CANCEL_THRESHOLD_DP = 64f

/**
 * 微信式语音条输入：按住录音、松开发送、上滑取消，60 秒自动发送。
 */
@Composable
internal fun VoiceBarInputRow(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onSendRecording: (File, Long, String?) -> Unit,
    liveTranscribe: Boolean = false,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var recorder by remember { mutableStateOf<VoiceBarRecorder?>(null) }
    var transcriber by remember { mutableStateOf<LiveSpeechTranscriber?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val amplitudeHistory = remember { mutableStateListOf<Float>() }

    val holdText = stringResource(R.string.voice_bar_hold)
    val releaseText = stringResource(R.string.voice_bar_release_send)
    val cancelText = stringResource(R.string.voice_bar_slide_cancel)
    val failedText = stringResource(R.string.voice_bar_record_failed)

    fun begin() {
        if (recording) return
        if (!permissionGranted) {
            onRequestPermission()
            return
        }
        val file = File(context.cacheDir, "voicebar_${System.currentTimeMillis()}.m4a")
        val candidate = VoiceBarRecorder(context, file)
        if (candidate.start()) {
            pendingFile = file
            recorder = candidate
            recording = true
            cancelArmed = false
            elapsedMs = 0
            amplitudeHistory.clear()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // 本机识别：录音同时听写出文字（配了模型 ASR 时 ChatPage 会优先用模型结果）
            if (liveTranscribe) {
                val live = LiveSpeechTranscriber(context)
                if (live.start()) {
                    transcriber = live
                }
            }
        } else {
            runCatching { file.delete() }
            onError(failedText)
        }
    }

    fun finish(send: Boolean) {
        val active = recorder ?: return
        val file = pendingFile
        recording = false
        recorder = null
        pendingFile = null
        val cancelled = !send || cancelArmed
        cancelArmed = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val live = transcriber
        transcriber = null
        if (cancelled) {
            active.cancel()
            live?.stop()
            return
        }
        val duration = active.stop()
        if (duration != null && duration >= MIN_RECORD_MS && file != null) {
            if (live == null) {
                onSendRecording(file, duration, null)
            } else {
                // 等几百毫秒看能不能拿到 final 识别结果；用独立 scope，避免手势协程
                // 因 recording 状态变化被取消时把发送也带没了
                scope.launch {
                    try {
                        onSendRecording(file, duration, live.awaitFinal())
                    } finally {
                        live.stop()
                    }
                }
            }
        } else {
            live?.stop()
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(100)
            val active = recorder ?: break
            elapsedMs = active.elapsedMs()
            amplitudeHistory.add(active.amplitude())
            while (amplitudeHistory.size > 24) {
                amplitudeHistory.removeAt(0)
            }
            if (elapsedMs >= MAX_RECORD_MS) {
                finish(send = true)
                break
            }
        }
    }

    val gestureModifier = Modifier.pointerInput(permissionGranted, recording) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!permissionGranted) {
                onRequestPermission()
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                }
                return@awaitEachGesture
            }
            begin()
            if (recording) {
                val startY = down.position.y
                val threshold = CANCEL_THRESHOLD_DP.dp.toPx()
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (pressed && change != null) {
                        cancelArmed = change.position.y < startY - threshold
                    }
                }
                finish(send = !cancelArmed)
            }
        }
    }

    val activeCancel = recording && cancelArmed
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(gestureModifier),
        shape = RoundedCornerShape(24.dp),
        color = if (activeCancel) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (activeCancel) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording) {
                VoiceWaveform(amplitudes = amplitudeHistory)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (cancelArmed) cancelText else releaseText,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatElapsed(elapsedMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Mic01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = holdText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveform(amplitudes: List<Float>) {
    val bars = if (amplitudes.isEmpty()) {
        List(10) { 0.2f }
    } else {
        amplitudes.takeLast(10)
    }
    Row(
        modifier = Modifier.width(72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bars.forEach { amplitude ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + amplitude.coerceIn(0f, 1f) * 20).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LocalContentColor.current.copy(alpha = 0.75f)),
            )
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

internal class VoiceBarRecorder(
    context: Context,
    private val outputFile: File,
) {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    fun start(): Boolean {
        return try {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            startedAt = System.currentTimeMillis()
            true
        } catch (t: Throwable) {
            runCatching { recorder?.release() }
            recorder = null
            runCatching { outputFile.delete() }
            false
        }
    }

    fun elapsedMs(): Long {
        if (recorder == null) return 0L
        return System.currentTimeMillis() - startedAt
    }

    fun amplitude(): Float {
        val raw = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (raw <= 0) return 0f
        return (raw / 32767f).coerceIn(0f, 1f)
    }

    /** Stop and keep the file; null when the recording is unusable (file gets deleted). */
    fun stop(): Long? {
        val active = recorder ?: return null
        recorder = null
        return try {
            active.stop()
            active.release()
            val duration = System.currentTimeMillis() - startedAt
            if (duration < MIN_RECORD_MS || !outputFile.exists() || outputFile.length() == 0L) {
                runCatching { outputFile.delete() }
                null
            } else {
                duration
            }
        } catch (t: Throwable) {
            runCatching { active.release() }
            runCatching { outputFile.delete() }
            null
        }
    }

    fun cancel() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
        runCatching { active.release() }
        runCatching { outputFile.delete() }
    }
}
