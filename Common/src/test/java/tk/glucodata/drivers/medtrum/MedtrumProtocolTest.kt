package tk.glucodata.drivers.medtrum

import org.junit.Assert.*
import org.junit.Test

/**
 * Byte-for-byte vectors taken from the 2026-10-17 HCI capture of a real EasySense patch
 * (SN 0x8ADF6216) and from the vendor app's own builders. See
 * docs/medtrum-ble-capture-2026-10-17.md.
 */
class MedtrumProtocolTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // --- readState record ---

    @Test
    fun stateRecordCarriesTheCountersCalibrationNeeds() {
        // Captured 0x41 readState reply (unframed): phase Measuring, sensorId 13, sequence 15,
        // signal 1601, runningTime-insertTime = 1864 s, battery 0x084b = 2123 mV.
        val value = hex("83410000180f05005f0d000f0041067b068a069b0607000000c1121018790b10180f004b08")
        val state = MedtrumProtocol.parseStateRecord(value)
        assertNotNull(state)
        assertEquals(MedtrumProtocol.Phase.MEASURING, state!!.phase)
        assertEquals(13, state.sensorId)
        assertEquals(15, state.sequence)
        assertEquals(1601, state.signal)
        assertEquals(403706561L, state.runningTime)
        assertEquals(403704697L, state.insertTime)
        assertEquals(1864L, state.elapsedSeconds)
        assertEquals(15, state.nextCalibrationSequence)
        assertEquals(2123, state.batteryMillivolts)
    }

    @Test
    fun stateRecordRejectsShortValues() {
        assertNull(MedtrumProtocol.parseStateRecord(hex("83410000180000")))
    }

    // --- advertisement ---

    @Test
    fun advertisedSerialComesFromTheManufacturerData() {
        // Captured 0x4781 payload (company id stripped, as Android delivers it):
        // SN 0x8ADF6216, device type 0x88 (136, TG158), version 0x18.
        val manuf = hex("1662df8a88188d1921378d192c558d1927558d19")
        assertEquals(2329895446L, MedtrumProtocol.parseAdvertisedSerial(manuf))
        assertEquals(136, MedtrumProtocol.parseAdvertisedDeviceType(manuf))
    }

    @Test
    fun advertisedSerialRejectsShortPayloads() {
        assertNull(MedtrumProtocol.parseAdvertisedSerial(null))
        assertNull(MedtrumProtocol.parseAdvertisedSerial(hex("1662df")))
        assertNull(MedtrumProtocol.parseAdvertisedDeviceType(hex("1662df8a")))
    }

    // --- framing ---

    @Test
    fun authRequestFrameMatchesCapture() {
        // Captured ATT value of the AUTH_REQ write.
        val key = MedtrumCrypt.keyGen(2329895446L)
        val pdus = MedtrumProtocol.encode(MedtrumProtocol.authorize(MedtrumProtocol.ROLE_MODERN, key))
        assertEquals(1, pdus.size)
        assertEquals("0a0005c900000000134406f0", pdus[0].hex())
    }

    @Test
    fun lengthHeaderIsValueLength() {
        val pdus = MedtrumProtocol.encode(MedtrumProtocol.readRecord(0x11, 1))
        assertEquals("05004211000100", pdus[0].hex())
        assertEquals(5, MedtrumProtocol.declaredLength(pdus[0]))
    }

    @Test
    fun longCommandSplitsAtTwentyBytePduWithHeaderlessContinuation() {
        // Captured 25-byte writeSensorAlarm command: 20-byte PDU then a 5-byte raw tail.
        val value = hex("54033f00009001009001000f00000f000100") + hex("005000f000") // 23 bytes
        val pdus = MedtrumProtocol.encode(value)
        assertEquals(2, pdus.size)
        assertEquals(20, pdus[0].size)
        assertEquals(5, pdus[1].size)
        assertEquals("170054033f00009001009001000f00000f000100", pdus[0].hex())
        assertEquals("005000f000", pdus[1].hex())
        // The declared length counts the whole value, not just the first PDU.
        assertEquals(23, MedtrumProtocol.declaredLength(pdus[0]))
    }

    @Test
    fun reassembledIndicationUnframesToItsValue() {
        // The 39-byte 0x41 record spanning two indications.
        val first = hex("25008341000018000000640d0000000000000000")
        val second = hex("0000000000000007000000000000006054b608")
        val joined = first + second
        assertEquals(39, joined.size)
        assertEquals(37, MedtrumProtocol.declaredLength(joined))
        val value = MedtrumProtocol.unframe(joined)
        assertNotNull(value)
        assertEquals(37, value!!.size)
        assertEquals("8341000018000000640d", value.copyOfRange(0, 10).hex())
    }

    // --- AUTH ---

    @Test
    fun authResponseParsesResponseCodeOpCodeAndVersion() {
        val value = MedtrumProtocol.unframe(hex("0d0095050000c9880146180718ffff"))
        assertNotNull(value)
        val response = MedtrumProtocol.parseResponse(value!!)
        assertNotNull(response)
        assertEquals(MedtrumProtocol.RESPONSE_AUTH, response!!.responseCode) // 0x95 = 149
        assertEquals(MedtrumProtocol.OP_AUTH, response.opCode)
        assertEquals(0, response.errorCode)
        assertTrue(response.ok)
        val version = MedtrumProtocol.parseAuthVersion(response)
        assertNotNull(version)
        assertEquals(1, version!!.primary)
        assertEquals(70, version.secondary)
        assertEquals(24, version.tertiary)
        assertEquals("1.70.24", version.toString())
    }

    @Test
    fun authenticateLayoutIsOpCodeRoleZeroTokenKey() {
        val key = MedtrumCrypt.keyGen(2329895446L)
        val cmd = MedtrumProtocol.authorize(MedtrumProtocol.ROLE_MODERN, key)
        assertEquals(10, cmd.size)
        assertEquals(MedtrumProtocol.OP_AUTH.toByte(), cmd[0])
        assertEquals(201.toByte(), cmd[1]) // role, not a token
        assertArrayEquals(ByteArray(4), cmd.copyOfRange(2, 6)) // always zero
        assertEquals("134406f0", cmd.copyOfRange(6, 10).hex())
    }

    // --- CGM record ---

    @Test
    fun cgmRecordAfterCalibrationYieldsGlucose() {
        // Captured notification: phase 3, signal 1629, calibrationFactor 12530 -> 130 mg/dL,
        // which is exactly the value the user entered (calibrate 0x0082).
        val record = MedtrumProtocol.parseCgmRecord(hex("0f09005f12000d005d064606390641060000f230"))
        assertNotNull(record)
        assertEquals(MedtrumProtocol.Phase.MEASURING, record!!.phase)
        assertEquals(18, record.sequence)
        assertEquals(13, record.sensorId)
        assertEquals(1629, record.signal)
        assertEquals(12530, record.calibrationFactor)
        assertEquals(0, record.intercept)
        assertEquals(130.0, record.glucoseMgdl!!, 0.05)
    }

    @Test
    fun cgmRecordDuringWarmUpHasNoGlucose() {
        // Captured earlier notification: phase 2 (Hydration), calibrationFactor 0.
        val record = MedtrumProtocol.parseCgmRecord(hex("0e01005f0d000d008a069b069b06790607000000"))
        assertNotNull(record)
        assertEquals(MedtrumProtocol.Phase.HYDRATION, record!!.phase)
        assertNull(record.glucoseMgdl)
    }

    @Test
    fun cgmRecordRejectsShortValues() {
        assertNull(MedtrumProtocol.parseCgmRecord(hex("0f09005f12")))
    }

    // --- calibrate ---

    @Test
    fun calibrateMatchesCapture() {
        // The user's calibration: glucose 130 mg/dL, 2147 s, type 0.
        val pdus = MedtrumProtocol.encode(MedtrumProtocol.calibrate(130, 2147, 0))
        assertEquals(1, pdus.size)
        assertEquals("08004082006308000000", pdus[0].hex())
    }

    @Test
    fun calibrateTimeUsesAFourByteCounter() {
        val pdus = MedtrumProtocol.encode(MedtrumProtocol.calibrateTime(0x180FF718L))
        assertEquals("0600570118f70f18", pdus[0].hex())
    }
}
