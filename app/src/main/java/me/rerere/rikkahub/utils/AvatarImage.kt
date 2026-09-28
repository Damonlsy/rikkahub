package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.net.toUri
import kotlin.math.min

/**
 * Damonlsy fork：把助手的 [me.rerere.rikkahub.data.model.Avatar] 图片解码成位图，
 * 并裁成圆形，供悬浮头像和通知大图标复用。
 */
fun decodeAvatarBitmap(context: Context, url: String, maxSize: Int = 320): Bitmap? = runCatching {
    val uri = url.toUri()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when (uri.scheme?.lowercase()) {
        "file" -> bounds.let { uri.path?.let { p -> BitmapFactory.decodeFile(p, it) } }
        "content" -> context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        "http", "https" -> return@runCatching null
        else -> BitmapFactory.decodeFile(url, bounds)
    }
    val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { BitmapFactory.decodeFile(it, opts) }
        "content" -> context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }

        else -> BitmapFactory.decodeFile(url, opts)
    } ?: return@runCatching null
    raw
}.getOrNull()

private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= maxSize && h / 2 >= maxSize) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

/** 把位图裁成圆形（居中裁剪）。 */
fun circularBitmap(src: Bitmap): Bitmap {
    val size = min(src.width, src.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = Rect(0, 0, size, size)
    canvas.drawARGB(0, 0, 0, 0)
    paint.color = -0x1000000
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    val srcRect = Rect(
        (src.width - size) / 2,
        (src.height - size) / 2,
        (src.width + size) / 2,
        (src.height + size) / 2,
    )
    canvas.drawBitmap(src, srcRect, rect, paint)
    return output
}
