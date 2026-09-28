package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "WorkflowAlarm"

/**
 * Damonlsy fork：用 AlarmManager 精确闹钟驱动定时查岗。
 *
 * 用 `setExactAndAllowWhileIdle`（**不带系统闹钟图标**）；设备休眠时也能唤醒。
 * 相比协程 delay，在应用被系统冻结时更可靠。每次触发后由
 * [WorkflowAlarmReceiver] 重新排下一次。
 */
object WorkflowAlarmScheduler {
    private const val REQUEST_CODE = 1001

    fun schedule(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intervalMs = intervalMinutes.coerceAtLeast(1) * 60_000L
        val triggerAt = System.currentTimeMillis() + intervalMs
        val pendingIntent = pendingIntent(context)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
            Log.i(TAG, "scheduled in ${intervalMinutes}min (exact=$canExact)")
        }.onFailure {
            Log.e(TAG, "schedule failed, fallback to inexact", it)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WorkflowAlarmReceiver::class.java).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
