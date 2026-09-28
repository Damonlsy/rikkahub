/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 安全审计日志条目
 *
 * 记录涉及隐私、权限、高风险操作的关键记录。
 *
 * 注意：这里刻意不使用 Room 实体 —— 本分支不在 AppDatabase 上新增表，
 * 改用 SharedPreferences 存 JSON，避免动到数据库版本与迁移链。
 */
@Serializable
data class SecurityAuditEntity(
    /** 主键（自增语义） */
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** 事件类别：plugin / workflow / tool / system */
    val category: String,
    /** 动作：installed / integrity_failed / blocked / approved / denied / triggered 等 */
    val action: String,
    /** 目标标识（插件ID、工作流ID等） */
    val target: String = "",
    /** 详情 */
    val detail: String = "",
    /** 状态：success / failure / blocked */
    val status: String = "",
)

/**
 * 安全审计日志仓库
 *
 * 上游实现基于 Room（新增 security_audit_logs 表），本分支改为 SharedPreferences，
 * 对外 API 保持一致，UI 层可无差别消费 [recentLogs]。
 */
class SecurityAuditRepository(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SecurityAuditRepository"
        private const val PREFS_NAME = "security_audit"
        private const val KEY_LOGS = "logs"
        private const val MAX_LOGS = 500
        private const val RETAIN_DAYS = 30L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    private val _recentLogs = MutableStateFlow(readLogs())
    val recentLogs: Flow<List<SecurityAuditEntity>> = _recentLogs

    suspend fun log(
        category: String,
        action: String,
        target: String = "",
        detail: String = "",
        status: String = "",
    ) = withContext(Dispatchers.IO) {
        val current = _recentLogs.value.toMutableList()
        current.add(
            SecurityAuditEntity(
                id = nextId.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                category = category,
                action = action,
                target = target,
                detail = detail,
                status = status,
            )
        )

        // 自动清理：只保留最近 30 天或 500 条
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24 * 60 * 60 * 1000L
        val pruned = current
            .filter { it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
            .take(MAX_LOGS)

        writeLogs(pruned)
        _recentLogs.value = pruned
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writeLogs(emptyList())
        _recentLogs.value = emptyList()
    }

    suspend fun getAll(): List<SecurityAuditEntity> = withContext(Dispatchers.IO) {
        _recentLogs.value
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        _recentLogs.value.size
    }

    private fun readLogs(): List<SecurityAuditEntity> {
        return try {
            val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
            json.decodeFromString<List<SecurityAuditEntity>>(raw)
                .sortedByDescending { it.timestamp }
                .also { list ->
                    list.maxOfOrNull { it.id }?.let { max -> nextId.set(max) }
                }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to read audit logs", e)
            emptyList()
        }
    }

    private fun writeLogs(logs: List<SecurityAuditEntity>) {
        prefs.edit().putString(KEY_LOGS, json.encodeToString(logs)).apply()
    }
}
