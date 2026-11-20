package tk.glucodata.drivers.libre2

import tk.glucodata.drivers.libre2.Libre2Gen2Crypto.KEY_BYTES

/** What an NFC enable-streaming exchange leaves behind for later BLE logins. */
internal class Libre2Gen2Provisioning(
    val streamPrefix: ByteArray,
    val salt: ByteArray,
    val extra: ByteArray,
    /** Root slot as the sensor reported it; null for records written before it was stored. */
    val storedRootSlot: Int?,
) {
    /** Slot used for key derivation, or null when the stored value has no known roots. */
    val rootSlot: Int? get() = Libre2Gen2Protocol.usableRootSlot(storedRootSlot)

    /** The 12-byte record the native sensor file keeps (`streamingAuthenticationData`). */
    fun toBytes(): ByteArray {
        val slot = storedRootSlot ?: return streamPrefix + salt + extra
        return streamPrefix + salt + extra + byteArrayOf(slot.toByte(), (slot ushr 8).toByte())
    }

    companion object {
        const val LEGACY_BYTES = 10
        const val BYTES = 12

        /** Accepts the 10-byte record (no root slot) and the 12-byte one; anything else is refused. */
        fun parse(record: ByteArray?): Libre2Gen2Provisioning? {
            if (record == null || (record.size != LEGACY_BYTES && record.size != BYTES)) return null
            val slot = if (record.size == BYTES) {
                (record[10].toInt() and 0xff) or ((record[11].toInt() and 0xff) shl 8)
            } else null
            return Libre2Gen2Provisioning(
                record.copyOfRange(0, 4), record.copyOfRange(4, 7), record.copyOfRange(7, 10), slot,
            )
        }
    }
}

internal object Libre2Gen2Protocol {
    const val RANDOM_BYTES = 7
    const val AUTH_REQUEST_BYTES = 19
    const val NFC_CHALLENGE_BYTES = 25
    const val BLE_CHALLENGE_BYTES = 14
    const val BLE_LOGIN_REPLY_BYTES = 25
    const val BLE_FRAME_BYTES = 46
    const val BLOCK_BYTES = 8
    /** Framing in front of the encrypted part of an NFC reply, after the status byte. */
    const val NFC_REPLY_HEADER_BYTES = 8

    const val CMD_ACTIVATE = 0x1b
    const val CMD_ENABLE_STREAMING = 0x1e
    const val CMD_AUTHENTICATE = 0x1f
    const val CMD_READ_CHALLENGE = 0x20
    const val CMD_READ_BLOCKS = 0x21
    const val CMD_READ_ROOT_SLOT = 0x22

    /**
     * Roots exist for slots 1 and 2. A missing slot (10-byte record) and the
     * values 0 and 0xFFFF, which the earlier vendor-library path used for "no
     * slot", fall back to slot 1. Any other value has no known roots.
     */
    fun usableRootSlot(stored: Int?): Int? = when (stored) {
        null, 0, 0xffff, 1 -> 1
        2 -> 2
        else -> null
    }

    fun authNonce(salt: ByteArray, counter: Int, ble: Boolean, step: Int): ByteArray {
        val nonce = ByteArray(8)
        nonce[1] = salt[0]
        nonce[2] = salt[1]
        nonce[3] = ((salt[2].toInt() and 0x7f) or (if (ble) 0x80 else 0)).toByte()
        Libre2Gen2Crypto.store32(nonce, 4, counter + step)
        return nonce
    }
}

/**
 * One challenge/response exchange with a sensor, over NFC or BLE. Holds the
 * sensor key and, after [acceptAuthentication], the session key. Not thread
 * safe; callers confine it to one NFC transaction or one GATT link.
 */
