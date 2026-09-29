package me.rerere.rikkahub.data.workflow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Damonlsy fork：定时发消息工作流的配置。
 *
 * 用独立的 SharedPreferences 存储，不碰主设置数据（红线：绝不覆盖用户已配置的数据）。
 */
data class WorkflowConfig(
    /** 总开关 */
    val enabled: Boolean = false,
    /** 触发间隔（分钟），用户可调 */
    val intervalMinutes: Int = 30,
    /** 白名单外应用使用超过多少分钟，AI 才允许考虑锁应用 */
    val thresholdMinutes: Int = 60,
    /** true = AI 自行决定这一轮发不发消息（不发就跳过） */
    val aiDecidesSend: Boolean = true,
)

class WorkflowStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _config = MutableStateFlow(read())
    val config: StateFlow<WorkflowConfig> = _config.asStateFlow()

    private fun read(): WorkflowConfig = WorkflowConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        intervalMinutes = prefs.getInt(KEY_INTERVAL, 30).coerceIn(MIN_INTERVAL, MAX_INTERVAL),
        thresholdMinutes = prefs.getInt(KEY_THRESHOLD, 60).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
        aiDecidesSend = prefs.getBoolean(KEY_AI_DECIDES_SEND, true),
    )

    fun update(config: WorkflowConfig) {
        val normalized = config.copy(
            intervalMinutes = config.intervalMinutes.coerceIn(MIN_INTERVAL, MAX_INTERVAL),
            thresholdMinutes = config.thresholdMinutes.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
        )
        prefs.edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putInt(KEY_INTERVAL, normalized.intervalMinutes)
            .putInt(KEY_THRESHOLD, normalized.thresholdMinutes)
            .putBoolean(KEY_AI_DECIDES_SEND, normalized.aiDecidesSend)
            .apply()
        _config.value = normalized
    }

    fun update(fn: (WorkflowConfig) -> WorkflowConfig) = update(fn(_config.value))

    companion object {
        const val PREFS_NAME = "workflow"
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 24 * 60
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 24 * 60

        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_THRESHOLD = "threshold_minutes"
        private const val KEY_AI_DECIDES_SEND = "ai_decides_send"
    }
}
