package me.rerere.rikkahub.data.applock

import android.content.Context
import org.json.JSONObject

data class AppLockInfo(val note: String, val by: String)

/**
 * 应用锁的锁定列表 + 每个应用的锁定信息（备注、锁定者）。
 * 用独立的 SharedPreferences 存储，不碰主设置数据。
 */
class AppLockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
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

    fun clear() {
        prefs.edit().remove(KEY_LOCKED).remove(KEY_INFO).apply()
    }

    companion object {
        private const val KEY_LOCKED = "locked_packages"
        private const val KEY_INFO = "lock_info"
    }
}
