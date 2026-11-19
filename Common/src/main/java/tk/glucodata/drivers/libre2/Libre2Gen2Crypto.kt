package tk.glucodata.drivers.libre2

/**
 * Libre 2 Gen2 primitives: Speck-64/128, the CMAC counter KDF, the CTR
 * keystream and the sensor's CRC-16. Pure functions with no Android
 * dependencies, so every value here is covered by JVM known-answer tests.
 *
 * Protocol facts come from Juggluco's `libre2_imp/gen2/protocol.c`
 * (GPL-3.0-or-later). None of this has been validated on a physical sensor.
 */
internal object Libre2Gen2Crypto {
    const val KEY_BYTES = 16
    private const val ROUNDS = 27

    private val SENSOR_ROOTS = arrayOf(
        hex("a1aa738f0cf75801a16dd4f7906e972a"),
        hex("3ce8bdbb8cb30a48a7d693f7b72eec6c"),
    )
    private val SESSION_ROOTS = arrayOf(
        hex("e82f8fc32199563cfda80c7f6c2a6721"),
        hex("4e2873bd16c54adce038ead5b25b68a3"),
    )

    // The terminating zero separates label and context in the KDF.
    private val SENSOR_LABEL = "ADC_*SpeckPatch#".toByteArray(Charsets.US_ASCII) + 0
    private val SESSION_LABEL = "ADC_*SpecKpatch#".toByteArray(Charsets.US_ASCII) + 0

    fun isSupportedRootSlot(rootSlot: Int): Boolean = rootSlot == 1 || rootSlot == 2

    /** Key that authenticates the phone to one sensor; context is the UID without its last two bytes. */
    fun deriveSensorKey(rootSlot: Int, uid: ByteArray): ByteArray? {
        if (!isSupportedRootSlot(rootSlot) || uid.size < 2) return null
        return deriveKey(SENSOR_ROOTS[rootSlot - 1], SENSOR_LABEL, uid.copyOf(uid.size - 2))
    }

    /** Key for FRAM blocks and BLE frames; context is 16 bytes of the sensor's decrypted reply. */
    fun deriveSessionKey(rootSlot: Int, context: ByteArray): ByteArray? {
        if (!isSupportedRootSlot(rootSlot) || context.size != 16) return null
        return deriveKey(SESSION_ROOTS[rootSlot - 1], SESSION_LABEL, context)
    }

    fun expandKey(key: ByteArray): IntArray {
        require(key.size == KEY_BYTES)
        var current = load32(key, 0)
        var oldest = load32(key, 4)
        var middle = load32(key, 8)
        var newest = load32(key, 12)
        val roundKeys = IntArray(ROUNDS)
        for (round in 0 until ROUNDS) {
            roundKeys[round] = current
            val next = (Integer.rotateRight(oldest, 8) + current) xor round
            current = Integer.rotateLeft(current, 3) xor next
            oldest = middle
            middle = newest
            newest = next
        }
        return roundKeys
    }

    fun encryptBlock(roundKeys: IntArray, input: ByteArray, inputOffset: Int = 0): ByteArray {
        var left = load32(input, inputOffset + 4)
        var right = load32(input, inputOffset)
        for (roundKey in roundKeys) {
            left = (Integer.rotateRight(left, 8) + right) xor roundKey
            right = Integer.rotateLeft(right, 3) xor left
        }
        val output = ByteArray(8)
        store32(output, 0, right)
        store32(output, 4, left)
        return output
    }

    /**
     * CTR over 8-byte blocks. The counter lives in nonce byte 0 and wraps
     * without carry. [nonce] is not modified.
     */
    fun cryptCtr(key: ByteArray, nonce: ByteArray, input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        require(nonce.size == 8)
        val roundKeys = expandKey(key)
        val counter = nonce.copyOf()
        val output = ByteArray(length)
        var done = 0
        while (done < length) {
            val keystream = encryptBlock(roundKeys, counter)
            val count = minOf(8, length - done)
            for (i in 0 until count) {
                output[done + i] = (input[offset + done + i].toInt() xor keystream[i].toInt()).toByte()
            }
            counter[0] = (counter[0] + 1).toByte()
            done += count
        }
        return output
    }

