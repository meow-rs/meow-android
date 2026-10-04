package io.github.madeye.meow.ui.qr

import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodesTest {

    private val sub = "https://sub.example.com/api/v1/client/subscribe?token=0123456789abcdef0123456789abcdef&flag=meta"

    @Test
    fun `a shared subscription scans back to the same subscription`() {
        val link = InstallConfigLink.of(sub, "机场 A")
        val scanned = QrFrameReader().read(cameraFrame(QrCodes.encode(link)!!))
        assertEquals(link, scanned)
        assertEquals(InstallConfigLink.Valid(sub, "机场 A"), InstallConfigLink.parseScanned(scanned!!))
    }

    @Test
    fun `the code is square and carries a quiet zone`() {
        val code = QrCodes.encode(sub)!!
        assertEquals(code.width, code.height)
        for (i in 0 until code.width) {
            for (edge in 0 until 4) {
                assertEquals(false, code.get(i, edge))
                assertEquals(false, code.get(edge, i))
            }
        }
    }

    @Test
    fun `camera frames are read through a row stride wider than the image`() {
        val code = QrCodes.encode(sub)!!
        assertEquals(sub, QrFrameReader().read(cameraFrame(code, padding = 64)))
    }

    @Test
    fun `light-on-dark codes are read too`() {
        val code = QrCodes.encode(sub)!!
        // The camera alternates: the first frame is read as is, the next inverted.
        val reader = QrFrameReader()
        val frame = cameraFrame(code, inverted = true)
        assertNull(reader.read(frame))
        assertEquals(sub, reader.read(frame))
        assertEquals(sub, QrCodes.decode(image(code, inverted = true), thorough = true))
    }

    @Test
    fun `the camera alternation still reads every other frame of a normal code`() {
        val reader = QrFrameReader()
        val frame = cameraFrame(QrCodes.encode(sub)!!)
        assertEquals(listOf(sub, null, sub), List(3) { reader.read(frame) })
    }

    @Test
    fun `a picked image is read with the thorough pass`() {
        val code = QrCodes.encode(sub)!!
        assertEquals(sub, QrCodes.decode(image(code), thorough = true))
    }

    @Test
    fun `a blank frame has no code`() {
        val blank = ByteArray(320 * 240) { 0x80.toByte() }
        val source = PlanarYUVLuminanceSource(blank, 320, 240, 0, 0, 320, 240, false)
        assertNull(QrCodes.decode(source, thorough = true))
        val reader = QrFrameReader()
        repeat(2) { assertNull(reader.read(Frame(ByteBuffer.wrap(blank), 320, 320, 240))) }
    }

    @Test
    fun `text past the largest QR code does not encode`() {
        assertNull(QrCodes.encode("https://example.com/" + "a".repeat(3000)))
    }

    /**
     * [code] as a camera Y plane: [SCALE] pixels per module on a mid-grey
     * frame, each row [padding] bytes longer than the image and the last one
     * cut at the image width, as camera buffers often are.
     */
    private fun cameraFrame(code: BitMatrix, padding: Int = 0, inverted: Boolean = false): Frame {
        val side = code.width * SCALE + 2 * BORDER
        val stride = side + padding
        val luma = ByteArray(stride * (side - 1) + side) { 0x80.toByte() }
        for (y in 0 until code.height * SCALE) {
            for (x in 0 until code.width * SCALE) {
                val dark = code.get(x / SCALE, y / SCALE) != inverted
                luma[(y + BORDER) * stride + x + BORDER] = if (dark) 0x10 else 0xF0.toByte()
            }
        }
        return Frame(ByteBuffer.wrap(luma), stride, side, side)
    }

    private class Frame(val plane: ByteBuffer, val rowStride: Int, val width: Int, val height: Int)

    private fun QrFrameReader.read(frame: Frame): String? =
        read(frame.plane, frame.rowStride, frame.width, frame.height)

    /** [code] as ARGB pixels, the way a picked image reaches the decoder. */
    private fun image(code: BitMatrix, inverted: Boolean = false): LuminanceSource {
        val side = code.width * SCALE
        val pixels = IntArray(side * side) { i ->
            val dark = code.get(i % side / SCALE, i / side / SCALE) != inverted
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return RGBLuminanceSource(side, side, pixels)
    }

    private companion object {
        const val SCALE = 4
        const val BORDER = 24
    }
}