internal class Libre2Gen2Exchange private constructor(
    val rootSlot: Int,
    private val sensorKey: ByteArray,
    private val ble: Boolean,
) {
    private var sensorRandom = ByteArray(Libre2Gen2Protocol.RANDOM_BYTES)
    private var hostRandom = ByteArray(Libre2Gen2Protocol.RANDOM_BYTES)
    private var counter = 0
    var salt = ByteArray(3); private set
    var streamPrefix = ByteArray(4); private set
    private var nfcEpoch = ByteArray(2)
    private var requested = false
    private var sessionKey: ByteArray? = null

    val authenticated: Boolean get() = sessionKey != null

    /**
     * NFC: `02 A1 07 cmd || 15 encrypted bytes`. Returns null when the
     * 25-byte challenge fails its CRC.
     */
    fun buildNfcRequest(command: Int, challenge: ByteArray, random: ByteArray): ByteArray? {
        check(!ble)
        if (challenge.size != Libre2Gen2Protocol.NFC_CHALLENGE_BYTES || random.size != Libre2Gen2Protocol.RANDOM_BYTES) return null
        if (!Libre2Gen2Crypto.matchesCrc(challenge, 0, 23)) return null
        sensorRandom = challenge.copyOfRange(0, 7)
        counter = Libre2Gen2Crypto.load32(challenge, 7)
        salt = challenge.copyOfRange(14, 17)
        streamPrefix = challenge.copyOfRange(17, 21)
        nfcEpoch = challenge.copyOfRange(21, 23)
        hostRandom = random.copyOf()
        sessionKey = null
        val plain = random + byteArrayOf(command.inv().toByte()) + sensorRandom
        val encrypted = Libre2Gen2Crypto.cryptCtr(sensorKey, nonce(0), plain)
        requested = true
        return byteArrayOf(2, 0xa1.toByte(), 7, command.toByte()) + encrypted
    }

    /** BLE login: `1F || 18 encrypted bytes` answering the 14-byte challenge. */
    fun buildBleRequest(provisioning: Libre2Gen2Provisioning, challenge: ByteArray, random: ByteArray): ByteArray? {
        check(ble)
        if (challenge.size != Libre2Gen2Protocol.BLE_CHALLENGE_BYTES || random.size != Libre2Gen2Protocol.RANDOM_BYTES) return null
        streamPrefix = provisioning.streamPrefix.copyOf()
        salt = provisioning.salt.copyOf()
        sensorRandom = challenge.copyOfRange(0, 7)
        counter = Libre2Gen2Crypto.load32(challenge, 7)
        hostRandom = random.copyOf()
        sessionKey = null
        val command = Libre2Gen2Protocol.CMD_AUTHENTICATE
        val plain = random + byteArrayOf(command.inv().toByte()) + provisioning.extra + sensorRandom
        val encrypted = Libre2Gen2Crypto.cryptCtr(sensorKey, nonce(0), plain)
        requested = true
        return byteArrayOf(command.toByte()) + encrypted
    }

    /**
     * Checks the sensor's answer to an authenticate command and derives the
     * session key from it. [response] excludes the NFC status byte. Over BLE
     * the two trailing bytes are not covered by any check known to us.
     */
    fun acceptAuthentication(response: ByteArray): Boolean {
        val header = if (ble) 0 else Libre2Gen2Protocol.NFC_REPLY_HEADER_BYTES
        if (!requested || response.size != header + 23 + 2) return false
        if (!ble && !Libre2Gen2Crypto.matchesCrc(response, header, 23)) return false
        val plain = Libre2Gen2Crypto.cryptCtr(sensorKey, nonce(1), response, header, 23)
        if (!plain.rangeEquals(0, hostRandom)) return false
        sessionKey = Libre2Gen2Crypto.deriveSessionKey(rootSlot, plain.copyOfRange(7, 23))
        requested = false
        return sessionKey != null
    }

    /**
     * Decrypts the answer to an activate or enable-streaming command and
     * returns its payload once both random echoes match. [response] excludes
     * the NFC status byte.
     */
    fun readNfcCommandReply(response: ByteArray, payloadSize: Int): ByteArray? {
        check(!ble)
        val header = Libre2Gen2Protocol.NFC_REPLY_HEADER_BYTES
        val encryptedSize = payloadSize + 2 * Libre2Gen2Protocol.RANDOM_BYTES
        if (!requested || response.size != header + encryptedSize + 2) return null
        if (!Libre2Gen2Crypto.matchesCrc(response, header, encryptedSize)) return null
        val plain = Libre2Gen2Crypto.cryptCtr(sensorKey, nonce(1), response, header, encryptedSize)
        if (!plain.rangeEquals(0, sensorRandom) ||
            !plain.rangeEquals(Libre2Gen2Protocol.RANDOM_BYTES + payloadSize, hostRandom)
        ) return null
        requested = false
        return plain.copyOfRange(Libre2Gen2Protocol.RANDOM_BYTES, Libre2Gen2Protocol.RANDOM_BYTES + payloadSize)
    }

    /** Decrypts whole 8-byte FRAM blocks read with the read-blocks command. */
    fun decryptNfcBlocks(firstBlock: Int, encrypted: ByteArray): ByteArray? {
        check(!ble)
        val key = sessionKey ?: return null
        if (encrypted.isEmpty() || encrypted.size % Libre2Gen2Protocol.BLOCK_BYTES != 0) return null
        val nonce = ByteArray(8)
        streamPrefix.copyInto(nonce, 2)
        nonce[5] = (nonce[5].toInt() and 0x7f).toByte()
        nfcEpoch.copyInto(nonce, 6)
        return Libre2Gen2Crypto.cryptBlocks(key, nonce, firstBlock, encrypted)
    }

    /**
     * Decrypts one 46-byte BLE data frame; the two-byte sequence stays in
     * clear. The result is raw sensor data, not a glucose value.
     */
    fun decryptBleFrame(frame: ByteArray): ByteArray? {
        check(ble)
        val key = sessionKey ?: return null
        if (frame.size != Libre2Gen2Protocol.BLE_FRAME_BYTES) return null
        val nonce = ByteArray(8)
        streamPrefix.copyInto(nonce, 2)
        nonce[5] = (nonce[5].toInt() or 0x80).toByte()
        nonce[6] = frame[0]
        nonce[7] = frame[1]
        return byteArrayOf(frame[0], frame[1]) + Libre2Gen2Crypto.cryptCtr(key, nonce, frame, 2, frame.size - 2)
    }

    /** Wipes key material; the exchange is unusable afterwards. */
    fun close() {
        sensorKey.fill(0)
        sessionKey?.fill(0)
        sessionKey = null
        requested = false
    }

    private fun nonce(step: Int) = Libre2Gen2Protocol.authNonce(salt, counter, ble, step)

    private fun ByteArray.rangeEquals(offset: Int, other: ByteArray): Boolean {
        if (size < offset + other.size) return false
        var diff = 0
        for (i in other.indices) diff = diff or (this[offset + i].toInt() xor other[i].toInt())
        return diff == 0
    }

    companion object {
        /** Null when the slot has no known roots or the UID is too short. The key is derived fresh every time. */
        fun open(rootSlot: Int, uid: ByteArray, ble: Boolean): Libre2Gen2Exchange? {
            val key = Libre2Gen2Crypto.deriveSensorKey(rootSlot, uid) ?: return null
            check(key.size == KEY_BYTES)
            return Libre2Gen2Exchange(rootSlot, key, ble)
        }
    }
}
