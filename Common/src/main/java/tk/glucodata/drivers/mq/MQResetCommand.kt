package tk.glucodata.drivers.mq

/** Protocol02's reset bytes come from the official-app capture, not an inferred CRC XOR. */
internal object MQResetCommand {
    fun isProtocol02Marker(frame: MQFrame): Boolean = frame.raw.contentEquals(
        byteArrayOf(0x5A, 0xA5.toByte(), 0x05, 0x01, 0x00, 0x51, 0x4F),
    ) || frame.raw.contentEquals(
        byteArrayOf(0x5A, 0xA5.toByte(), 0x06, 0x01, 0x00, 0x91.toByte(), 0x7E),
    )

    fun build(protocolType: Int, observedProtocol02: Boolean): ByteArray =
        if (protocolType == 2 || observedProtocol02) {
            byteArrayOf(0x5A, 0xA5.toByte(), 0x11, 0x01, 0x00, 0xAA.toByte(), 0x07)
        } else {
            MQParser.buildConfirmReset(0)
        }
}
