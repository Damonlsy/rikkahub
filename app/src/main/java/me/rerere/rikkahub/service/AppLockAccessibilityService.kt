package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.applock.AppLockStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.utils.circularBitmap
import me.rerere.rikkahub.utils.decodeAvatarBitmap
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 应用锁 + 屏幕内容读取 + 定时工作流的悬浮头像。
 *
 * - 应用锁：监听前台应用，命中锁定列表就弹出一个拦截浮层
 *   （灰色图标 + “已被 {助手名} 锁定” + 备注）。
 * - 屏幕内容：`canRetrieveWindowContent` 已开启，工作流触发时按需读取当前窗口文字。
 * - 悬浮头像：工作流触发时在屏幕右侧中央显示助手头像圆形按钮，钟摆式摆动，10 秒后消失。
 *
 * 需要在系统设置里手动开启无障碍服务（App 内「设置 → 应用锁」会跳过去）。
 */
class AppLockAccessibilityService : AccessibilityService() {
    private val TAG = "AppLockA11y"
    private var overlay: View? = null
    private var overlayPackage: String? = null

    private var companionView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hideCompanion() }
    private val settingsStore: SettingsStore by inject()

    private data class BlockColors(
        val scrim: Int,
        val card: Int,
        val title: Int,
        val secondary: Int,
        val button: Int,
        val onButton: Int,
    )

    /** 取 Damonlsy 当前主题配色（与 RikkahubTheme 同样的计算逻辑）。 */
    private fun blockColors(): BlockColors {
        val settings = settingsStore.settingsFlow.value
        val prefs = getSharedPreferences("rikkahub.preferences", MODE_PRIVATE)
        val dark = when (prefs.getString("colorMode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val scheme: ColorScheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
            (findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)).getColorScheme(dark)
        }
        return BlockColors(
            scrim = withAlpha(scheme.scrim.toArgb(), 0.35f),
            card = scheme.surfaceContainerHigh.toArgb(),
            title = scheme.onSurface.toArgb(),
            secondary = scheme.onSurfaceVariant.toArgb(),
            button = scheme.primary.toArgb(),
            onButton = scheme.onPrimary.toArgb(),
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val locked = AppLockStore(this).lockedPackages()
        if (pkg in locked) {
            if (overlay == null) {
                Log.i(TAG, "locked app foreground: $pkg -> block + home")
                showBlockOverlay(pkg)
                performGlobalAction(GLOBAL_ACTION_HOME)
                pauseMedia()
                killAppSoon(pkg)
            }
        } else if (overlay != null && hasLauncherActivity(pkg)) {
            // 只有用户真的打开了别的 App 才收起提示；系统 UI/桌面/输入法不收起
            Log.i(TAG, "other app foreground: $pkg -> hide overlay")
            hideOverlay()
        }
    }

    private fun hasLauncherActivity(pkg: String): Boolean = runCatching {
        packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    private fun killAppSoon(pkg: String) {
        handler.postDelayed({
            val killed = runCatching {
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            }.isSuccess
            Log.i(TAG, "killBackgroundProcesses($pkg) success=$killed")
        }, 500)
    }

    private fun pauseMedia() {
        runCatching {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
        }
    }

    private fun showBlockOverlay(pkg: String) {
        hideOverlay()
        val store = AppLockStore(this)
        val info = store.info(pkg)
        val pm = packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
        val grayIcon = appInfo?.let {
            runCatching {
                val d = pm.getApplicationIcon(it).constantState?.newDrawable()?.mutate()
                d?.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                d
            }.getOrNull()
        }
        val by = info?.by?.takeIf { it.isNotBlank() } ?: "AI"
        val note = info?.note.orEmpty()
        val density = resources.displayMetrics.density
        val colors = blockColors()

        // 半透明遮罩：能看见桌面（先退回桌面再显示，所以背后是桌面而不是被锁应用）
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(colors.scrim)
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (32 * density).toInt(),
                (40 * density).toInt(),
                (32 * density).toInt(),
                (32 * density).toInt(),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f * density
                setColor(colors.card)
                setStroke((1 * density).toInt(), withAlpha(colors.secondary, 0.25f))
            }
        }

        grayIcon?.let {
            val size = (72 * density).toInt()
            card.addView(
                ImageView(this).apply {
                    setImageDrawable(it)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                },
            )
        }
        card.addView(
            TextView(this).apply {
                text = label
                setTextColor(colors.secondary)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, 0)
            },
        )
        card.addView(
            TextView(this).apply {
                text = "已被 $by 锁定"
                setTextColor(colors.title)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, 0)
            },
        )
        if (note.isNotBlank()) {
            card.addView(
                TextView(this).apply {
                    text = note
                    setTextColor(colors.secondary)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, (14 * density).toInt(), 0, 0)
                },
            )
        }
        card.addView(
            Button(this).apply {
                text = "知道了"
                setTextColor(colors.onButton)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f * density
                    setColor(colors.button)
                }
                setPadding((56 * density).toInt(), (20 * density).toInt(), (56 * density).toInt(), (20 * density).toInt())
                setOnClickListener { hideOverlay() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (28 * density).toInt()
                }
            },
        )

        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                marginStart = (36 * density).toInt()
                marginEnd = (36 * density).toInt()
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(scrim, params)
            overlay = scrim
            overlayPackage = pkg
            Log.i(TAG, "block overlay shown for $pkg")
        }.onFailure {
            Log.e(TAG, "failed to show block overlay", it)
        }
    }

    private fun hideOverlay() {
        if (overlay != null) {
            Log.i(TAG, "hideOverlay (pkg=$overlayPackage)")
        }
        overlay?.let {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        overlay = null
        overlayPackage = null
    }

    // ---- 屏幕内容读取 ----

    private fun readActiveWindowText(): String? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || sb.length >= 4000) return
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) sb.append(text).append('\n')
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank() && desc != text) sb.append(desc).append('\n')
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }
        runCatching { walk(root, 0) }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun scanInteractiveNodes(): String? {
        val root = rootInActiveWindow ?: return null
        val lines = mutableListOf<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || visited++ >= 400 || lines.size >= 120) return
            if (!node.isPassword && (node.isClickable || node.isEditable || node.isCheckable || node.isScrollable)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.className?.toString()?.substringAfterLast('.')
                    ?: "element"
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isScrollable) add("scrollable")
                }.joinToString(",")
                lines += "${label.replace('\n', ' ').take(120)} | center=${bounds.centerX()},${bounds.centerY()} | $flags"
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun clickTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(text)
        return candidates.firstNotNullOfOrNull { candidate ->
            var node: AccessibilityNodeInfo? = candidate
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@firstNotNullOfOrNull true
                node = node.parent
            }
            null
        } == true
    }

    private fun setTextInternal(text: String, target: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = target?.takeIf { it.isNotBlank() }
            ?.let { root.findAccessibilityNodeInfosByText(it).firstOrNull { item -> item.isEditable } }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditableNode(root)
            ?: return false
        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            },
        )
    }

    private fun submitTextInternal(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditableNode(root)
            ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || node.isPassword) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) findEditableNode(node.getChild(i))?.let { return it }
        return null
    }

    private suspend fun gestureInternal(path: Path, durationMillis: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private suspend fun tapInternal(x: Float, y: Float, durationMillis: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gestureInternal(path, durationMillis)
    }

    private suspend fun swipeInternal(x1: Float, y1: Float, x2: Float, y2: Float, durationMillis: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gestureInternal(path, durationMillis)
    }

    private suspend fun screenshotInternal(): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = screenshot.hardwareBuffer
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    hardware.close()
                    val file = bitmap?.let {
                        File(cacheDir, "screen_${System.currentTimeMillis()}.jpg").also { output ->
                            FileOutputStream(output).use { stream -> it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, stream) }
                            it.recycle()
                        }
                    }
                    if (continuation.isActive) continuation.resume(file)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    // ---- 悬浮头像 ----

    private fun showCompanionInternal(name: String, avatar: Avatar, conversationId: String?): Boolean {
        hideCompanion()
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()

        val container = FrameLayout(this).apply {
            elevation = 12f * density
            setPadding(0, 0, 0, 0)
        }
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F2FFFFFF"))
            setStroke((2 * density).toInt(), Color.parseColor("#33000000"))
        }
        clipCircle(container)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            container.outlineAmbientShadowColor = Color.parseColor("#55FF8A65")
            container.outlineSpotShadowColor = Color.parseColor("#55FF8A65")
        }

        val content = when (avatar) {
            is Avatar.Image -> buildAvatarImage(avatar.url, name)
            is Avatar.Emoji -> TextView(this).apply {
                text = avatar.content
                textSize = 28f
                gravity = Gravity.CENTER
            }

            is Avatar.Dummy -> initialAvatar(name)
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(size, size, Gravity.CENTER),
        )

        container.setOnClickListener {
            swing(container)
            vibrate()
            conversationId?.let { openConversation(it) }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (10 * density).toInt()
        }

        return runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(container, params)
            companionView = container
            container.post { swing(container) }
            vibrate()
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, COMPANION_DURATION_MS)
            true
        }.getOrElse {
            companionView = null
            false
        }
    }

    private fun buildAvatarImage(url: String, name: String): View {
        val bitmap = decodeAvatarBitmap(this, url)
        if (bitmap != null) {
            return ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(circularBitmap(bitmap))
            }
        }
        return initialAvatar(name)
    }

    private fun initialAvatar(name: String): TextView = TextView(this).apply {
        text = name.trim().take(1).ifBlank { "A" }
        setTextColor(Color.WHITE)
        textSize = 26f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColors(intArrayOf(Color.parseColor("#FF8A65"), Color.parseColor("#4FC3F7")))
        }
    }

    private fun clipCircle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
    }

    private fun hideCompanion() {
        handler.removeCallbacks(dismissRunnable)
        companionView?.let {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        companionView = null
    }

    private fun swing(view: View) {
        ObjectAnimator.ofFloat(
            view,
            View.ROTATION,
            0f, -8f, 8f, -8f, 8f, -8f, 8f, 0f,
        ).apply {
            duration = 400L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun vibrate() {
        val vibrator = runCatching {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }.getOrNull() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, 40))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(18L)
            }
        }
    }

    private fun openConversation(conversationId: String) {
        runCatching {
            val intent = Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId)
            }
            startActivity(intent)
        }
        hideCompanion()
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
        hideOverlay()
    }

    override fun onDestroy() {
        hideOverlay()
        hideCompanion()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val COMPANION_DURATION_MS = 10_000L

        @Volatile
        private var instance: AppLockAccessibilityService? = null

        /** 读取当前屏幕窗口中的文字（需无障碍服务已连接且开启内容读取）。 */
        fun captureScreenText(): String? = instance?.readActiveWindowText()

        fun isConnected(): Boolean = instance != null

        fun foregroundPackage(): String? = instance?.rootInActiveWindow?.packageName?.toString()

        fun scanInteractiveUi(): String? = instance?.scanInteractiveNodes()

        fun clickText(text: String): Boolean = instance?.clickTextInternal(text) ?: false

        fun setText(text: String, target: String? = null): Boolean = instance?.setTextInternal(text, target) ?: false

        fun submitText(): Boolean = instance?.submitTextInternal() ?: false

        fun globalAction(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        suspend fun tap(x: Float, y: Float, durationMillis: Long = 60L): Boolean =
            instance?.tapInternal(x, y, durationMillis) ?: false

        suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMillis: Long): Boolean =
            instance?.swipeInternal(x1, y1, x2, y2, durationMillis) ?: false

        suspend fun screenshot(): File? = instance?.screenshotInternal()

        /**
         * 显示助手悬浮头像（右侧中央，钟摆摆动，10 秒后消失）。
         * 必须在主线程调用；无障碍服务未开启时返回 false。
         */
        fun showCompanion(
            name: String,
            avatar: Avatar,
            conversationId: String?,
        ): Boolean = instance?.showCompanionInternal(name, avatar, conversationId) ?: false
    }
}
