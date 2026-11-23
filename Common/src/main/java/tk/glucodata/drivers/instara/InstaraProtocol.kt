package tk.glucodata.drivers.instara

import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import java.util.UUID

object InstaraProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8653000A-43E6-47B7-9CB0-5FC21D4AE340")
    val WRITE_UUID: UUID = UUID.fromString("8653000C-43E6-47B7-9CB0-5FC21D4AE340")
    val NOTIFY_UUID: UUID = UUID.fromString("8653000B-43E6-47B7-9CB0-5FC21D4AE340")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_BATTERY = 0x02
    const val CMD_SEQUENCE = 0x03
    const val CMD_FUNCTION = 0x04
    const val CMD_CLEAR_HISTORY = 0x05
    const val CMD_STATUS = 0x06
    const val CMD_SN = 0x07
    const val CMD_TEMPERATURE = 0x08
    const val CMD_SET_DURATION = 0x09
    const val CMD_DURATION = 0x0A
    const val CMD_USE_TIME = 0x0B

    const val RSP_BLOOD = 0x10
    const val RSP_BATTERY = 0x20
    const val RSP_HISTORY = 0x30
    const val RSP_ERROR = 0x40
    const val RSP_DATE_OVER = 0x50
    const val RSP_STATUS = 0x60
    const val RSP_TEMPERATURE = 0x70
    const val RSP_HANDSHAKE = 0xF0
    const val RSP_PROTOCOL_ERROR = 0xFF

    private val HEADER = byteArrayOf(0x55.toByte(), 0xAA.toByte())

    sealed class Packet {
        data class Realtime(val sequenceId: Int, val glucoseRaw: Float) : Packet()
        data class HistoryHeader(val frameCount: Int) : Packet()
        data class HistoryPayload(
            val bloodDataLength: Int,
            val startSequenceId: Int,
            val duration: Int,
            val values: List<Float>,
        ) : Packet()
        data class Battery(val percent: Int) : Packet()
        data class Status(val value: Int) : Packet()
        data class SnAuth(val ok: Boolean) : Packet()
        data class Handshake(val hardware: Int, val software: Int) : Packet()
        data class Temperature(val value: Int) : Packet()
        data class Duration(val minutes: Int) : Packet()
        data class Simple(val cmd: Int) : Packet()
    }

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 255) { "payload too long: ${payload.size}" }
        val out = ByteArray(HEADER.size + 2 + payload.size + 1)
        out[0] = HEADER[0]
        out[1] = HEADER[1]
        out[2] = cmd.toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        out[out.lastIndex] = Crc8Maxim.checksum(out.copyOf(out.lastIndex)).toByte()
        return out
    }

    fun ack(cmd: Int): ByteArray = frame(cmd)

    private fun hasBytes(data: ByteArray, count: Int): Boolean =
        data.size >= 4 + count && (data[3].toInt() and 0xFF) >= count

    fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun needsAck(cmd: Int): Boolean = when (cmd and 0xFF) {
        0x01, 0x10, 0x02, 0x20, 0x08, 0x70, 0x30, 0x40, 0x50, 0xFF, 0xF0 -> true
        else -> false
    }

    fun parse(data: ByteArray): Packet? {
        if (data.size < 5) return null
        if (data[0] != HEADER[0] || data[1] != HEADER[1]) return null
        if ((data[3].toInt() and 0xFF) != data.size - 5) return null
        if (Crc8Maxim.checksum(data.copyOf(data.lastIndex)).toByte() != data.last()) return null

        val cmd = data[2].toInt() and 0xFF
        return when (cmd) {
            0x01, RSP_BLOOD -> if (data.size >= 9 && (data[3].toInt() and 0xFF) >= 4) {
                Packet.Realtime(u16(data, 4), u16(data, 6) / 10.0f)
            } else null

            0x03 -> if (data.size >= 5 && (data[3].toInt() and 0xFF) >= 1) {
                Packet.HistoryHeader(data[4].toInt() and 0xFF)
            } else null

            RSP_HISTORY -> {
                if (data.size < 11 || (data[3].toInt() and 0xFF) < 6) return null
                val byteCount = data.lastIndex - 8
                if (byteCount <= 0 || byteCount % 2 != 0) return null
                val values = (8 until data.lastIndex step 2).map { u16(data, it) / 10.0f }
                Packet.HistoryPayload(
                    bloodDataLength = data[3].toInt() and 0xFF,
                    startSequenceId = u16(data, 4),
                    duration = u16(data, 6),
                    values = values,
                )
            }
            0x02, RSP_BATTERY -> if (hasBytes(data, 1)) Packet.Battery(data[4].toInt() and 0xFF) else null
            0x06, RSP_STATUS -> if (hasBytes(data, 1)) Packet.Status(data[4].toInt() and 0xFF) else null
            0x07 -> if (hasBytes(data, 1)) Packet.SnAuth((data[4].toInt() and 0xFF) == 1) else null
            RSP_HANDSHAKE -> if (hasBytes(data, 2)) {
                Packet.Handshake(data[4].toInt() and 0xFF, data[5].toInt() and 0xFF)
            } else Packet.Simple(cmd)
            0x08, RSP_TEMPERATURE -> if (hasBytes(data, 1)) Packet.Temperature(data[4].toInt() and 0xFF) else null
            0x0A -> if (hasBytes(data, 2)) Packet.Duration(u16(data, 4)) else null
            else -> Packet.Simple(cmd)
        }
    }
}
