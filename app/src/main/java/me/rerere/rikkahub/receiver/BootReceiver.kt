package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.data.workflow.WorkflowStore
import me.rerere.rikkahub.service.WorkflowForegroundService

/** Damonlsy fork：开机后如果定时工作流是开着的，恢复前台服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = WorkflowStore(context).config.value
        if (config.enabled) {
            WorkflowForegroundService.start(context)
        }
    }
}
