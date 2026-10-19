// MQCrc16.kt — CRC16-Modbus (polynomial 0xA001, init 0xFFFF) for the
// MQ/Glutec wire protocol.
//
// Wire format: CRC is written high-byte-first (packet[n-2] = high, packet[n-1] = low).
//
// The firmware ships in two variants:
//   • Protocol01 — plain CRC16-Modbus.
//   • Protocol02 — Modbus ^ 0x0100 (single-bit XOR in the high byte).
//
// Protocol02 was observed empirically: the transmitter sent a 0x06 BEGIN_WORK
// frame with CRC 0x917E where Modbus computes 0x907E (diff 0x0100).  We match
// that offset on outgoing confirm frames; incoming frames accept either.

package tk.glucodata.drivers.mq

object MQCrc16 {

    /**
     * Compute CRC16-Modbus over [length] bytes of [data] starting at [offset].
     * Returns a 16-bit unsigned value packed into the low bits of an Int.
     */
    @JvmStatic
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        var i = 0
        while (i < length) {
            crc = crc xor (data[offset + i].toInt() and 0xFF)
            var j = 0
            while (j < 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                j++
            }
            i++
        }
        return crc and 0xFFFF
    }

    /**
     * Append the CRC16 of the payload (all bytes up to but not including the
     * trailing CRC placeholder) to the last two bytes of [frame]. The frame
     * must already have two reserved trailing bytes for the CRC.
     *
     * [xorOut] is applied to the computed CRC before stamping — 0x0000 for
     * vanilla Modbus (Protocol01), 0x0100 for Protocol02.
     */
    @JvmStatic
    @JvmOverloads
    fun stamp(frame: ByteArray, xorOut: Int = 0): ByteArray {
        require(frame.size >= 2) { "frame too small to hold CRC" }
        val crc = compute(frame, 0, frame.size - 2) xor (xorOut and 0xFFFF)
        frame[frame.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        frame[frame.size - 1] = (crc and 0xFF).toByte()
        return frame
    }

    /**
     * Validate the last two bytes of [frame] as the big-endian CRC16 of the
     * preceding bytes.
     */
    @JvmStatic
    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        val expected = ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or
            (frame[frame.size - 1].toInt() and 0xFF)
        return expected == compute(frame, 0, frame.size - 2)
    }
}
