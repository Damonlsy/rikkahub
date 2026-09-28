package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DeviceContextSetting
import me.rerere.rikkahub.data.usage.queryRecentApps
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Damonlsy fork：把设备状态拼成一段 <device_context>。
 *
 * 这段内容会追加到系统提示词里，模型不用调工具就能看到；
 * 因为只存在于系统提示词，聊天界面不会显示它。
 */
suspend fun buildDeviceContextBlock(
    context: Context,
    setting: DeviceContextSetting,
): String? = withContext(Dispatchers.IO) {
    if (!setting.enabled) return@withContext null

    val lines = buildList {
        if (setting.includeTime) add(timeLine())
        if (setting.includeBattery) batteryLine(context)?.let { add(it) }
        if (setting.includeWeather) fetchWeatherLine(context)?.let { add(it) }
        if (setting.includeRecentApps) recentAppsLine(context, setting)?.let { add(it) }
    }
    if (lines.isEmpty()) return@withContext null

    buildString {
        appendLine("<device_context>")
        lines.forEach { appendLine("  $it") }
        append("</device_context>")
    }
}

private fun timeLine(): String {
    val now = ZonedDateTime.now()
    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.CHINA).format(now)
    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.CHINA).format(now)
    val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA).format(now)
    val zone = now.zone.id
    val offset = now.offset.id.let { if (it == "Z") "UTC" else it }
    return "当前时间：$date $time，$weekday，时区 $zone（$offset）"
}

private fun batteryLine(context: Context): String? {
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return null

    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level <= 0) return null

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val charger = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
        else -> "未知"
    }
    val state = when {
        status == BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        charging -> "充电中（$charger）"
        else -> "未充电"
    }
    return "手机电量：$level%，$state"
}

private fun recentAppsLine(context: Context, setting: DeviceContextSetting): String? {
    if (!context.hasUsageStatsPermission()) return null
    val windowMinutes = setting.recentAppsWindowMinutes.coerceIn(1, 24 * 60)
    val recent = queryRecentApps(
        context = context,
        windowMillis = windowMinutes * 60_000L,
        limit = setting.recentAppsLimit.coerceIn(1, 30),
    )
    if (recent.isEmpty()) return null

    val now = System.currentTimeMillis()
    val names = recent.joinToString("、") { item ->
        val minutes = (now - item.lastAtMillis) / 60_000L
        if (minutes < 1) "${item.appName}（刚刚）" else "${item.appName}（${minutes} 分钟前）"
    }
    return "最近 $windowMinutes 分钟内切过的应用（从最近到最早）：$names"
}
