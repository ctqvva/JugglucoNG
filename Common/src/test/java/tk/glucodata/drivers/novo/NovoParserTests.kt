package tk.glucodata.drivers.novo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovoParserTests {
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun txFramesMatchVendorBuilders() {
        assertEquals("7a414241424331323334", NovoParser.setupPassword(0x41, 0x42).hex())
        assertEquals("7b0031303412", NovoParser.verifyPassword(0x31, 0x30, 0x1234).hex())
        assertEquals(
            "1a6553f1003412080001010000cdab",
            NovoParser.startup(1_700_000_000L, 0x1234, 8, 0xABCD).hex(),
        )
        assertEquals("0100020104033412", NovoParser.historyData(0x0102, 0x0304, 0x1234).hex())
        assertEquals("05", NovoParser.clearHistory().hex())
        assertEquals("09020201", NovoParser.inquireFlashData(0x0102).hex())
        assertEquals("0a0402016400", NovoParser.getFlashData(0x0102, 100).hex())
        assertEquals("040102", NovoParser.localPackage(0x0102).hex())
    }

    @Test
    fun measurementLayoutIsPinned() {
        val frame = "0f0201000001026c07000000193412".hexBytes()
        val measurement = NovoParser.parseMeasurement(frame)!!
        assertEquals(15, measurement.length)
        assertEquals(0x0102, measurement.offset)
        assertEquals(0, measurement.reserved1)
        assertEquals(1, measurement.isHistory)
        assertEquals(2, measurement.trend)
        assertEquals(1900, measurement.electricityMv)
        assertEquals(0, measurement.reserved2)
        assertEquals(0, measurement.reserved3)
        assertEquals(NovoConstants.STATUS_OK, measurement.status)
        assertEquals(25, measurement.cvDiv100)
        assertEquals(0x1234, measurement.crc)
        assertTrue(NovoParser.isLowPower(measurement.electricityMv))
        assertNull(NovoParser.parseMeasurement(frame.copyOf(14)))
    }

    @Test
    fun deviceInfoLayoutIsPinned() {
        val info = NovoParser.parseDeviceInfo("0101000301010005033412".hexBytes())!!
        assertEquals(1, info.origin)
        assertEquals("3.0.1", info.version)
        assertEquals(1, info.passwordExist)
        assertEquals(1, info.crcSupported)
        assertEquals(0, info.factoryCalibrationSupported)
        assertEquals(5, info.workTime)
        assertEquals(3, info.sampleIntervalMinutes)
        assertEquals(0x1234, info.crc)
        assertNull(NovoParser.parseDeviceInfo("01010003010100050334".hexBytes()))
    }

    @Test
    fun flashInquiryAndSessionTimeLayoutsArePinned() {
        val inquiry = NovoParser.parseFlashInquiry("02010004033412".hexBytes())!!
        assertEquals(0x0102, inquiry.count)
        assertEquals(NovoConstants.STATUS_OK, inquiry.status)
        assertEquals(0x0304, inquiry.factoryCode)
        assertEquals(0x1234, inquiry.crc)

        val session = NovoParser.parseSessionTime("0403020108070605083412".hexBytes())!!
        assertEquals(0x01020304L, session.startTimeU32Le)
        assertEquals(0x05060708L, session.runTimeU32Le)
        assertEquals(8, session.timeZone)
        assertEquals(0x1234, session.crc)
    }

    @Test
    fun ackLayoutAndOpcodeDecodingArePinned() {
        val ack = NovoParser.parseAck("011a023412".hexBytes())!!
        assertEquals(1, ack.flag)
        assertEquals(0x1A, ack.tmpOpCode)
        assertEquals(2, ack.result)
        assertEquals(0x1234, ack.crc)
        assertNull(NovoParser.parseAck("011a0234".hexBytes()))
        assertEquals(NovoConstants.RESP_CGM_MEASUREMENT, NovoParser.opcode16("0000a72a00".hexBytes()))
        assertNull(NovoParser.opcode16("0000a7".hexBytes()))
    }

    @Test
    fun glucoseConversionMatchesVendorLinearRegression() {
        assertEquals(1866.67, NovoParser.adcToNaX100(2048), 0.001)
        assertEquals(18.67, NovoParser.glucoseMmol(2048, NovoCalibration(1.0, 0.0)), 0.001)
        assertEquals(336.4f, NovoParser.glucoseMgdl(2048, NovoCalibration(1.0, 0.0)), 0.05f)
        assertEquals(
            34.0,
            NovoParser.glucoseMmol(
                2048,
                NovoCalibration(NovoConstants.VENDOR_UNVERIFIED_K, NovoConstants.VENDOR_UNVERIFIED_B),
            ),
            0.001,
        )
        assertEquals(0.0, NovoParser.glucoseMmol(2048, NovoCalibration(NovoConstants.DEFAULT_K, NovoConstants.DEFAULT_B)), 0.0)
    }

    @Test
    fun batteryAndHistoryTimingHelpers() {
        assertTrue(NovoParser.isLowPower(NovoConstants.LOW_POWER_MV))
        assertFalse(NovoParser.isLowPower(NovoConstants.LOW_POWER_MV + 1))
        assertFalse(NovoParser.isLowPower(0))
        assertEquals(880_000L, NovoParser.historyTimestampMs(2, 1_000_000L))
        assertEquals(-60_000L, NovoParser.historyTimestampMs(1, 0L))
    }
}
