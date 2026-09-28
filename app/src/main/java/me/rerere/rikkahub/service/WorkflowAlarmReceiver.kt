package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.data.workflow.WorkflowStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "WorkflowAlarmRx"

/**
 * Damonlsy fork：精确闹钟到点时触发一次查岗。
 *
 * 触发后立刻重排下一次闹钟，并拉起前台服务来执行（前台进程避免网络被系统掐断）。
 */
class WorkflowAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val workflowStore: WorkflowStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val config = workflowStore.config.value
        if (!config.enabled) {
            WorkflowAlarmScheduler.cancel(context)
            return
        }
        WorkflowAlarmScheduler.schedule(context, config.intervalMinutes)
        Log.i(TAG, "alarm fired, triggering workflow")
        WorkflowForegroundService.triggerNow(context)
    }
}
