package tk.glucodata.drivers.libre2

import tk.glucodata.drivers.libre2.Libre2Gen2Protocol.BLOCK_BYTES

/** Sends one ISO 15693 frame and returns the raw reply, status byte first, or null. */
internal fun interface Libre2NfcTransceiver {
    fun transceive(command: ByteArray): ByteArray?
}

/** Result of enabling BLE streaming over NFC. */
internal class Libre2Gen2StreamingGrant(val provisioning: Libre2Gen2Provisioning, val bleAddress: ByteArray)

/**
 * Libre 2 Gen2 NFC transactions. Every call reads a fresh root slot and
 * challenge, so no key or session outlives the transaction.
 *
 * [random] fills the seven host-random bytes; production passes SecureRandom.
 */
internal class Libre2Gen2Nfc(
    private val nfc: Libre2NfcTransceiver,
    private val random: (ByteArray) -> Unit,
) {
    /** Why the last call returned nothing; for logs and the PR test checklist. */
    var lastFailure: String? = null
        private set

    fun activate(uid: ByteArray): Boolean = withExchange(uid, Libre2Gen2Protocol.CMD_ACTIVATE) { exchange, reply ->
        exchange.readNfcCommandReply(reply, 5) != null || fail("activation reply rejected")
    } ?: false

    fun enableStreaming(uid: ByteArray): Libre2Gen2StreamingGrant? =
        withExchange(uid, Libre2Gen2Protocol.CMD_ENABLE_STREAMING) { exchange, reply ->
            val payload = exchange.readNfcCommandReply(reply, 9)
            if (payload == null) {
                fail("enable-streaming reply rejected")
                null
            } else {
                Libre2Gen2StreamingGrant(
                    Libre2Gen2Provisioning(
                        exchange.streamPrefix.copyOf(), exchange.salt.copyOf(),
                        payload.copyOfRange(6, 9), reportedRootSlot,
                    ),
                    payload.copyOfRange(0, 6),
                )
            }
        }

    /** Reads [length] decrypted FRAM bytes starting at byte [offset]. */
    fun readFram(uid: ByteArray, offset: Int, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        return withExchange(uid, Libre2Gen2Protocol.CMD_AUTHENTICATE) { exchange, reply ->
            if (!exchange.acceptAuthentication(reply)) {
                fail("FRAM authentication rejected")
                null
            } else {
                readBlocks(exchange, offset, length)
            }
        }
    }

    private var reportedRootSlot = 0

    private fun readBlocks(exchange: Libre2Gen2Exchange, offset: Int, length: Int): ByteArray? {
        val firstBlock = offset / BLOCK_BYTES
        val startInBlock = offset % BLOCK_BYTES
        val blockCount = (startInBlock + length + BLOCK_BYTES - 1) / BLOCK_BYTES
        if (firstBlock + blockCount > 256) return null
        val encrypted = ByteArray(blockCount * BLOCK_BYTES)
        var block = 0
        while (block < blockCount) {
            val count = minOf(3, blockCount - block)
            val bytes = count * BLOCK_BYTES
            val reply = nfc.transceive(
                byteArrayOf(2, 0xa1.toByte(), 7, Libre2Gen2Protocol.CMD_READ_BLOCKS.toByte(), (firstBlock + block).toByte(), (count - 1).toByte())
            )
            // Status byte plus the same 8 framing bytes as other replies.
            val header = 1 + Libre2Gen2Protocol.NFC_REPLY_HEADER_BYTES
            if (!good(reply) || reply!!.size < header + bytes) {
                fail("read blocks ${firstBlock + block} failed")
                return null
            }
            reply.copyInto(encrypted, block * BLOCK_BYTES, header, header + bytes)
            block += count
        }
        val fram = exchange.decryptNfcBlocks(firstBlock, encrypted) ?: return null
        return fram.copyOfRange(startInBlock, startInBlock + length)
    }

    private fun <T> withExchange(uid: ByteArray, command: Int, onReply: (Libre2Gen2Exchange, ByteArray) -> T?): T? {
        lastFailure = null
        if (uid.size < 8) {
            fail("short UID")
            return null
        }
        val slotReply = nfc.transceive(byteArrayOf(2, 0xa1.toByte(), uid[6], Libre2Gen2Protocol.CMD_READ_ROOT_SLOT.toByte()))
        if (!good(slotReply) || slotReply!!.size < 5) {
            fail("root slot read failed")
            return null
        }
        reportedRootSlot = (slotReply[3].toInt() and 0xff) or ((slotReply[4].toInt() and 0xff) shl 8)
        val rootSlot = Libre2Gen2Protocol.usableRootSlot(reportedRootSlot)
        if (rootSlot == null) {
            fail("unsupported root slot $reportedRootSlot")
            return null
        }
        val challengeReply = nfc.transceive(byteArrayOf(2, 0xa1.toByte(), uid[6], Libre2Gen2Protocol.CMD_READ_CHALLENGE.toByte()))
        if (!good(challengeReply)) {
            fail("challenge read failed")
            return null
        }
        val exchange = Libre2Gen2Exchange.open(rootSlot, uid, ble = false)
        if (exchange == null) {
            fail("no sensor key")
            return null
        }
        try {
            val hostRandom = ByteArray(Libre2Gen2Protocol.RANDOM_BYTES).also(random)
            val request = exchange.buildNfcRequest(command, challengeReply!!.copyOfRange(1, challengeReply.size), hostRandom)
            if (request == null) {
                fail("challenge rejected")
                return null
            }
            val reply = nfc.transceive(request)
            if (!good(reply)) {
                fail("command 0x${command.toString(16)} got no reply")
                return null
            }
            return onReply(exchange, reply!!.copyOfRange(1, reply.size))
        } finally {
            exchange.close()
        }
    }

    private fun good(reply: ByteArray?): Boolean = reply != null && reply.isNotEmpty() && (reply[0].toInt() and 1) == 0

    private fun fail(reason: String): Boolean {
        if (lastFailure == null) lastFailure = reason
        return false
    }
}