    /** One keystream block per 8-byte FRAM block; the block number sits in nonce byte 1. */
    fun cryptBlocks(key: ByteArray, nonce: ByteArray, firstBlock: Int, input: ByteArray): ByteArray {
        require(nonce.size == 8 && input.size % 8 == 0)
        val roundKeys = expandKey(key)
        val blockNonce = nonce.copyOf()
        val output = ByteArray(input.size)
        for (block in 0 until input.size / 8) {
            blockNonce[1] = (firstBlock + block).toByte()
            val keystream = encryptBlock(roundKeys, blockNonce)
            for (i in 0 until 8) {
                output[block * 8 + i] = (input[block * 8 + i].toInt() xor keystream[i].toInt()).toByte()
            }
        }
        return output
    }

    /**
     * Reflected 0x8408 CRC, initial 0xFFFF, one extra zero byte for an odd
     * length, result bit-reversed. Not interchangeable with CRC16-CCITT.
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xffff
        for (i in 0 until length) crc = crcByte(crc, data[offset + i].toInt() and 0xff)
        if (length and 1 == 1) crc = crcByte(crc, 0)
        var reflected = 0
        for (bit in 0 until 16) {
            reflected = (reflected shl 1) or (crc and 1)
            crc = crc ushr 1
        }
        return reflected
    }

    /** True when the two bytes after [payloadLength] hold its CRC, little-endian. */
    fun matchesCrc(frame: ByteArray, offset: Int, payloadLength: Int): Boolean {
        if (offset < 0 || frame.size < offset + payloadLength + 2) return false
        val stored = (frame[offset + payloadLength].toInt() and 0xff) or
            ((frame[offset + payloadLength + 1].toInt() and 0xff) shl 8)
        return crc16(frame, offset, payloadLength) == stored
    }

    private fun crcByte(crcIn: Int, byte: Int): Int {
        var crc = crcIn xor byte
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0x8408 else 0) }
        return crc
    }

    private fun deriveKey(root: ByteArray, label: ByteArray, context: ByteArray): ByteArray {
        val roundKeys = expandKey(root)
        val subkey = encryptBlock(roundKeys, ByteArray(8))
        doubleBlock(subkey)
        val outputBits = byteArrayOf(128.toByte(), 0)
        val messageLength = 1 + label.size + context.size + outputBits.size
        val partialLastBlock = messageLength % 8 != 0
        if (partialLastBlock) doubleBlock(subkey)

        val key = ByteArray(KEY_BYTES)
        for (counter in 1..2) {
            val message = byteArrayOf(counter.toByte()) + label + context + outputBits
            var block = ByteArray(8)
            var used = 0
            // The last block stays pending so it can take the subkey.
            for (byte in message) {
                if (used == 8) {
                    block = encryptBlock(roundKeys, block)
                    used = 0
                }
                block[used] = (block[used].toInt() xor byte.toInt()).toByte()
                used++
            }
            if (used < 8) block[used] = (block[used].toInt() xor 0x80).toByte()
            for (i in 0 until 8) block[i] = (block[i].toInt() xor subkey[i].toInt()).toByte()
            encryptBlock(roundKeys, block).copyInto(key, 8 * (counter - 1))
        }
        return key
    }

    private fun doubleBlock(block: ByteArray) {
        val carry = block[0].toInt() and 0x80 != 0
        for (i in 0 until 7) {
            block[i] = ((block[i].toInt() shl 1) or ((block[i + 1].toInt() and 0xff) ushr 7)).toByte()
        }
        block[7] = ((block[7].toInt() shl 1) xor (if (carry) 0x1b else 0)).toByte()
    }

    fun load32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    fun store32(bytes: ByteArray, offset: Int, word: Int) {
        for (i in 0 until 4) bytes[offset + i] = (word ushr (8 * i)).toByte()
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
