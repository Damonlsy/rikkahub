package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PhoneOff01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import java.util.Locale

/**
 * 拨出语音通话的全屏界面：头像、状态、计时、实时字幕、挂断。
 * 挂断时把通话时长（秒）交给 [onHangUp]，由上层决定是否写通话记录。
 */
@Composable
internal fun CallOverlay(
    visible: Boolean,
    assistant: Assistant?,
    state: VoiceSessionState,
    onStart: () -> Unit,
    onHangUp: (Long) -> Unit,
) {
    if (!visible) return

    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    var durationMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.isActive) {
        if (state.isActive) {
            val base = System.currentTimeMillis() - durationMs
            while (true) {
                durationMs = System.currentTimeMillis() - base
                delay(250)
            }
        }
    }

    LaunchedEffect(Unit) {
        onStart()
    }

    BackHandler {
        onHangUp(durationMs / 1000)
    }

    val statusText = when (state.phase) {
        VoicePhase.Off -> stringResource(R.string.call_ui_preparing)
        VoicePhase.Connecting -> stringResource(R.string.call_ui_connecting)
        VoicePhase.Listening -> stringResource(R.string.call_ui_listening)
        VoicePhase.Transcribing -> stringResource(R.string.call_ui_transcribing)
        VoicePhase.Speaking -> stringResource(R.string.call_ui_speaking)
        VoicePhase.Error -> state.error ?: stringResource(R.string.call_ui_error)
    }
    val hangUpText = stringResource(R.string.call_ui_hang_up)
    val totalSeconds = durationMs / 1000
    val name = assistant?.name?.ifBlank { null } ?: "AI"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF141419), Color(0xFF23232C)),
                ),
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Text(
                    text = String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(16.dp))
                CallAvatar(assistant = assistant, name = name)
                Text(
                    text = name,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.transcript.takeIf { it.isNotBlank() }?.let { transcript ->
                    Text(
                        text = transcript,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.speakingText.takeIf { it.isNotBlank() }?.let { reply ->
                    Text(
                        text = reply,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.phase == VoicePhase.Error) {
                    Button(onClick = onStart) {
                        Text(stringResource(R.string.call_ui_retry))
                    }
                }
                Surface(
                    onClick = { onHangUp(durationMs / 1000) },
                    shape = CircleShape,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.PhoneOff01,
                            contentDescription = hangUpText,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Text(
                    text = hangUpText,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun CallAvatar(
    assistant: Assistant?,
    name: String,
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        when (val avatar = assistant?.avatar) {
            is Avatar.Image -> {
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            is Avatar.Emoji -> {
                Text(
                    text = avatar.content,
                    fontSize = 56.sp,
                )
            }

            else -> {
                Text(
                    text = name.take(1),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
