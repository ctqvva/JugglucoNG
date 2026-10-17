// MedtrumProtocol.kt — EasySense CGM framing, command builders and parsers.
//
// Ported from the vendor app's Java (com.medtrum.healthcareforandroid):
//   BLEBaseCentral.addLengthToPrefix / parseCharacteristicValue / writeCommand
//   SensorInstance.<command> builders, receiveData, onIndicationReceive
// Cross-checked byte-for-byte against the 2026-10-17 HCI capture (SN 0x8ADF6216).
//
// Framing: every CTRLPT PDU is `[u16 LE valueLength][value…]`. A message longer than
// 18 value bytes is split at 20-byte PDUs and the continuations carry no header.
// CGM_DATA notifications are NOT framed — they are the raw record.

package tk.glucodata.drivers.medtrum

object MedtrumProtocol {

    // Command opcodes (SensorInstance builders).
    const val OP_AUTH = 0x05
    const val OP_STOP_UPDATE_CONNECT_PARAM = 0x0A
    const val OP_CALIBRATE = 0x40
    const val OP_READ_STATE = 0x41
    const val OP_READ_RECORD = 0x42
    const val OP_READ_DATA = 0x44 // sub-commands: 4 calibration, 6 special, 7 usage record
    const val OP_SET_SENSOR_CODE = 0x48
    const val OP_CALIBRATE_TIME = 0x57
    const val OP_READ_ORIGINAL = 0x5D

    // Response codes (byte 0 of a response value; BLEBaseCentral constants).
    const val RESPONSE_AUTH = 149 // 0x95
    const val RESPONSE_CALIBRATE = 130 // 0x82

    /** ATT payload size including the length header. */
    const val PDU_SIZE = 20

    const val ROLE_MODERN = 201
    const val ROLE_LEGACY = 2

    // ---- framing ----

    /**
     * Frame [commandData] with its u16 length and split it into ATT-sized PDUs.
     * The first PDU carries the header; later PDUs are raw continuations.
     */
    fun encode(commandData: ByteArray): List<ByteArray> {
        val framed = ByteArray(commandData.size + 2)
        framed[0] = (commandData.size and 0xFF).toByte()
        framed[1] = ((commandData.size shr 8) and 0xFF).toByte()
        commandData.copyInto(framed, 2)
        if (framed.size <= PDU_SIZE) return listOf(framed)
        val out = ArrayList<ByteArray>((framed.size + PDU_SIZE - 1) / PDU_SIZE)
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + PDU_SIZE, framed.size)
            out.add(framed.copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    /** Value length declared by the header of a first PDU, or -1 when unusable. */
    fun declaredLength(pdu: ByteArray): Int {
        if (pdu.size < 2) return -1
        return (pdu[0].toInt() and 0xFF) or ((pdu[1].toInt() and 0xFF) shl 8)
    }

    /** Strip the header from a complete message. Null when the length is inconsistent. */
    fun unframe(framed: ByteArray): ByteArray? {
        val length = declaredLength(framed)
        if (length < 0 || length > framed.size - 2) return null
        return framed.copyOfRange(2, 2 + length)
    }

    // ---- command builders ----

    /**
     * AUTH_REQ. [role] is 201 for firmware > 1.86, else 2. The 4-byte field after the
     * role is always zero — it is not a session token (the app never randomises it).
     */
    fun authorize(role: Int, key: Long): ByteArray =
        byteArrayOf(OP_AUTH.toByte(), role.toByte()) + le32(0L) + le32(key)

    /** readState — the patch answers with its current record. */
    fun readState(): ByteArray = byteArrayOf(OP_READ_STATE.toByte())

    /** readRecord — [from, count] of the stored sample records. */
    fun readRecord(from: Int, count: Int): ByteArray =
        byteArrayOf(OP_READ_RECORD.toByte()) + le16(from) + le16(count)

    /** readCalibrationData (sub 4) / readSpecialData (6) / readUsageRecordData (7). */
    fun readData(sub: Int, sensorId: Int, sequence: Int): ByteArray =
        byteArrayOf(OP_READ_DATA.toByte(), sub.toByte()) + le16(sensorId) + le16(sequence)

    /** calibrate — glucose is a whole number of mg/dL. */
    fun calibrate(glucoseMgdl: Int, seconds: Int, type: Int): ByteArray =
        byteArrayOf(OP_CALIBRATE.toByte()) +
            le16(glucoseMgdl) +
            le32(seconds.toLong()) +
            byteArrayOf(type.toByte())

    /** calibrateTime — seconds since 2014-01-01 local (see MedtrumTime). */
    fun calibrateTime(seconds: Long): ByteArray =
        byteArrayOf(OP_CALIBRATE_TIME.toByte(), 1) + le32(seconds)

    fun stopUpdateConnectParam(): ByteArray =
        byteArrayOf(OP_STOP_UPDATE_CONNECT_PARAM.toByte(), 1)

    // ---- parsers ----

    /** A CTRLPT response value: `[responseCode][opCode][errorCode u16][payload…]`. */
    data class Response(
        val responseCode: Int,
        val opCode: Int,
        val errorCode: Int,
        val payload: ByteArray,
    ) {
        val ok: Boolean get() = errorCode == 0
        override fun equals(other: Any?): Boolean =
            other is Response && responseCode == other.responseCode && opCode == other.opCode &&
                errorCode == other.errorCode && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            ((responseCode * 31 + opCode) * 31 + errorCode) * 31 + payload.contentHashCode()
    }

    /** Parse a fully reassembled CTRLPT response value. */
    fun parseResponse(value: ByteArray): Response? {
        if (value.size < 4) return null
        return Response(
            responseCode = value[0].toInt() and 0xFF,
            opCode = value[1].toInt() and 0xFF,
            errorCode = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8),
            payload = value.copyOfRange(4, value.size),
        )
    }

