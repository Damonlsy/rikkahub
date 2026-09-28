package me.rerere.rikkahub.ui.pages.setting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import me.rerere.rikkahub.service.AppLockAccessibilityService

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
