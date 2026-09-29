package me.rerere.rikkahub.data.applock

import android.content.Context
import org.json.JSONObject

data class AppLockInfo(val note: String, val by: String)

/**
 * 应用锁的锁定列表 + 每个应用的锁定信息（备注、锁定者）。
 * 用独立的 SharedPreferences 存储，不碰主设置数据。
 */
class AppLockStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "app_lock",
        Context.MODE_PRIVATE,
    )

    fun lockedPackages(): Set<String> =
        prefs.getStringSet(KEY_LOCKED, emptySet())?.toSet() ?: emptySet()

    private fun infoMap(): MutableMap<String, AppLockInfo> {
        val raw = prefs.getString(KEY_INFO, null) ?: return mutableMapOf()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, AppLockInfo>()
        obj.keys().forEach { k ->
            val o = obj.optJSONObject(k) ?: return@forEach
            map[k] = AppLockInfo(o.optString("note"), o.optString("by"))
        }
        return map
    }

    private fun saveInfo(map: Map<String, AppLockInfo>) {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, JSONObject().put("note", v.note).put("by", v.by))
        }
        prefs.edit().putString(KEY_INFO, obj.toString()).apply()
    }

    fun info(packageName: String): AppLockInfo? = infoMap()[packageName]

    fun lock(packageName: String, note: String, by: String) {
        prefs.edit().putStringSet(KEY_LOCKED, lockedPackages() + packageName).apply()
        val map = infoMap()
        map[packageName] = AppLockInfo(note, by)
        saveInfo(map)
    }

    fun unlock(packageName: String) {
        prefs.edit().putStringSet(KEY_LOCKED, lockedPackages() - packageName).apply()
        val map = infoMap()
        map.remove(packageName)
        saveInfo(map)
    }

    /**
     * 受保护应用：永远不允许锁定（AI 和手动都锁不了），**由用户在「设置 → 应用锁」自定义**。
     * 首次访问时按 [DEFAULT_PROTECTED_LABELS] 播种（只匹配已安装的应用），之后完全听用户的。
     */
    fun protectedPackages(): Set<String> {
        ensureProtectedSeeded()
        return prefs.getStringSet(KEY_PROTECTED, emptySet())?.toSet() ?: emptySet()
    }

    fun isProtected(packageName: String): Boolean = packageName in protectedPackages()

    fun setProtected(packageName: String, protected: Boolean) {
        val current = protectedPackages()
        val next = if (protected) current + packageName else current - packageName
        prefs.edit().putStringSet(KEY_PROTECTED, next).apply()
    }

    private fun ensureProtectedSeeded() {
        if (prefs.getBoolean(KEY_PROTECTED_SEEDED, false)) return
        val pm = appContext.packageManager
        val installedByLabel = mutableMapOf<String, String>()
        for (app in pm.getInstalledApplications(0)) {
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
            installedByLabel.putIfAbsent(label, app.packageName)
        }
        val seed = DEFAULT_PROTECTED_LABELS.mapNotNull { installedByLabel[it] }
        prefs.edit()
            .putStringSet(KEY_PROTECTED, seed.toSet())
            .putBoolean(KEY_PROTECTED_SEEDED, true)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_LOCKED)
            .remove(KEY_INFO)
            .remove(KEY_PROTECTED)
            .remove(KEY_PROTECTED_SEEDED)
            .apply()
    }

    companion object {
        private const val KEY_LOCKED = "locked_packages"
        private const val KEY_INFO = "lock_info"
        private const val KEY_PROTECTED = "protected_packages"
        private const val KEY_PROTECTED_SEEDED = "protected_seeded"

        /** 默认受保护名单：首次播种用，只取手机里实际装了的，之后由用户增删。 */
        val DEFAULT_PROTECTED_LABELS = listOf(
            "微信",
            "学习通",
            "支付宝",
            "完美校园",
            "胖乖生活",
            "到梦空间",
            "百度网盘",
        )
    }
}
