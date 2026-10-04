package io.github.madeye.meow.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.ByteBuffer

/**
 * ZXing glue for the subscription QR codes. Plain JVM, so the encode/decode
 * round trip runs as a unit test; the Android side is in QrBitmaps.kt.
 */
object QrCodes {

    private val ENCODE_HINTS = mapOf(
        // M survives a smudged screen or a slightly cropped screenshot without
        // growing a URL-sized code past what a phone camera resolves easily.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        // The spec's quiet zone, so the code also scans as a bare shared image
        // on a dark chat background.
        EncodeHintType.MARGIN to 4,
    )

    private val THOROUGH_HINTS = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * [text] as a QR code, one cell per module, quiet zone included; null
     * when it is too long for any QR code.
     */
    fun encode(text: String): BitMatrix? = try {
        // 0×0 asks for the natural size instead of a scaled one.
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, ENCODE_HINTS)
    } catch (_: WriterException) {
        null
    }

    /**
     * The QR code in [source], or null. [thorough] is for a single picked
     * image, where it pays to look harder: a second binarizer, and the
     * inverted image too, since dark-themed panels draw light codes on dark
     * backgrounds, which ZXing does not read as-is. Camera frames keep coming,
     * so each gets one quick pass, and [QrFrameReader] alternates polarity.
     */
    fun decode(source: LuminanceSource, thorough: Boolean): String? {
        val reader = QRCodeReader()
        if (!thorough) return reader.tryDecode(HybridBinarizer(source), null)
        for (luminance in listOf(source, source.invert())) {
            for (binarizer in listOf(HybridBinarizer(luminance), GlobalHistogramBinarizer(luminance))) {
                reader.tryDecode(binarizer, THOROUGH_HINTS)?.let { return it }
            }
        }
        return null
    }

    private fun QRCodeReader.tryDecode(binarizer: Binarizer, hints: Map<DecodeHintType, *>?): String? = try {
        decode(BinaryBitmap(binarizer), hints).text
    } catch (_: ReaderException) {
        reset()
        null
    }
}

/**
 * Reads QR codes from a stream of camera luma (Y) planes, on one thread.
 *
 * Every frame is copied into one reused buffer, packed to the image width so
 * ZXing reads it as-is instead of copying it again, and every other frame is
 * inverted in place to catch light-on-dark codes. A 720p preview delivers
 * dozens of frames a second, so the copies ZXing would otherwise make (the
 * repacked frame, the inverted one) would be megabytes of garbage a second.
 */
class QrFrameReader {

    private var luma = ByteArray(0)
    private var invertNext = false

    /** The QR code in the [width]×[height] Y plane in [plane], rows [rowStride] bytes apart, or null. */
    fun read(plane: ByteBuffer, rowStride: Int, width: Int, height: Int): String? {
        if (luma.size != width * height) luma = ByteArray(width * height)
        // Row by row: the last row may stop at the width rather than the stride.
        for (y in 0 until height) {
            plane.position(y * rowStride)
            plane.get(luma, y * width, width)
        }
        if (invertNext) {
            for (i in luma.indices) luma[i] = luma[i].toInt().inv().toByte()
        }
        invertNext = !invertNext
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return QrCodes.decode(source, thorough = false)
    }
}
