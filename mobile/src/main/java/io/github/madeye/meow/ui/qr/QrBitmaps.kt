package io.github.madeye.meow.ui.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picked images are scaled down to this on their long side before decoding:
 * plenty for a QR code that fills a fair part of a screenshot or photo,
 * and it keeps a 50-megapixel photo from costing hundreds of megabytes.
 */
private const val MAX_DECODE_SIDE = 2048

/** [scale] pixels per module, black on white. */
fun BitMatrix.toBitmap(scale: Int = 1): Bitmap {
    val w = width * scale
    val h = height * scale
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (get(x / scale, y / scale)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** The QR code in the image at [uri], or null when it has none or cannot be read. */
suspend fun Context.decodeQrImage(uri: Uri): String? {
    val bitmap = withContext(Dispatchers.IO) { runCatching { loadScaled(uri) }.getOrNull() }
        ?: return null
    return withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        QrCodes.decode(source, thorough = true)
    }
}

private fun Context.loadScaled(uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
