package tk.glucodata.drivers.bioe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BioeParserTests {
    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun txFramesMatchVendorBuilders() {
        assertEquals("8aa8020000", BioeParser.getRealtime().hex())
        assertEquals("8aa803", BioeParser.inquireLocalData().hex())
        assertEquals("8aa8040002", BioeParser.getLocalData(2).hex())
        assertEquals("8aa805", BioeParser.clearHistory().hex())
        assertEquals("8aa806", BioeParser.getVersion().hex())
        assertEquals("8aa807", BioeParser.getSysPara().hex())
        assertEquals("8aa809022301", BioeParser.inquireFlashData(0x0123).hex())
        assertEquals("8aa80a0464006400", BioeParser.getFlashData(100, 100).hex())
        assertEquals("8aa80b", BioeParser.clearFlash().hex())
        assertEquals("8aa80c", BioeParser.getCgmPara().hex())
        assertEquals("8aa8ff", BioeParser.heartbeat().hex())
    }

    @Test
    fun commandIsReadFromTheThirdByte() {
        assertEquals(BioeConstants.CMD_GET_DATA, BioeParser.command(hex("8aa8020000")))
        assertEquals(BioeConstants.CMD_HEARTBEAT, BioeParser.command(hex("8aa8ff")))
        assertNull(BioeParser.command(hex("8aa8")))
        assertNull(BioeParser.command(hex("aaa80200")))
        assertTrue(BioeParser.isHeartbeat(hex("ff")))
        assertFalse(BioeParser.isHeartbeat(hex("8aa8ff")))
    }

    @Test
    fun realtimeFrameCarriesAdcAndAuxWords() {
        val sample = BioeParser.parseRealtime(hex("8aa802000800012c0fa00190"), 1_700_000_000_000L)
        assertNotNull(sample)
        assertEquals(2048, sample!!.adc)
        assertEquals(300, sample.referenceRaw)
        assertEquals(4000, sample.batteryRaw)
        assertEquals(400, sample.ntcRaw)
        assertEquals(1_700_000_000_000L, sample.timestampMs)
    }

    @Test
    fun realtimeFrameRejectsTruncationAndWrongCommand() {
        assertNull(BioeParser.parseRealtime(hex("8aa802000800012c0fa001"), 0L))
        assertNull(BioeParser.parseRealtime(hex("8aa804000800012c0fa00190"), 0L))
    }

    @Test
    fun localDataInquiryCarriesDataAndPackageCounts() {
        val inquiry = BioeParser.parseLocalDataInquiry(hex("8aa8030001012c0003"))
        assertNotNull(inquiry)
        assertTrue(inquiry!!.hasPackage)
        assertEquals(300, inquiry.dataCount)
        assertEquals(3, inquiry.packageCount)
        assertFalse(BioeParser.parseLocalDataInquiry(hex("8aa8030000012c0003"))!!.hasPackage)
        assertNull(BioeParser.parseLocalDataInquiry(hex("8aa80300")))
    }

    @Test
    fun historyFrameUsesBigEndianHeaderAndAdcWords() {
        val batch = BioeParser.parseLocalDataBatch(hex("8aa8040002000308000fff0000"))
        assertNotNull(batch)
        assertEquals(2, batch!!.packageIndex)
        assertEquals(3, batch.count)
        assertEquals(listOf(2048, 4095, 0), batch.adc)
        assertEquals(100, batch.sampleIndex(0))
        assertEquals(102, batch.sampleIndex(2))
        assertNull(BioeParser.parseLocalDataBatch(hex("8aa8040002000380000fff")))
    }

    @Test
    fun flashInquiryUsesBigEndianIndexes() {
        val inquiry = BioeParser.parseFlashInquiry(hex("8aa80900006400c8"))
        assertNotNull(inquiry)
        assertEquals(100, inquiry!!.latestIndex)
        assertEquals(200, inquiry.count)
        assertNull(BioeParser.parseFlashInquiry(hex("8aa80900")))
    }

    @Test
    fun flashFrameUsesLittleEndianHeaderAndBigEndianAdc() {
        val batch = BioeParser.parseFlashBatch(hex("8aa80a066400020008000fff"))
        assertNotNull(batch)
        assertEquals(100, batch!!.index)
        assertEquals(2, batch.count)
        assertEquals(listOf(2048, 4095), batch.adc)
        assertNull(BioeParser.parseFlashBatch(hex("8aa80a06640002000800")))
    }

    @Test
    fun versionAndSysParaFramesDecodeVendorFields() {
        val version = BioeParser.parseVersion(hex("8aa80600000001010207"))
        assertNotNull(version)
        assertEquals(0x010102, version!!.hardFirmwareVersion)
        assertEquals(7, version.manufacturerCode)

        val sysPara = BioeParser.parseSysPara(hex("8aa8070100020100"))
        assertNotNull(sysPara)
        assertEquals(1, sysPara!!.samplePeriod)
        assertEquals(0, sysPara.dataType)
        assertEquals(2, sysPara.workMode)
        assertEquals(1, sysPara.afeOutEnabled)
        assertEquals(0, sysPara.dataFilterEnabled)
    }

    @Test
    fun glucoseUsesFullPrecisionCoefficients() {
        val calibration = BioeCalibration(0.03989725, -33.257442)
        assertTrue(calibration.isUsable)
        assertEquals(34.002190666666664, BioeParser.glucoseMmol(2048, calibration), 1e-9)
        assertEquals(35.198523734293616, BioeParser.glucoseMmol(4095, calibration), 1e-9)
        assertEquals(0.0, BioeParser.glucoseMmol(0, calibration), 0.0)
    }

    @Test
    fun adcIsClampedAndLowCurrentReadingsAreDropped() {
        val calibration = BioeCalibration(1.0, 0.0, lowCurrentThresholdNa = 1000.0)
        assertEquals(1866.6666666666667, BioeParser.adcToNa(2048), 1e-9)
        assertEquals(4865.201822916666, BioeParser.adcToNa(4095), 1e-9)
        assertEquals(4095, BioeParser.clampFlashAdc(9000))
        assertEquals(4095, BioeParser.clampFlashAdc(4095))
        assertEquals(0, BioeParser.clampFlashAdc(-1))
        assertTrue(BioeParser.glucoseMmol(2048, calibration) > 18.0)
        assertEquals(0.0, BioeParser.glucoseMmol(776, calibration), 0.0)
        assertEquals(776, BioeParser.sanitizeHistoryAdc(9000, null))
        assertEquals(2048, BioeParser.sanitizeHistoryAdc(9000, 2048))
        assertEquals(4095, BioeParser.sanitizeHistoryAdc(4095, 2048))
    }

    @Test
    fun mmolConversionUsesDisplayUnit() {
        assertEquals(18.0182f, BioeParser.displayValue(1.0, 0), 0.0001f)
        assertEquals(1.0f, BioeParser.displayValue(1.0, 1), 0.0001f)
    }

    @Test
    fun sampleTimestampsUseUseAtOrTheLocalAnchor() {
        assertEquals(1_000_000L + 3 * 60_000L, BioeParser.sampleTimeMs(3, 300, 0L, 1_000_000L))
        assertEquals(10_000_000L - 2 * 60_000L, BioeParser.sampleTimeMs(297, 300, 10_000_000L, 0L))
        assertEquals(10_000_000L, BioeParser.flashTimeMs(100, 100, 10_000_000L, 0L))
        assertEquals(1_000_000L + 100 * 60_000L, BioeParser.flashTimeMs(100, 200, 0L, 1_000_000L))
    }

    @Test
    fun calibrationRejectsMissingOrNegativeK() {
        assertFalse(BioeCalibration(0.0, -33.0).isUsable)
        assertFalse(BioeCalibration(-1.0, -33.0).isUsable)
        assertTrue(BioeCalibration(0.04, -33.0).isUsable)
    }
}
