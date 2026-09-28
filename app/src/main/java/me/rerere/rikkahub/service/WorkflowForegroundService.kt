package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKFLOW_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.workflow.WorkflowStore
import org.koin.android.ext.android.inject

private const val TAG = "WorkflowFgs"

/**
 * Damonlsy fork：定时查岗的常驻前台服务。
 *
 * - 常驻通知 + 保持进程前台。
 * - 用协程计时器按用户设定的间隔触发（间隔可调）。
 * - 同时保留 ACTION_RUN_NOW，供闹钟兜底/手动触发。
 */
class WorkflowForegroundService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.WORKFLOW_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.WORKFLOW_STOP"
        private const val ACTION_RUN_NOW = "me.rerere.rikkahub.action.WORKFLOW_RUN_NOW"

        const val NOTIFICATION_ID = 2003

        fun start(context: Context) = startService(context, ACTION_START)
        fun triggerNow(context: Context) = startService(context, ACTION_RUN_NOW)

        fun stop(context: Context) {
            val intent = Intent(context, WorkflowForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.e(TAG, "Unable to stop workflow foreground service", it) }
        }

        private fun startService(context: Context, action: String) {
            val intent = Intent(context, WorkflowForegroundService::class.java).apply {
                this.action = action
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "Unable to start workflow foreground service ($action)", it)
            }
        }
    }

    private val scope: AppScope by inject()
    private val workflowService: WorkflowService by inject()
    private val workflowStore: WorkflowStore by inject()

    private var runJob: Job? = null
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                loopJob?.cancel()
                loopJob = null
                runJob?.cancel()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RUN_NOW -> {
                startForegroundInternal()
                runWorkflowOnce()
                return START_STICKY
            }

            else -> {
                startForegroundInternal()
                startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        loopJob?.cancel()
        loopJob = null
        runJob?.cancel()
        runJob = null
        super.onDestroy()
    }

    private fun startLoop() {
        // 间隔/开关变化时会重新进来：取消旧计时，按新间隔重新开始
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "loop started (interval=${workflowStore.config.value.intervalMinutes}min)")
            while (isActive) {
                val config = workflowStore.config.value
                if (!config.enabled) {
                    Log.i(TAG, "loop stop (disabled)")
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "loop sleeping ${config.intervalMinutes}min")
                delay(config.intervalMinutes * 60_000L)
                Log.i(TAG, "loop wake")
                if (!workflowStore.config.value.enabled) {
                    stopSelf()
                    return@launch
                }
                runCatching { doRun() }.onFailure { Log.e(TAG, "run failed", it) }
            }
        }
    }

    private fun runWorkflowOnce() {
        if (runJob?.isActive == true) return
        runJob = scope.launch(Dispatchers.IO) {
            runCatching { doRun() }.onFailure { Log.e(TAG, "run failed", it) }
        }
    }

    private suspend fun doRun() {
        val outcome = workflowService.runOnce()
        if (outcome == null) {
            Log.i(TAG, "runOnce returned null (skip)")
            return
        }
        Log.i(TAG, "notify + companion: ${outcome.message.take(40)}")
        WorkflowNotifier.notifyMessage(this, outcome)
        val shown = withContext(Dispatchers.Main) {
            AppLockAccessibilityService.showCompanion(
                name = outcome.assistantName,
                avatar = outcome.assistantAvatar,
                conversationId = outcome.conversationId,
            )
        }
        Log.i(TAG, "companion shown=$shown")
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, WORKFLOW_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("定时查岗已开启，到点会来查你在干嘛")
            .setContentIntent(contentPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "Failed to enter foreground", it)
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