    /** Firmware version from an AUTH response payload (`[verX][verY][verZ]` at payload 2..4). */
    data class Version(val primary: Int, val secondary: Int, val tertiary: Int) {
        override fun toString(): String = "$primary.$secondary.$tertiary"
    }

    fun parseAuthVersion(response: Response): Version? {
        if (response.opCode != OP_AUTH || response.payload.size < 5) return null
        return Version(
            primary = response.payload[2].toInt() and 0xFF,
            secondary = response.payload[3].toInt() and 0xFF,
            tertiary = response.payload[4].toInt() and 0xFF,
        )
    }

    // ---- advertisement ----

    /**
     * Medtrum manufacturer id. The advert carries `[SN u32 LE][deviceType u8][version u8]…`
     * after it — the byte layout `BLEBaseCentral.parseManufacturerSpecificData` reads.
     * Pump and sensor share the advertised name "MT", so the serial is the only discriminator.
     */
    const val MANUFACTURER_ID = 0x4781

    /** Serial number from a 0x4781 manufacturer payload, or null when it is too short. */
    fun parseAdvertisedSerial(manufacturerData: ByteArray?): Long? {
        if (manufacturerData == null || manufacturerData.size < 4) return null
        var serial = 0L
        for (i in 0 until 4) serial = serial or ((manufacturerData[i].toInt() and 0xFF).toLong() shl (8 * i))
        return serial
    }

    /** Device type byte from a 0x4781 manufacturer payload (136 = TG158 sensor). */
    fun parseAdvertisedDeviceType(manufacturerData: ByteArray?): Int? =
        manufacturerData?.takeIf { it.size >= 5 }?.let { it[4].toInt() and 0xFF }

    // ---- readState response (0x41) ----

    /**
     * The long record the patch answers a readState with. SensorInstance receives it with
     * `receiveData(..., fromConnect = true)`, whose offsets are one higher than the CGM_DATA
     * notification's because the response already carries `[responseCode][opCode][error u16]`.
     * `runningTime`/`insertTime` are the two counters a calibrate command needs.
     */
    data class StateRecord(
        val version: Int,
        val phase: Phase,
        val status: Int,
        val sensorId: Int,
        val sequence: Int,
        val signal: Int,
        val calibrationFactor: Int,
        val runningTime: Long,
        val insertTime: Long,
        val nextCalibrationSequence: Int,
        val batteryMillivolts: Int,
    ) {
        /** Seconds since the patch was inserted. */
        val elapsedSeconds: Long get() = runningTime - insertTime
    }

    fun parseStateRecord(value: ByteArray): StateRecord? {
        if (value.size < 37) return null
        return StateRecord(
            version = value[4].toInt() and 0xFF,
            phase = Phase.entries[value[5].toInt() and 0x03],
            status = u16(value, 6),
            sensorId = u16(value, 9),
            sequence = u16(value, 11),
            signal = u16(value, 13),
            calibrationFactor = u16(value, 23),
            runningTime = u32(value, 25),
            insertTime = u32(value, 29),
            nextCalibrationSequence = u16(value, 33),
            batteryMillivolts = u16(value, 35),
        )
    }

    // ---- CGM_DATA record (raw notification, no framing) ----
    enum class Phase { SETUP, TRANSITION, HYDRATION, MEASURING }

    /**
     * The patch's periodic CGM record, as read by SensorInstance.receiveData
     * (notification branch). Indices are into the raw ATT value.
     */
    data class CgmRecord(
        val phase: Phase,
        val status: Int,
        val sequence: Int,
        val sensorId: Int,
        val signal: Int,
        val rateLevel: Int,
        val intercept: Int,
        val calibrationFactor: Int,
    ) {
        /** Glucose in mg/dL, or null while warming up / before the first calibration. */
        val glucoseMgdl: Double?
            get() = if (phase == Phase.MEASURING && calibrationFactor != 0) {
                signal * 1000.0 / calibrationFactor + intercept
            } else {
                null
            }
    }

    fun parseCgmRecord(value: ByteArray): CgmRecord? {
        if (value.size < 20) return null
        return CgmRecord(
            phase = Phase.entries[value[0].toInt() and 0x03],
            status = u16(value, 1),
            sequence = u16(value, 4),
            sensorId = u16(value, 6),
            signal = u16(value, 8),
            rateLevel = value[16].toInt() and 0xFF,
            intercept = value[17].toInt(), // signed
            calibrationFactor = u16(value, 18),
        )
    }

    // ---- little-endian helpers ----

    fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun u32(data: ByteArray, offset: Int): Long =
        (u16(data, offset).toLong()) or (u16(data, offset + 2).toLong() shl 16)

    fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    fun bytesToLong(data: ByteArray): Long {
        var result = 0L
        for (i in data.indices) result = result or ((data[i].toInt() and 0xFF).toLong() shl (8 * i))
        return result
    }
}
