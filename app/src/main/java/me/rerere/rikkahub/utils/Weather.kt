package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

private const val TAG = "Weather"
private const val REQUEST_URL =
    "https://api.open-meteo.com/v1/forecast" +
        "?current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
        "&wind_speed_unit=ms&timezone=auto"

/** 天气查询结果缓存，10 分钟内不重复请求接口 */
private const val WEATHER_CACHE_TTL_MS = 10L * 60 * 1000
private const val LOCATION_CACHE_TTL_MS = 10L * 60 * 1000

private data class WeatherCache(
    val latitude: Double,
    val longitude: Double,
    val text: String,
    val fetchedAt: Long,
)

private data class LocationCache(
    val location: Location,
    val fetchedAt: Long,
)

@Volatile
private var weatherCache: WeatherCache? = null

@Volatile
private var locationCache: LocationCache? = null

/**
 * Damonlsy fork：拿当前天气，返回一行给模型看的中文描述，拿不到返回 null。
 *
 * 定位走系统 LocationManager（需要用户授予定位权限），天气走免费的 Open-Meteo，
 * 不需要 key。结果按坐标缓存 10 分钟，定位同样缓存 10 分钟。
 */
suspend fun fetchWeatherLine(context: Context): String? = withContext(Dispatchers.IO) {
    if (!context.hasLocationPermission()) return@withContext null

    val location = cachedLocation(context) ?: return@withContext null
    val lat = location.latitude
    val lon = location.longitude

    val cached = weatherCache
    if (cached != null &&
        System.currentTimeMillis() - cached.fetchedAt < WEATHER_CACHE_TTL_MS &&
        kotlin.math.abs(cached.latitude - lat) < 0.05 &&
        kotlin.math.abs(cached.longitude - lon) < 0.05
    ) {
        return@withContext cached.text
    }

    val text = withTimeoutOrNull(8_000) { fetchFromOpenMeteo(lat, lon) } ?: return@withContext null
    weatherCache = WeatherCache(lat, lon, text, System.currentTimeMillis())
    text
}

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@SuppressLint("MissingPermission")
private suspend fun cachedLocation(context: Context): Location? {
    val cached = locationCache
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < LOCATION_CACHE_TTL_MS) {
        return cached.location
    }
    val location = systemLocation(context) ?: return null
    locationCache = LocationCache(location, System.currentTimeMillis())
    return location
}

@SuppressLint("MissingPermission")
private suspend fun systemLocation(context: Context): Location? {
    if (!context.hasLocationPermission()) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

    val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    var best: Location? = null
    providers.forEach { provider ->
        val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        if (location != null && (best == null || location.time > (best?.time ?: 0L))) {
            best = location
        }
    }
    best?.let { return it }

    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { continuation ->
            var settled = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (settled) return
                    settled = true
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                @Deprecated("Deprecated in Android 13")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            val requested = providers.firstOrNull { provider ->
                runCatching {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    true
                }.getOrDefault(false)
            }
            if (requested == null && continuation.isActive) {
                continuation.resume(null)
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }
}

private suspend fun fetchFromOpenMeteo(latitude: Double, longitude: Double): String? {
    val url = URL("$REQUEST_URL&latitude=$latitude&longitude=$longitude")
    val connection = url.openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.requestMethod = "GET"
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        parseWeatherBody(body, latitude, longitude)
    } finally {
        connection.disconnect()
    }
}

private fun parseWeatherBody(body: String, latitude: Double, longitude: Double): String? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val current = json.optJSONObject("current") ?: return null
    val temperature = current.optDouble("temperature_2m", Double.NaN)
    val apparent = current.optDouble("apparent_temperature", Double.NaN)
    val humidity = current.optDouble("relative_humidity_2m", Double.NaN)
    val wind = current.optDouble("wind_speed_10m", Double.NaN)
    val code = current.optInt("weather_code", -1)
    if (temperature.isNaN()) return null

    val parts = buildList {
        add(describeWeatherCode(code))
        add("气温 ${roundOne(temperature)}℃")
        if (!apparent.isNaN()) add("体感 ${roundOne(apparent)}℃")
        if (!humidity.isNaN()) add("湿度 ${humidity.toInt()}%")
        if (!wind.isNaN()) add("风 ${roundOne(wind)} m/s")
        add("坐标 ${roundOne(latitude)},${roundOne(longitude)}")
    }
    return parts.joinToString("，")
}

private fun roundOne(value: Double) = "%.1f".format(value)

/** WMO 天气代码 → 中文描述 */
private fun describeWeatherCode(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "大部晴朗"
    2 -> "多云"
    3 -> "阴"
    45 -> "雾"
    48 -> "雾凇"
    51 -> "小毛毛雨"
    53 -> "毛毛雨"
    55 -> "大毛毛雨"
    56 -> "冻毛毛雨"
    57 -> "强冻毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66 -> "冻雨"
    67 -> "强冻雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "雪粒"
    80 -> "阵雨"
    81 -> "强阵雨"
    82 -> "暴阵雨"
    85 -> "阵雪"
    86 -> "强阵雪"
    95 -> "雷阵雨"
    96 -> "雷阵雨伴冰雹"
    99 -> "强雷暴伴冰雹"
    else -> "未知天气"
}
