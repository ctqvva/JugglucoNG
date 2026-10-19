// JugglucoNG — AiDex Native Kotlin Driver
// AiDexCommandBuilder.kt — F002 command construction + CRC + encryption
//
// Builds encrypted commands to write to F002 characteristic.

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse

/**
 * Builds encrypted F002 commands using the session key from key exchange.
 */
class AiDexCommandBuilder(private val keyExchange: AiDexKeyExchange) {

    /**
     * Build an encrypted F002 command.
     * Adds CRC-16 trailer, then encrypts with session key + SN IV.
     */
    fun buildEncrypted(opcode: Int, vararg params: Int): ByteArray? {
        val plaintext = Crc16CcittFalse.makeCommand(opcode, *params)
        return keyExchange.encrypt(plaintext)
    }

    // -- Convenience Methods --

    fun getStartupDeviceInfo() = buildEncrypted(AiDexOpcodes.GET_STARTUP_DEVICE_INFO)

    @Deprecated("Use getStartupDeviceInfo()")
    fun getOfficialDeviceInfo() = getStartupDeviceInfo()

    fun getLegacyStartTime() = buildEncrypted(AiDexOpcodes.GET_LOCAL_START_TIME)

    @Deprecated("Use getStartupDeviceInfo()")
    fun getDeviceInfo() = getStartupDeviceInfo()

    fun getStartTime() = getLegacyStartTime()

    fun getBroadcastData() = buildEncrypted(AiDexOpcodes.GET_BROADCAST_DATA)

    fun getSensorCheck(index: Int = 0x01) = buildEncrypted(
        AiDexOpcodes.GET_SENSOR_CHECK,
        index and 0xFF
    )

    fun getAutoUpdateStatus() = buildEncrypted(AiDexOpcodes.GET_AUTO_UPDATE_STATUS)

    /**
     * Official native wrapper sends raw `0x31` with a one-byte start index payload.
     * The initial query uses `0x01`, and follow-up reads continue with the next
     * 1-based start index until the assembled DP blob is complete.
     */
    fun getDefaultParam(startIndex: Int = 0x01) = buildEncrypted(
        AiDexOpcodes.GET_DEFAULT_PARAM,
        startIndex and 0xFF
    )

    fun getHistoryRange() = buildEncrypted(AiDexOpcodes.GET_HISTORY_RANGE)

    fun getHistoriesRaw(offset: Int) = buildEncrypted(
        AiDexOpcodes.GET_HISTORIES_RAW,
        offset and 0xFF, (offset shr 8) and 0xFF
    )

    fun getHistories(offset: Int) = buildEncrypted(
        AiDexOpcodes.GET_HISTORIES,
        offset and 0xFF, (offset shr 8) and 0xFF
    )

    /**
     * SET_NEW_SENSOR: activate a new sensor with the given datetime.
     * Payload: [year_lo, year_hi, month, day, hour, minute, second, tz_quarters, dst_quarters]
     */
    fun setNewSensor(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int,
        tzQuarters: Int, dstQuarters: Int
    ) = buildEncrypted(
        AiDexOpcodes.SET_NEW_SENSOR,
        year and 0xFF, (year shr 8) and 0xFF,
        month, day, hour, minute, second,
        tzQuarters, dstQuarters
    )

    /**
     * SET_CALIBRATION: send blood glucose reference to sensor.
     * Wire: [0x25, offset_lo, offset_hi, glucose_lo, glucose_hi, CRC16_lo, CRC16_hi]
     * Note: offset FIRST, glucose SECOND (confirmed by disassembly).
     */
    fun setCalibration(offsetMinutes: Int, glucoseMgDl: Int) = buildEncrypted(
        AiDexOpcodes.SET_CALIBRATION,
        offsetMinutes and 0xFF, (offsetMinutes shr 8) and 0xFF,
        glucoseMgDl and 0xFF, (glucoseMgDl shr 8) and 0xFF
    )

    fun getCalibrationRange() = buildEncrypted(AiDexOpcodes.GET_CALIBRATION_RANGE)

    fun getCalibration(index: Int) = buildEncrypted(
        AiDexOpcodes.GET_CALIBRATION,
        index and 0xFF, (index shr 8) and 0xFF
    )

    fun setAutoUpdateStatus(enabled: Boolean = true) = buildEncrypted(
        AiDexOpcodes.SET_AUTO_UPDATE_STATUS,
        if (enabled) 0x01 else 0x00
    )

    fun setDynamicAdvMode(mode: Int) = buildEncrypted(
        AiDexOpcodes.SET_DYNAMIC_ADV_MODE,
        mode and 0xFF
    )

    /**
     * Raw `0x30` default-param chunk write.
     *
     * Native official flow wraps this in a `DefaultParam` long-attribute state
     * machine and sends chunks as `[totalCount, startIndex, payload...]`.
     */
    fun setDefaultParamChunk(totalCount: Int, startIndex: Int, payload: ByteArray): ByteArray? {
        val params = IntArray(payload.size + 2)
        params[0] = totalCount and 0xFF
        params[1] = startIndex and 0xFF
        for (i in payload.indices) {
            params[i + 2] = payload[i].toInt() and 0xFF
        }
        return buildEncrypted(AiDexOpcodes.SET_DEFAULT_PARAM, *params)
    }

    fun deleteBond() = buildEncrypted(AiDexOpcodes.DELETE_BOND)

    /** Separate hardware-maintenance reset; lifecycle reset uses [clearStorage] only. */
    fun reset() = buildEncrypted(AiDexOpcodes.RESET)

    fun clearStorage() = buildEncrypted(AiDexOpcodes.CLEAR_STORAGE)

    fun shelfMode() = buildEncrypted(AiDexOpcodes.SHELF_MODE)
}
