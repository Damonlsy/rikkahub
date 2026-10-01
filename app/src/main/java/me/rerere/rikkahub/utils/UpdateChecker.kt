package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request

    private val API_URLS = listOf(
        "https://api.github.com/repos/Damonlsy/rikkahub/releases/latest",
        // 部分网络下 api.github.com 不通/被限流，走镜像兜底
        "https://gh-proxy.com/https://api.github.com/repos/Damonlsy/rikkahub/releases/latest",
        "https://rikkahub-update.rikkahub.workers.dev/releases/latest",
    )

    // 下载镜像按顺序重试，最后一个为空串表示直连 GitHub
    private val DOWNLOAD_MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "",
    )

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val updateState: StateFlow<UiState<UpdateInfo>> = checkUpdate().stateIn(
        scope = appScope,
        started = SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        // 所有源并发请求，谁先成功用谁：单个源被墙/挂起时不再拖死整个检查
        val (winner, lastError) = coroutineScope {
            val channel = Channel<Result<UpdateInfo?>>(Channel.UNLIMITED)
            val jobs = API_URLS.map { apiUrl ->
                launch(Dispatchers.IO) {
                    channel.send(runCatching { fetchUpdateInfo(apiUrl) })
                }
            }
            var success: Result<UpdateInfo?>? = null
            var failure: Throwable? = null
            for (i in API_URLS.indices) {
                val result = channel.receive()
                if (result.isSuccess) {
                    success = result
                    break
                }
                failure = result.exceptionOrNull()
            }
            jobs.forEach { it.cancel() }
            success to failure
        }
        if (winner != null && winner.isSuccess) {
            val info = winner.getOrNull()
            if (info != null) {
                emit(UiState.Success(info))
            } else {
                emit(UiState.Idle)
            }
        } else {
            emit(UiState.Error(lastError ?: IllegalStateException("update check failed")))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 请求单个更新源：有新版本返回 UpdateInfo，已是最新返回 null，失败抛异常。
     */
    private suspend fun fetchUpdateInfo(apiUrl: String): UpdateInfo? {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw IllegalStateException("empty body")
        val release = json.parseToJsonElement(body).jsonObject
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("missing tag_name")
        val version = tag.removePrefix("v")
        val publishedAt = release["published_at"]?.jsonPrimitive?.contentOrNull ?: ""
        val changelog = release["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val assets = release["assets"]?.jsonArray ?: throw IllegalStateException("missing assets")
        val downloads = assets.mapNotNull { asset ->
            val obj = asset.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawUrl = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.contentOrNull ?: "0"
            UpdateDownload(
                name = name,
                url = rawUrl,
                size = size,
            )
        }
        val currentVersion = BuildConfig.VERSION_NAME
        val hasUpdate = Version.compare(currentVersion, version) < 0
        return if (hasUpdate) {
            UpdateInfo(
                version = version,
                publishedAt = publishedAt,
                changelog = changelog,
                downloads = downloads,
            )
        } else {
            null
        }
    }

    // 记录每个 DownloadManager 任务的原始地址与当前镜像序号，失败时换下一个镜像重试
    private data class PendingDownload(val rawUrl: String, val mirrorIndex: Int)

    private val pendingDownloads = mutableMapOf<Long, PendingDownload>()
    private var downloadReceiverRegistered = false
    private val downloadStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val pending = pendingDownloads.remove(downloadId) ?: return
            val failed = runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_FAILED
                } == true
            }.getOrDefault(false)
            if (!failed) return
            val next = pending.mirrorIndex + 1
            if (next < mirrorCount(pending.rawUrl)) {
                enqueueDownload(context.applicationContext, pending.rawUrl, next)
            } else {
                val appContext = context.applicationContext
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.update_download_failed),
                    Toast.LENGTH_LONG,
                ).show()
                appContext.openUrl(pending.rawUrl)
            }
        }
    }

    private fun buildDownloadUrl(rawUrl: String, mirrorIndex: Int): String {
        if (!rawUrl.startsWith("https://github.com/")) return rawUrl
        return DOWNLOAD_MIRRORS[mirrorIndex] + rawUrl
    }

    private fun mirrorCount(rawUrl: String): Int =
        if (rawUrl.startsWith("https://github.com/")) DOWNLOAD_MIRRORS.size else 1

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        val appContext = context.applicationContext
        if (!downloadReceiverRegistered) {
            downloadReceiverRegistered = true
            ContextCompat.registerReceiver(
                appContext,
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // 防连点：已经在下载、或正在选择镜像时，忽略本次点击，不再创建重复任务
        val alreadyPending = pendingDownloads.values.any { it.rawUrl == download.url }
        if (alreadyPending || !downloadStarting.compareAndSet(false, true)) {
            Toast.makeText(appContext, appContext.getString(R.string.update_card_downloading), Toast.LENGTH_SHORT).show()
            return
        }
        appScope.launch {
            try {
                // 先探测各镜像活性，选一个能响应的，避免点完下载一直不动
                val total = mirrorCount(download.url)
                var chosen = -1
                for (i in 0 until total) {
                    val alive = withTimeoutOrNull(3_000L) {
                        probe(buildDownloadUrl(download.url, i))
                    }
                    if (alive == true) {
                        chosen = i
                        break
                    }
                }
                enqueueDownload(appContext, download.url, if (chosen >= 0) chosen else 0)
            } finally {
                downloadStarting.set(false)
            }
        }
    }

    /** HEAD 探测镜像是否响应（3 秒超时由调用方控制） */
    private suspend fun probe(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).await().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun enqueueDownload(context: Context, rawUrl: String, mirrorIndex: Int) {
        runCatching {
            val fileName = rawUrl.substringAfterLast('/')
            val request = DownloadManager.Request(buildDownloadUrl(rawUrl, mirrorIndex).toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(fileName)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 允许在移动网络和WiFi下下载
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                // 设置文件保存路径
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            if (id != -1L) {
                pendingDownloads[id] = PendingDownload(rawUrl, mirrorIndex)
            }
        }.onFailure {
            // 当前镜像入队失败，立刻换下一个镜像；全部失败则打开浏览器兜底
            val next = mirrorIndex + 1
            if (next < mirrorCount(rawUrl)) {
                enqueueDownload(context, rawUrl, next)
            } else {
                Toast.makeText(context, context.getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
                context.openUrl(rawUrl)
            }
        }
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
