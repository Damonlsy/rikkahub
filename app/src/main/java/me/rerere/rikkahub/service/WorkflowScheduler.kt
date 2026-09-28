package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.workflow.WorkflowStore

/**
 * Damonlsy fork：观察工作流开关，自动拉起/停止常驻前台服务（前台服务内部用计时器触发）。
 *
 * 作为 Koin 单例在进程启动时创建（createdAtStart），所以 App 一启动、或开机
 * 被 [BootReceiver] 拉起进程时，只要开关是开的就会恢复定时。
 */
class WorkflowScheduler(
    private val context: Context,
    workflowStore: WorkflowStore,
    scope: AppScope,
) {
    init {
        scope.launch {
            workflowStore.config
                .map { it.enabled to it.intervalMinutes }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    if (enabled) {
                        // start() 会重启内部计时循环，所以改间隔能立刻生效
                        WorkflowForegroundService.start(context)
                    } else {
                        WorkflowForegroundService.stop(context)
                    }
                }
        }
    }
}
