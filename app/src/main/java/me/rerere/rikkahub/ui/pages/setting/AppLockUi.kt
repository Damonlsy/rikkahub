package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import me.rerere.rikkahub.service.AppLockAccessibilityService

/**
 * 国产 ROM（小米/红米 HyperOS 等）在 QUERY_ALL_PACKAGES 之上额外管控的
 * 「读取应用列表」运行时权限：不授予的话 getInstalledApplications 只能拿到自己。
 */
internal const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
private const val REQUEST_APP_LIST_PERMISSION = 4401

internal fun hasAppListPermission(context: Context): Boolean = runCatching {
    context.checkSelfPermission(APP_LIST_PERMISSION) == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

/** 申请「读取应用列表」权限。未定义该权限的 ROM 上会静默失败，无副作用。 */
internal fun requestAppListPermission(context: Context) {
    var ctx: Context = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) {
            runCatching {
                ctx.requestPermissions(arrayOf(APP_LIST_PERMISSION), REQUEST_APP_LIST_PERMISSION)
            }
            return
        }
        ctx = ctx.baseContext
    }
}

internal fun isAppLockServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, AppLockAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any {
        it.equals(component.flattenToString(), ignoreCase = true) ||
            it.equals(component.flattenToShortString(), ignoreCase = true)
    }
}

internal fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
