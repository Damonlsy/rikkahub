package me.rerere.rikkahub.ui.components.message

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.utils.voiceAudioDurationMs
import java.util.Locale

/**
 * 聊天气泡里的语音条：播放/暂停 + 波形进度 + 时长，宽度随语音时长变化。
 */
@Composable
internal fun VoiceBarBubble(
    url: String,
    metadata: JsonObject?,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playback by VoiceBarPlayer.state.collectAsState()

    var knownDurationMs by remember(url) {
        mutableStateOf(voiceAudioDurationMs(metadata) ?: 0L)
    }

    LaunchedEffect(url) {
        if (knownDurationMs > 0L) return@LaunchedEffect
        val parsed = Uri.parse(url)
        val probed = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (parsed.scheme == null) {
                        retriever.setDataSource(parsed.path ?: url)
                    } else {
                        retriever.setDataSource(context, parsed)
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrDefault(0L)
        }
        if (probed > 0L) {
            knownDurationMs = probed
        }
    }

    // 播放中的消息离开屏幕时停掉播放，避免后台残留声音
    DisposableEffect(url) {
        onDispose {
            if (VoiceBarPlayer.state.value.url == url && VoiceBarPlayer.state.value.isPlaying) {
                VoiceBarPlayer.stop()
            }
        }
    }

    val isCurrent = playback.url == url
    val playing = isCurrent && playback.isPlaying
    val positionMs = if (isCurrent) playback.positionMs else 0L
    val durationMs = when {
        isCurrent && playback.durationMs > 0L -> playback.durationMs
        knownDurationMs > 0L -> knownDurationMs
        else -> 0L
    }
    val durationSeconds = durationMs / 1000
    val targetWidth = (72L + durationSeconds * 3).coerceIn(72L, 240L).toInt()

    Surface(
        onClick = { VoiceBarPlayer.toggle(context, url) },
        modifier = modifier
            .width(targetWidth.dp)
            .height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (playing) HugeIcons.Pause else HugeIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            VoiceProgressBars(
                playing = playing,
                progress = if (durationMs > 0L) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
            )
            Box(modifier = Modifier.weight(1f))
            if (durationMs > 0L) {
                Text(
                    text = String.format(Locale.ROOT, "%d\"%02d", durationSeconds / 60, durationSeconds % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun VoiceProgressBars(
    playing: Boolean,
    progress: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.widthIn(min = 32.dp),
    ) {
        repeat(7) { index ->
            val height = when {
                index % 3 == 1 -> 8.dp
                index % 3 == 2 -> 12.dp
                else -> 15.dp
            }
            val active = playing && progress >= (index + 1) / 7f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        LocalContentColor.current.copy(alpha = if (active) 0.95f else 0.35f),
                    ),
            )
        }
    }
}
