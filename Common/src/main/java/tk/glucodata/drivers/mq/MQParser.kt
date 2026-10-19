// MQParser.kt — Wire-format parsing and frame builders for the MQ/Glutec
// Nordic UART protocol.
//
// Frame:  [5A A5] [CMD] [LEN] [payload ...] [CRC_HI CRC_LO]
// Protocol01: CRC is recomputed fresh by us on every outgoing frame.
// Protocol02: the transmitter accepts pre-baked CRC bytes; we cache them for
//             speed but still recompute when we build frames from scratch.
//             The two versions differ only in how strictly the transmitter
//             pattern-matches on the confirm bytes.
//
// The BG payload contains N records of 6 bytes each. For the validated
// W25101399 family the layout is:
//   [marker packet_lo packet_hi current_lo current_hi battery]

package tk.glucodata.drivers.mq

import tk.glucodata.Log

data class MQFrame(
    val cmd: Byte,
    val len: Int,
    val payload: ByteArray,
    val raw: ByteArray,
    /** True if the trailing CRC verified against CRC16-Modbus. Some Protocol02
     *  firmware uses pre-baked CRC bytes that don't match the computed value
     *  — we still accept the frame, because header+length+size gates already
     *  filter noise, and the transmitter is the authoritative source. */
    val crcValid: Boolean = true,
) {
    val cmdUnsigned: Int get() = cmd.toInt() and 0xFF
}

data class MQBgRecord(
    val indexInPacket: Int,
    val marker: Int,
    val packetIndex: Int,
    val sampleCurrent: Int,
    /** Raw vendor battery field; not a percentage. */
    val batteryRaw: Int,
    /** 6 bytes of the raw record for durable storage / replay. */
    val recordBytes: ByteArray,
)

object MQParser {

    /**
     * Parse a full BLE notification buffer into a framed packet.
     *
     * CRC mismatches are logged but **do not** reject the frame: Protocol02
     * transmitters ship pre-baked CRC bytes that don't match CRC16-Modbus.
     * Header and length gates are strict enough to filter noise.
     */
    @JvmStatic
    fun parse(buffer: ByteArray?): MQFrame? {
        if (buffer == null || buffer.size < MQConstants.FRAME_OVERHEAD) return null
        if (buffer[0] != MQConstants.HEADER0 || buffer[1] != MQConstants.HEADER1) return null
        val len = buffer[MQConstants.OFFSET_LEN].toInt() and 0xFF
        val expectedSize = MQConstants.FRAME_OVERHEAD + len
        if (buffer.size < expectedSize) return null
        val frame = if (buffer.size == expectedSize) buffer else buffer.copyOf(expectedSize)
        val crcOk = MQCrc16.verify(frame)
        if (!crcOk) {
            val computed = MQCrc16.compute(frame, 0, frame.size - 2)
            val observedHi = frame[frame.size - 2].toInt() and 0xFF
            val observedLo = frame[frame.size - 1].toInt() and 0xFF
            val detail = if (frame[MQConstants.OFFSET_CMD] == MQConstants.CMD_NOTIFY_BG_COMPLETE) {
                "Protocol02 session marker"
            } else {
                "Protocol02-style inbound frame"
            }
            Log.d(
                MQConstants.TAG,
                "CRC mismatch cmd=0x%02X len=%d computed=0x%04X observed=0x%02X%02X — accepting %s"
                    .format(
                        frame[MQConstants.OFFSET_CMD].toInt() and 0xFF,
                        len,
                        computed,
                        observedHi,
                        observedLo,
                        detail,
                    ),
            )
        }
        val payload = if (len == 0) ByteArray(0)
        else frame.copyOfRange(MQConstants.OFFSET_PAYLOAD, MQConstants.OFFSET_PAYLOAD + len)
        return MQFrame(
            cmd = frame[MQConstants.OFFSET_CMD],
            len = len,
            payload = payload,
            raw = frame,
            crcValid = crcOk,
        )
    }

    /** Extract fixed-size BG records out of a validated 0x04 frame. */
    @JvmStatic
    fun parseBgRecords(frame: MQFrame): List<MQBgRecord> {
        if (frame.cmd != MQConstants.CMD_NOTIFY_BG_DATA) return emptyList()
        val count = frame.len / MQConstants.BG_RECORD_SIZE
        if (count <= 0) return emptyList()
        val out = ArrayList<MQBgRecord>(count)
        var idx = 0
        while (idx < count) {
            val base = idx * MQConstants.BG_RECORD_SIZE
            val marker = frame.payload[base + MQConstants.BG_OFFSET_MARKER].toInt() and 0xFF
            val packetLo = frame.payload[base + MQConstants.BG_OFFSET_PACKET_LO].toInt() and 0xFF
            val packetHi = frame.payload[base + MQConstants.BG_OFFSET_PACKET_HI].toInt() and 0xFF
            val currentLo = frame.payload[base + MQConstants.BG_OFFSET_CURRENT_LO].toInt() and 0xFF
            val currentHi = frame.payload[base + MQConstants.BG_OFFSET_CURRENT_HI].toInt() and 0xFF
            val battery = frame.payload[base + MQConstants.BG_OFFSET_BATTERY].toInt() and 0xFF
            val rec = frame.payload.copyOfRange(base, base + MQConstants.BG_RECORD_SIZE)
            out.add(
                MQBgRecord(
                    indexInPacket = idx,
                    marker = marker,
                    packetIndex = (packetHi shl 8) or packetLo,
                    sampleCurrent = (currentHi shl 8) or currentLo,
                    batteryRaw = battery,
                    recordBytes = rec,
                )
            )
            idx++
        }
        return out
    }

    // ---- Confirm / control frame builders ----

    /**
     * Build a minimal 7-byte confirm frame with a CRC-stamped body.
     *
     * [crcXorOut] selects the CRC variant: 0x0000 for Protocol01 (vanilla
     * Modbus), 0x0100 for Protocol02 (observed on W25101399 transmitter).
     */
    @JvmStatic
    @JvmOverloads
    fun buildConfirm(cmd: Byte, crcXorOut: Int = 0): ByteArray {
        val frame = byteArrayOf(
            MQConstants.HEADER0,
            MQConstants.HEADER1,
            cmd,
            0x01, // LEN
            0x00, // payload
            0x00, // CRC placeholder hi
            0x00, // CRC placeholder lo
        )
        return MQCrc16.stamp(frame, crcXorOut)
    }

    @JvmStatic @JvmOverloads
    fun buildConfirmBgData(crcXorOut: Int = 0): ByteArray =
        buildConfirm(MQConstants.CMD_WRITE_BG_DATA_CONFIRM, crcXorOut)

    @JvmStatic @JvmOverloads
    fun buildConfirmWithInit(crcXorOut: Int = 0): ByteArray =
        buildConfirm(MQConstants.CMD_WRITE_CONFIRM_WITH_INIT, crcXorOut)

    @JvmStatic @JvmOverloads
    fun buildConfirmWithoutInit(crcXorOut: Int = 0): ByteArray =
        buildConfirm(MQConstants.CMD_WRITE_CONFIRM_WITHOUT_INIT, crcXorOut)

    @JvmStatic @JvmOverloads
    fun buildConfirmReset(crcXorOut: Int = 0): ByteArray =
        buildConfirm(MQConstants.CMD_WRITE_CONFIRM_RESET, crcXorOut)
}
