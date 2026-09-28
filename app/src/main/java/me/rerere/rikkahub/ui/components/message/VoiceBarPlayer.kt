package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 聊天里所有语音条共用一个播放器：同一时刻只播一条，切换消息自动停掉上一条。
 */
object VoiceBarPlayer {
    data class PlaybackState(
        val url: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun toggle(context: Context, url: String) {
        val current = _state.value
        if (current.url == url && player != null) {
            if (current.isPlaying) {
                runCatching { player?.pause() }
                progressJob?.cancel()
                _state.update { it.copy(isPlaying = false) }
            } else {
                runCatching { player?.start() }
                _state.update { it.copy(isPlaying = true) }
                observeProgress()
            }
            return
        }

        reset()
        val mediaPlayer = MediaPlayer()
        try {
            val parsed = Uri.parse(url)
            if (parsed.scheme == null) {
                mediaPlayer.setDataSource(parsed.path ?: url)
            } else {
                mediaPlayer.setDataSource(context.applicationContext, parsed)
            }
            mediaPlayer.setOnCompletionListener {
                progressJob?.cancel()
                _state.update { it.copy(isPlaying = false, positionMs = 0L) }
            }
            mediaPlayer.prepare()
            player = mediaPlayer
            _state.value = PlaybackState(
                url = url,
                isPlaying = true,
                positionMs = 0L,
                durationMs = mediaPlayer.duration.coerceAtLeast(0).toLong(),
            )
            mediaPlayer.start()
            observeProgress()
        } catch (t: Throwable) {
            runCatching { mediaPlayer.release() }
            player = null
            _state.value = PlaybackState()
        }
    }

    fun stop() {
        reset()
    }

    private fun reset() {
        progressJob?.cancel()
        progressJob = null
        player?.let { existing ->
            runCatching { existing.stop() }
            runCatching { existing.release() }
        }
        player = null
        _state.value = PlaybackState()
    }

    private fun observeProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val current = player ?: break
                val position = runCatching { current.currentPosition.toLong() }.getOrDefault(0L)
                _state.update { it.copy(positionMs = position) }
                delay(200)
            }
        }
    }
}
