package io.github.madeye.meow.ui.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Looks for a QR code in each camera frame and hands every one it reads to
 * [onText], on the analysis thread. Only the Y plane is read: luminance is
 * all ZXing needs, and a QR code reads the same at any rotation, so the
 * frame is used as the sensor delivers it.
 */
class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    /** Analysis runs on one thread, so one reader serves every frame. */
    private val reader = QrFrameReader()

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            reader.read(plane.buffer, plane.rowStride, it.width, it.height)?.let(onText)
        }
    }
}
