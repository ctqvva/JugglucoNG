package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse

class AiDexCommandBuilderExtraTests {

    private fun builderWithSessionKey(): Pair<AiDexKeyExchange, AiDexCommandBuilder> {
        val exchange = AiDexKeyExchange("2222267V4E")
        val field = exchange.javaClass.getDeclaredField("sessionKey")
        field.isAccessible = true
        field.set(exchange, ByteArray(16) { (it + 1).toByte() })
        return exchange to AiDexCommandBuilder(exchange)
    }

    private fun plain(exchange: AiDexKeyExchange, bytes: ByteArray?): ByteArray {
        assertNotNull(bytes)
        val decoded = exchange.decrypt(bytes!!)
        assertNotNull(decoded)
        assertTrue(Crc16CcittFalse.validateResponse(decoded!!))
        return decoded
    }

    private fun assertOpcode(opcode: Int, decoded: ByteArray) {
        assertEquals(opcode, decoded[0].toInt() and 0xFF)
    }

    @Test
    fun aliasesShareTheSameOpcodes() {
        val (exchange, builder) = builderWithSessionKey()
        val startup = plain(exchange, builder.getStartupDeviceInfo())
        assertOpcode(AiDexOpcodes.GET_STARTUP_DEVICE_INFO, startup)
        assertOpcode(AiDexOpcodes.GET_STARTUP_DEVICE_INFO, plain(exchange, builder.getOfficialDeviceInfo()))
        assertOpcode(AiDexOpcodes.GET_STARTUP_DEVICE_INFO, plain(exchange, builder.getDeviceInfo()))
        assertOpcode(AiDexOpcodes.GET_LOCAL_START_TIME, plain(exchange, builder.getLegacyStartTime()))
        assertOpcode(AiDexOpcodes.GET_LOCAL_START_TIME, plain(exchange, builder.getStartTime()))
    }

    @Test
    fun simpleQueriesCarryExpectedOpcodes() {
        val (exchange, builder) = builderWithSessionKey()
        assertOpcode(AiDexOpcodes.GET_AUTO_UPDATE_STATUS, plain(exchange, builder.getAutoUpdateStatus()))
        assertOpcode(AiDexOpcodes.GET_CALIBRATION_RANGE, plain(exchange, builder.getCalibrationRange()))
        assertOpcode(AiDexOpcodes.CLEAR_STORAGE, plain(exchange, builder.clearStorage()))
        assertOpcode(AiDexOpcodes.SHELF_MODE, plain(exchange, builder.shelfMode()))
    }

    @Test
    fun sensorCheckDefaultsToOneAndMasksIndex() {
        val (exchange, builder) = builderWithSessionKey()
        val default = plain(exchange, builder.getSensorCheck())
        assertOpcode(AiDexOpcodes.GET_SENSOR_CHECK, default)
        assertEquals(1, default[1].toInt() and 0xFF)

        val masked = plain(exchange, builder.getSensorCheck(index = 0x1_02))
        assertEquals(0x02, masked[1].toInt() and 0xFF)
    }

    @Test
    fun calibrationIsLittleEndianWithIndex() {
        val (exchange, builder) = builderWithSessionKey()
        val decoded = plain(exchange, builder.getCalibration(0x1234))
        assertOpcode(AiDexOpcodes.GET_CALIBRATION, decoded)
        assertEquals(0x34, decoded[1].toInt() and 0xFF)
        assertEquals(0x12, decoded[2].toInt() and 0xFF)
    }

    @Test
    fun calibrationCommandSendsOffsetBeforeGlucose() {
        val (exchange, builder) = builderWithSessionKey()
        val decoded = plain(exchange, builder.setCalibration(offsetMinutes = 200, glucoseMgDl = 180))
        assertOpcode(AiDexOpcodes.SET_CALIBRATION, decoded)
        assertEquals(200, (decoded[1].toInt() and 0xFF) or ((decoded[2].toInt() and 0xFF) shl 8))
        assertEquals(180, (decoded[3].toInt() and 0xFF) or ((decoded[4].toInt() and 0xFF) shl 8))
    }

    @Test
    fun newSensorEncodesYearLittleEndian() {
        val (exchange, builder) = builderWithSessionKey()
        val decoded = plain(exchange, builder.setNewSensor(2024, 5, 6, 7, 8, 9, tzQuarters = 4, dstQuarters = 0))
        assertOpcode(AiDexOpcodes.SET_NEW_SENSOR, decoded)
        assertEquals(0xE8, decoded[1].toInt() and 0xFF)
        assertEquals(0x07, decoded[2].toInt() and 0xFF)
        assertEquals(listOf<Byte>(5, 6, 7, 8, 9, 4, 0), decoded.copyOfRange(3, 10).toList())
    }

    @Test
    fun autoUpdateAndDynamicAdvMode() {
        val (exchange, builder) = builderWithSessionKey()
        assertOpcode(AiDexOpcodes.SET_AUTO_UPDATE_STATUS, plain(exchange, builder.setAutoUpdateStatus(true)))
        val disabled = plain(exchange, builder.setAutoUpdateStatus(false))
        assertEquals(0x00, disabled[1].toInt() and 0xFF)

        val adv = plain(exchange, builder.setDynamicAdvMode(0x1_05))
        assertOpcode(AiDexOpcodes.SET_DYNAMIC_ADV_MODE, adv)
        assertEquals(0x05, adv[1].toInt() and 0xFF)
    }

    @Test
    fun defaultParamChunkCarriesCountIndexAndPayload() {
        val (exchange, builder) = builderWithSessionKey()
        val decoded = plain(exchange, builder.setDefaultParamChunk(totalCount = 3, startIndex = 2, payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        assertOpcode(AiDexOpcodes.SET_DEFAULT_PARAM, decoded)
        assertEquals(3, decoded[1].toInt() and 0xFF)
        assertEquals(2, decoded[2].toInt() and 0xFF)
        assertEquals(0xAA, decoded[3].toInt() and 0xFF)
        assertEquals(0xBB, decoded[4].toInt() and 0xFF)
    }
}
