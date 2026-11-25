package tk.glucodata.drivers.libre2

/**
 * Cipher of the first Libre 2 generation (EU and other non-Gen2 sensors):
 * NFC activation and enable-streaming payloads, the BLE unlock payload and
 * BLE frame decryption. The construction is the publicly documented one
 * (LibreTools, xDrip, DiaBLE); the outputs match the vendor-library fixtures
 * kept next to the native code.
 */
internal object Libre2Gen1Crypto {
    private val KEY = intArrayOf(0xA0C5, 0x6860, 0x0000, 0x14C6)
    private const val SECRET = 0x1b6a
    private const val MASK = 0xffff
    const val CMD_ACTIVATE = 0x1b
    const val CMD_ENABLE_STREAMING = 0x1e
    const val BLE_FRAME_BYTES = 46
    const val UNLOCK_PAYLOAD_BYTES = 12
    /** The vendor counts sensor time in seconds from 2010-01-01 UTC. */
    const val EPOCH_OFFSET_SECONDS = 1262304000L

    private fun op(value: Int): Int {
        var result = value ushr 2
        if (value and 1 != 0) result = result xor KEY[1]
        if (value and 2 != 0) result = result xor KEY[0]
        return result
    }

    fun processCrypto(input: IntArray): IntArray {
        val r0 = op(input[0]) xor input[3]
        val r1 = op(r0) xor input[2]
        val r2 = op(r1) xor input[1]
        val r3 = op(r2) xor input[0]
        val r4 = op(r3)
        val r5 = op(r4 xor r0)
        val r6 = op(r5 xor r1)
        val r7 = op(r6 xor r2)
        return intArrayOf(r3 xor r7, r2 xor r6, r1 xor r5, r0 xor r4)
    }

    private fun word(bytes: ByteArray, high: Int, low: Int): Int =
        ((bytes[high].toInt() and 0xff) shl 8) or (bytes[low].toInt() and 0xff)

    private fun prepareVariables(uid: ByteArray, x: Int, y: Int): IntArray = intArrayOf(
        (word(uid, 5, 4) + x + y) and MASK,
        (word(uid, 3, 2) + KEY[2]) and MASK,
        (word(uid, 1, 0) + x * 2) and MASK,
        0x241a xor KEY[3],
    )

    private fun prepareVariables2(uid: ByteArray, i1: Int, i2: Int, i3: Int, i4: Int): IntArray = intArrayOf(
        (word(uid, 5, 4) + i1) and MASK,
        (word(uid, 3, 2) + i2) and MASK,
        (word(uid, 1, 0) + i3 + KEY[2]) and MASK,
        (i4 + KEY[3]) and MASK,
    )

    private fun usefulFunction(uid: ByteArray, x: Int, y: Int): ByteArray {
        val blockKey = processCrypto(prepareVariables(uid, x, y))
        val r1 = blockKey[0] xor 0x4163
        val r2 = blockKey[1] xor 0x4344
        return byteArrayOf(r1.toByte(), (r1 ushr 8).toByte(), r2.toByte(), (r2 ushr 8).toByte())
    }

    private fun le32(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun valid(uid: ByteArray, info: ByteArray? = null): Boolean =
        uid.size >= 6 && (info == null || info.size >= 6)

    /** NFC `A1 07` parameters that activate a new sensor. */
    fun activationPayload(uid: ByteArray): ByteArray? {
        if (!valid(uid)) return null
        return byteArrayOf(CMD_ACTIVATE.toByte()) + usefulFunction(uid, CMD_ACTIVATE, SECRET)
    }

    /**
     * NFC `A1 07` parameters that switch on BLE streaming. [enableTime] is
     * remembered by the sensor and is needed for every later BLE unlock.
     */
    fun enableStreamingPayload(uid: ByteArray, info: ByteArray, enableTime: Int): ByteArray? {
        if (!valid(uid, info)) return null
        val y = (enableTime and MASK) xor word(info, 5, 4)
        return byteArrayOf(CMD_ENABLE_STREAMING.toByte()) + le32(enableTime) +
            usefulFunction(uid, CMD_ENABLE_STREAMING, y)
    }

    /**
     * BLE login payload. [info] and [enableTime] are the patch info and time
     * used when streaming was enabled; [unlockCount] must grow on every use.
     */
    fun streamingUnlockPayload(uid: ByteArray, info: ByteArray, enableTime: Int, unlockCount: Int): ByteArray? {
        if (!valid(uid, info)) return null
        val b = le32(enableTime + unlockCount)
        val ad = usefulFunction(uid, CMD_ACTIVATE, SECRET)
        val ed = usefulFunction(uid, CMD_ENABLE_STREAMING, (enableTime and MASK) xor word(info, 5, 4))
        val t2 = processCrypto(
            prepareVariables2(
                uid,
                word(ed, 1, 0) xor word(b, 3, 2),
                word(ad, 1, 0),
                word(ed, 3, 2) xor word(b, 1, 0),
                word(ad, 3, 2),
            )
        )
        val t31 = crc(byteArrayOf(0xc1.toByte(), 0xc4.toByte(), 0xc3.toByte(), 0xc0.toByte(), 0xd4.toByte(), 0xe1.toByte(), 0xe7.toByte(), 0xba.toByte()) + words(t2[0]))
        val t32 = crc(words(t2[1], t2[2], t2[3]))
        val t33 = crc(ad + ed.copyOfRange(0, 2))
        val t34 = crc(ed.copyOfRange(2, 4) + b)
        val t4 = processCrypto(prepareVariables2(uid, t31, t32, t33, t34))
        return b + words(t4[0], t4[1], t4[2], t4[3])
    }

    /**
     * Decrypts a 46-byte BLE frame into 44 bytes, or null when the frame has
     * the wrong size or its checksum does not match.
     */
    fun decryptBle(uid: ByteArray, frame: ByteArray): ByteArray? {
        if (!valid(uid) || frame.size != BLE_FRAME_BYTES) return null
        val d = usefulFunction(uid, CMD_ACTIVATE, SECRET)
        val x = (word(d, 1, 0) xor word(d, 3, 2)) or 0x63
        val y = word(frame, 1, 0) xor 0x63
        var state = processCrypto(prepareVariables(uid, x, y))
        val result = ByteArray(BLE_FRAME_BYTES - 2)
        for (round in 0 until 8) {
            val keystream = words(state[0], state[1], state[2], state[3])
            for (i in 0 until 8) {
                val index = round * 8 + i
                if (index < result.size) {
                    result[index] = (frame[index + 2].toInt() xor keystream[i].toInt()).toByte()
                }
            }
            state = processCrypto(state)
        }
        if (!Libre2Gen2Crypto.matchesCrc(result, 0, 42)) return null
        return result
    }

    // Every checksum here covers an even number of bytes, where the Gen2
    // routine's odd-length padding does not apply.
    private fun crc(data: ByteArray): Int = Libre2Gen2Crypto.crc16(data)

    private fun words(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, value ->
            out[i * 2] = value.toByte()
            out[i * 2 + 1] = (value ushr 8).toByte()
        }
        return out
    }
}
