package tk.glucodata.drivers.scgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScgmParserTests {
    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun raw(cmd: Int, payload: String, trailer: String = "00000000"): ByteArray =
        hex("5aa5" + "%02x".format(cmd) + "%02x".format(payload.length / 2) + payload + trailer)

    @Test
    fun txFramesFollowVendorPduCreator() {
        assertEquals("5aa500040403020100000000", ScgmParser.heartbeat(0x01020304).hex())
        assertEquals("5aa5010000000000", ScgmParser.currentResult().hex())
        assertEquals("5aa50202000000000000", ScgmParser.queryHistory().hex())
        assertEquals("5aa503042301640000000000", ScgmParser.historyPage(0x0123, 100).hex())
        assertEquals("5aa5040000000000", ScgmParser.clearHistory().hex())
        assertEquals("5aa505010100000000", ScgmParser.activation().hex())
        assertEquals("5aa5100000000000", ScgmParser.terminalInfo().hex())
        assertEquals("5aa5110f12340102030405060708090a0b0c0d00000000", ScgmParser.authenticate(0x1234, hex("0102030405060708090a0b0c0d")).hex())
    }

    @Test
    fun frameIsDecodedFromRawNotificationAndReaderShape() {
        val frame = raw(1, "64000800")
        assertNotNull(ScgmParser.decode(frame))
        assertEquals(1, ScgmParser.command(frame))
        assertEquals("64000800", ScgmParser.decode(frame)!!.payload.hex())
        assertEquals(1, ScgmParser.command(hex("010464000800")))
        assertNull(ScgmParser.command(hex("5aa5")))
        assertNull(ScgmParser.command(hex("aaa5010464000800")))
        assertNull(ScgmParser.command(hex("5aa50108640000")))
    }

    @Test
    fun realtimeCarriesLittleEndianIndexAdcBatteryAndNtc() {
        val sample = ScgmParser.parseRealtime(raw(1, "02010008a00f9001"), 1_700_000_000_000L)
        assertNotNull(sample)
        assertEquals(258, sample!!.index)
        assertEquals(2048, sample.adc)
        assertEquals(4000, sample.batteryMv)
        assertEquals(400, sample.ntc)
        assertEquals(1_700_000_000_000L, sample.timestampMs)
    }

    @Test
    fun realtimeRejectsZeroIndexAndTruncation() {
        assertNull(ScgmParser.parseRealtime(raw(1, "00000008a00f9001"), 0L))
        assertNull(ScgmParser.parseRealtime(raw(1, "02000008a00f90"), 0L))
        assertNull(ScgmParser.parseRealtime(raw(2, "02000008a00f9001"), 0L))
    }

    @Test
    fun historyInquiryCarriesLatestIndexAndDataCount() {
        val inquiry = ScgmParser.parseHistoryInquiry(raw(2, "6400c800"))
        assertNotNull(inquiry)
        assertEquals(100, inquiry!!.latestIndex)
        assertEquals(200, inquiry.dataCount)
        assertNull(ScgmParser.parseHistoryInquiry(raw(2, "6400c8")))
        assertNull(ScgmParser.parseHistoryInquiry(raw(1, "6400c800")))
    }

    @Test
    fun historyPageCarriesLittleEndianAdcWords() {
        val page = ScgmParser.parseHistoryPage(raw(3, "640003000008ff0f0000"))
        assertNotNull(page)
        assertEquals(100, page!!.startIndex)
        assertEquals(3, page.count)
        assertEquals(listOf(2048, 4095, 0), page.adc)
        assertNull(ScgmParser.parseHistoryPage(raw(3, "640003000008ff0f")))
        assertNull(ScgmParser.parseHistoryPage(raw(3, "6400000000000000")))
    }

    @Test
    fun terminalInfoDecodesSixteenByteCodeAndVendorFields() {
        val code = "SCGM000000000000"
        val payload = "5343474d303030303030303030303030010203043c00b80b6400"
        assertEquals(52, payload.length)
        val info = ScgmParser.parseTerminalInfo(raw(16, payload))
        assertNotNull(info)
        assertEquals(code, info!!.deviceCode)
        assertEquals("01020304", info.firmware)
        assertEquals(60, info.runTimeMinutes)
        assertEquals(3000, info.biasVoltageMv)
        assertEquals(100, info.packageCount)
        assertNull(ScgmParser.parseTerminalInfo(raw(16, "01020304")))
    }

    @Test
    fun heartbeatStatusIsParsed() {
        assertEquals(1, ScgmParser.parseHeartbeat(raw(0, "01"))!!.status)
        assertNull(ScgmParser.parseHeartbeat(raw(0, "")))
        assertNull(ScgmParser.parseHeartbeat(raw(1, "01")))
    }

    @Test
    fun glucoseUsesAdcAsNanoampereTimesHundredAndRoundsToTwoDecimals() {
        val calibration = ScgmCalibration(1.0, 0.0)
        assertEquals(1000.0, ScgmParser.adcToNa(1000), 1e-9)
        assertEquals(10.0, ScgmParser.glucoseMmol(1000, calibration), 1e-9)
        assertEquals(180.182f, ScgmParser.glucoseMgdl(1000, calibration), 1e-4f)
        assertEquals(4.0, ScgmParser.glucoseMmol(1000, ScgmCalibration(0.5, 1.0)), 1e-9)
        assertEquals(1.0, ScgmParser.glucoseMmol(999, ScgmCalibration(0.1, 0.0)), 1e-9)
    }

    @Test
    fun glucoseRejectsUnusableCalibrationAndLowCurrent() {
        assertEquals(0.0, ScgmParser.glucoseMmol(1000, ScgmCalibration(0.0, 0.0)), 1e-9)
        assertEquals(0.0, ScgmParser.glucoseMmol(1000, ScgmCalibration(-1.0, 0.0)), 1e-9)
        assertEquals(0.0, ScgmParser.glucoseMmol(0, ScgmCalibration(1.0, 0.0)), 1e-9)
        assertEquals(0.0, ScgmParser.glucoseMmol(1000, ScgmCalibration(1.0, 0.0, 2000.0)), 1e-9)
    }

    @Test
    fun displayValueFollowsSelectedUnit() {
        assertEquals(5.5f, ScgmParser.displayValue(5.5, 1))
        assertEquals(99.1f, ScgmParser.displayValue(5.5, 0), 1e-4f)
    }

    @Test
    fun batteryLowPowerThresholdIsInclusive() {
        org.junit.Assert.assertFalse(ScgmParser.isLowBattery(0))
        org.junit.Assert.assertFalse(ScgmParser.isLowBattery(1901))
        org.junit.Assert.assertTrue(ScgmParser.isLowBattery(1900))
        org.junit.Assert.assertTrue(ScgmParser.isLowBattery(1800))
    }

    @Test
    fun historyTimestampsStepBackWholeMinutesFromLastTime() {
        val lastTime = 1_700_000_000_000L
        assertEquals(20_000L, lastTime % 60_000L)
        assertEquals(1_699_999_940_000L, ScgmParser.historyTimeMs(100, 100, lastTime))
        assertEquals(1_699_999_880_000L, ScgmParser.historyTimeMs(99, 100, lastTime))
        assertEquals(1_699_999_340_000L, ScgmParser.historyTimeMs(90, 100, lastTime))
    }

    @Test
    fun shouldReadSubtractsAlreadyCoveredSamples() {
        assertEquals(400, ScgmParser.shouldRead(400, 101, 500))
        assertEquals(380, ScgmParser.shouldRead(400, 520, 500))
        assertEquals(0, ScgmParser.shouldRead(10, 20, 5))
    }

    @Test
    fun bootAtAndLastPidUseVendorPackageCountQuirk() {
        val now = 1_700_000_000_000L
        val bootAt = ScgmParser.bootAtSeconds(now, 100)
        assertEquals(1_699_994_000L, bootAt)
        assertEquals(100, ScgmParser.lastPidFromBoot(now, bootAt))
        assertEquals(0, ScgmParser.lastPidFromBoot(now, now / 1000L))
    }
}
