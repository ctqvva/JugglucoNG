package tk.glucodata

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorQrFrameDecoderTests {
    @Test
    fun decodesVersionTwelveQrFromPaddedCameraLuma() {
        val payload = "m".repeat(253)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val encoded = Encoder.encode(payload, ErrorCorrectionLevel.M, hints)
        assertEquals(12, encoded.version.versionNumber)

        val width = 390
        val height = 390
        val rowStride = 416
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, height, hints)
        val luma = ByteArray(rowStride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (qr[x, y]) luma[y * rowStride + x] = 0
            }
        }

        assertEquals(
            payload,
            MirrorQrFrameDecoder.decodeLuma(luma, width, height, rowStride, 1)
        )
        assertTrue(qr.width / encoded.matrix.width >= 5)
    }

    @Test
    fun decodesVersionTwelveQrFromInterleavedCameraLuma() {
        val payload = "m".repeat(253)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val width = 390
        val height = 390
        val pixelStride = 2
        val rowStride = 800
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, height, hints)
        val luma = ByteArray(rowStride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (qr[x, y]) luma[y * rowStride + x * pixelStride] = 0
            }
        }

        assertEquals(
            payload,
            MirrorQrFrameDecoder.decodeLuma(luma, width, height, rowStride, pixelStride)
        )
    }

    @Test
    fun invalidLumaDoesNotProduceScanText() {
        assertEquals(
            null,
            MirrorQrFrameDecoder.decodeLuma(ByteArray(16), 8, 8, 8, 1)
        )
        assertEquals(
            null,
            MirrorQrFrameDecoder.decodeLuma(ByteArray(63), 8, 8, 8, 1)
        )
    }
}
