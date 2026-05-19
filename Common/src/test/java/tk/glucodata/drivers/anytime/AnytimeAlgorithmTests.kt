package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeAlgorithmTests {

    @Test
    fun fromComputedRecordKeepsRawLinearSeparateFromNativeGlucose() {
        val qr = AnytimeQr.parse("a61061B")
        val result = AnytimeAlgorithm.fromComputedRecord(
            AnytimeComputedRecord(
                glucoseId = 42,
                hypoEarlyWarnMinutes = 0,
                hyperEarlyWarnMinutes = 0,
                ibNa = 0.5f,
                iwNa = 5.0f,
                temperatureC = 32.5f,
                gluMmol = 15.0f,
                referenceBgMmol = 0f,
                errorCode = 0,
                trend = 6,
                warnCode = 0,
            ),
            qr,
        )

        assertEquals(AnytimeAlgorithm.Source.NATIVE, result.source)
        assertEquals(270.0f, result.mgdl, 0.001f)
        assertEquals(95.58f, result.rawMgdl, 0.01f)
        assertNotEquals(result.mgdl, result.rawMgdl, 0.001f)
    }

    @Test
    fun fromComputedRecordRawLaneUsesUnclampedLinearValue() {
        val qr = AnytimeQr.parse("a61061B")
        val family = AnytimeConstants.resolveFamily("SN8760000835")
        val result = AnytimeAlgorithm.fromComputedRecord(
            AnytimeComputedRecord(
                glucoseId = 216,
                hypoEarlyWarnMinutes = 0,
                hyperEarlyWarnMinutes = 0,
                ibNa = 0.22f,
                iwNa = 2.16f,
                temperatureC = 32.8f,
                gluMmol = 3.5f,
                referenceBgMmol = 0f,
                errorCode = 0,
                trend = 0,
                warnCode = 0,
            ),
            qr,
            family,
        )

        assertEquals(63.0f, result.mgdl, 0.001f)
        assertEquals(20.8f, result.rawMgdl, 0.1f)
        assertNotEquals(result.mgdl, result.rawMgdl, 0.001f)
    }

    @Test
    fun ct4VoltageOneRawLinearNormalizesWorkingCurrentWithoutTouchingParsedFields() {
        val qr = AnytimeQr.parse("a61061B")!!
        val family = AnytimeConstants.resolveFamily("SN8760000835")
        val result = AnytimeAlgorithm.computeLinear(
            AnytimeRawRecord(
                indexInPacket = 0,
                glucoseId = 147,
                ibNa = 4.39f,
                iwNa = 13.74f,
                temperatureC = 33.10f,
                recordBytes = byteArrayOf(0x93.toByte(), 0x00, 0x01, 0xB7.toByte(), 0x05, 0x5E, 0x49, 0x0A, 0x04, 0x1B),
            ),
            qr.k,
            qr.r,
            family,
            qr.voltageFlag,
        )

        assertEquals(13.74f, result.iwNa, 0.001f)
        assertEquals(7.29f, result.mmol, 0.01f)
        assertEquals(131.3f, result.rawMgdl, 0.1f)
    }

    @Test
    fun rawLinearLaneIsNotClampedToAutoDisplayFloor() {
        val qr = AnytimeQr.parse("a61061B")!!
        val family = AnytimeConstants.resolveFamily("SN8760000835")
        val result = AnytimeAlgorithm.computeLinear(
            AnytimeRawRecord(
                indexInPacket = 0,
                glucoseId = 216,
                ibNa = 0.22f,
                iwNa = 2.16f,
                temperatureC = 32.8f,
                recordBytes = ByteArray(0),
            ),
            qr.k,
            qr.r,
            family,
            qr.voltageFlag,
        )

        assertEquals(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat(), result.mmol, 0.001f)
        assertEquals(20.8f, result.rawMgdl, 0.1f)
    }

    @Test
    fun referenceBgStaysAttachedAfterTargetGlucoseId() {
        assertFalse(AnytimeAlgorithm.shouldAttachReferenceBg(3724, 3725, 1080))
        assertTrue(AnytimeAlgorithm.shouldAttachReferenceBg(3725, 3725, 1080))
        assertTrue(AnytimeAlgorithm.shouldAttachReferenceBg(3727, 3725, 1080))
        assertFalse(AnytimeAlgorithm.shouldAttachReferenceBg(3727, 3725, 0))
    }
}
