package me.rerere.rikkahub.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** 单个应用在统计区间内的前台使用时长。 */
data class AppUsage(
    val packageName: String,
    val appName: String,
    val minutes: Long,
)

/**
 * Damonlsy fork：查询某段时间内各应用的前台使用时长排行榜。
 *
 * 复用与 [me.rerere.rikkahub.data.ai.tools.local.ScreenTimeTool] 相同的"全局单一前台"口径，
 * 结果与系统「屏幕使用时间」基本一致。需要用户授予「使用情况访问」特殊权限，
 * 未授权时返回空列表（调用方自行处理）。
 */
fun queryAppUsage(
    context: Context,
    startMs: Long,
    endMs: Long,
    top: Int = 20,
): List<AppUsage> {
    if (startMs >= endMs) return emptyList()
    val usageStatsManager = runCatching {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }.getOrNull() ?: return emptyList()
    val pm = context.packageManager
    val excluded = resolveLauncherPackages(pm)
    val foregroundMs = runCatching {
        computeForegroundTime(usageStatsManager, startMs, endMs, excluded)
    }.getOrDefault(emptyMap())
    return foregroundMs.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .take(top)
        .map { entry ->
            AppUsage(
                packageName = entry.key,
                appName = resolveAppName(pm, entry.key),
                minutes = entry.value / 60000,
            )
        }
}

// 向前回看的窗口(12h)，用于还原区间开始时刻已在前台的 App。
private const val LOOKBACK_MS = 12L * 60 * 60 * 1000

@Suppress(
    "DEPRECATION",
    "NewApi",
)
private fun computeForegroundTime(
    usageStatsManager: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
): Map<String, Long> {
    val foregroundMs = HashMap<String, Long>()
    val events = usageStatsManager.queryEvents(startMs - LOOKBACK_MS, endMs)
    val event = UsageEvents.Event()

    var currentPkg: String? = null
    var currentStart = 0L

    fun settle(until: Long) {
        val pkg = currentPkg
        currentPkg = null
        if (pkg == null || pkg in excludedPackages) return
        val from = maxOf(currentStart, startMs)
        val duration = until - from
        if (duration > 0) {
            foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
        }
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (event.packageName != currentPkg) {
                    settle(event.timeStamp)
                    currentPkg = event.packageName
                    currentStart = event.timeStamp
                }
            }

            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                if (event.packageName == currentPkg) {
                    settle(event.timeStamp)
                }
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                settle(event.timeStamp)
            }
        }
    }
    settle(endMs)
    return foregroundMs
}

private fun resolveLauncherPackages(pm: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/** 最近切到前台的一个应用，[lastAtMillis] 是窗口内最后一次切进去的时间。 */
data class RecentApp(
    val packageName: String,
    val appName: String,
    val lastAtMillis: Long,
)

/**
 * Damonlsy fork：查「最近切过的应用」列表（按最近到最早），给设备上下文注入用。
 *
 * 与 [queryAppUsage] 同样需要「使用情况访问」权限，未授权时返回空列表。
 * 桌面和本应用自身不会出现在列表里。
 *
 * @param windowMillis 往回看多久
 * @param limit 最多返回几个
 */
fun queryRecentApps(
    context: Context,
    windowMillis: Long,
    limit: Int = 8,
): List<RecentApp> {
    if (windowMillis <= 0 || limit <= 0) return emptyList()
    val usageStatsManager = runCatching {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }.getOrNull() ?: return emptyList()
    val pm = context.packageManager
    val excluded = resolveLauncherPackages(pm) + context.packageName
    val now = System.currentTimeMillis()
    val events = runCatching { usageStatsManager.queryEvents(now - windowMillis, now) }
        .getOrNull() ?: return emptyList()

    val lastSeen = LinkedHashMap<String, Long>()
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        if (!isForeground) continue
        val pkg = event.packageName ?: continue
        if (pkg in excluded) continue
        lastSeen[pkg] = event.timeStamp
    }

    return lastSeen.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { entry ->
            RecentApp(
                packageName = entry.key,
                appName = resolveAppName(pm, entry.key),
                lastAtMillis = entry.value,
            )
        }
}
