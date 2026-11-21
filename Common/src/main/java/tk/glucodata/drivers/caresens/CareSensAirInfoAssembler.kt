// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

import java.nio.ByteBuffer

/**
 * Collects one C2 transfer. Use a fresh instance for each request and connection.
 * CRC validation covers the wire bytes. Mapping the continuation chunks to the calibration
 * structure still requires captures for each supported library version.
 */
internal class CareSensAirInfoAssembler {
    private val payload = ByteArray(TRANSFER_BYTES)
    private val continuationSizes = mutableListOf<Int>()
    private var written = 0
    private var headerPayloadSize = 0
    private var closed = false

    fun accept(frame: ByteArray): Result {
        if (closed) return Result.Rejected(Failure.CLOSED)
        return when {
            frame.hasHeader(0xC2, 1) -> {
                if (written != 0) reject(Failure.OUT_OF_ORDER)
                else if (frame.size !in (HEADER_BYTES + 2)..(TRANSFER_BYTES + 2)) reject(Failure.FRAME_SIZE)
                else {
                    headerPayloadSize = frame.size - 2
                    append(frame)
                }
            }
            frame.hasHeader(0xC2, 2) -> {
                if (written == 0) reject(Failure.OUT_OF_ORDER)
                else if (frame.size == 2 || frame.size - 2 > payload.size - written) {
                    reject(Failure.FRAME_SIZE)
                } else {
                    continuationSizes += frame.size - 2
                    append(frame)
                }
            }
            frame.hasHeader(0xC2, 3) -> finish(frame)
            else -> reject(Failure.UNEXPECTED_FRAME)
        }
    }

    private fun append(frame: ByteArray): Result {
        frame.copyInto(payload, destinationOffset = written, startIndex = 2)
        written += frame.size - 2
        return Result.Pending
    }

    private fun finish(terminator: ByteArray): Result {
        if (continuationSizes.isEmpty()) return reject(Failure.INCOMPLETE)

        // The legacy buffer held up to 724 bytes plus the big-endian CRC. A shorter
        // transfer can still provide the CRC after its data or in a two-byte terminator.
        val inlineOffset = written - CRC_BYTES
        val inlineMatches = inlineOffset >= headerPayloadSize &&
            crc16(payload, inlineOffset) == crcWord(payload, inlineOffset)
        val terminalMatches = terminator.size == 4 && written <= TRANSFER_BYTES - CRC_BYTES &&
            crc16(payload, written) == crcWord(terminator, 2)
        val dataSize = when {
            inlineMatches && !terminalMatches -> inlineOffset
            terminalMatches && !inlineMatches -> written
            else -> return reject(Failure.CHECKSUM)
        }

        val header = CareSensAirFactoryHeader.decode(payload)
        if (header == null) return reject(Failure.INVALID_HEADER)
        closed = true
        val sizes = continuationSizes.toIntArray()
        var remaining = dataSize - headerPayloadSize
        for (index in sizes.indices) {
            sizes[index] = minOf(sizes[index], remaining)
            remaining -= sizes[index]
        }
        return Result.Verified(
            header,
            if (inlineMatches) payload.copyOf(written) else payload.copyOf(written) + terminator.copyOfRange(2, 4),
            headerPayloadSize,
            sizes,
        )
    }

    private fun reject(failure: Failure): Result.Rejected {
        closed = true
        return Result.Rejected(failure)
    }

    sealed interface Result {
        data object Pending : Result
        data class Rejected(val failure: Failure) : Result

        class Verified(
            val header: CareSensAirFactoryHeader,
            private val payload: ByteArray,
            val headerPayloadSize: Int,
            private val continuationSizes: IntArray,
        ) : Result {
            fun payloadWithChecksum(): ByteArray = payload.copyOf()
            fun continuationDataSizes(): IntArray = continuationSizes.copyOf()
        }
    }

    enum class Failure {
        CLOSED, OUT_OF_ORDER, FRAME_SIZE, UNEXPECTED_FRAME, INCOMPLETE, CHECKSUM, INVALID_HEADER,
    }

    private companion object {
        const val HEADER_BYTES = 72
        const val TRANSFER_BYTES = 726
        const val CRC_BYTES = 2

        fun crcWord(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        // Both legacy implementations are CRC-16/CCITT-FALSE. The first uses a bytewise
        // update and the second expresses the same update as eight polynomial steps.
        fun crc16(bytes: ByteArray, length: Int): Int {
            var crc = 0xFFFF
            for (index in 0 until length) {
                val swapped = ((crc shl 8) or (crc ushr 8)) and 0xFFFF
                val mixed = swapped xor (bytes[index].toInt() and 0xFF)
                val folded = mixed xor ((mixed and 0xFF) ushr 4)
                crc = (folded xor (folded shl 12) xor ((folded and 0xFF) shl 5)) and 0xFFFF
            }
            return crc
        }
    }

}

/** The packed SensorInfo header from air/java.cpp, in on-wire units. */
internal class CareSensAirFactoryHeader private constructor(
    val sensorVersion: Int,
    val ycept: Float,
    val slope100: Float,
    val slope: Float,
    val r2: Float,
    val t90: Float,
    val slopeRatio: Float,
    val lot: String,
    val sensorId: String,
    val expiry: String,
    val stabilizationInterval: Int,
    val cgmDataInterval: Int,
    val bleAdvInterval: Int,
    val bleAdvDuration: Int,
    val age: Int,
    val allowedList: Int,
    val maxGlucose: Float,
    val minGlucose: Float,
    val libraryVersion: Int,
) {
    companion object {
        fun decode(payload: ByteArray): CareSensAirFactoryHeader? {
            if (payload.size < 72) return null
            val bytes = payload.littleEndian()
            val header = CareSensAirFactoryHeader(
                sensorVersion = bytes.u8(0),
                ycept = bytes.getFloat(1),
                slope100 = bytes.getFloat(5),
                slope = bytes.getFloat(9),
                r2 = bytes.getFloat(13),
                t90 = bytes.getFloat(17),
                slopeRatio = bytes.getFloat(21),
                lot = bytes.asciiField(25, 10),
                sensorId = bytes.asciiField(35, 12),
                expiry = bytes.asciiField(47, 6),
                stabilizationInterval = bytes.u16(53),
                cgmDataInterval = bytes.u16(55),
                bleAdvInterval = bytes.u16(57),
                bleAdvDuration = bytes.u8(59),
                age = bytes.u8(60),
                allowedList = bytes.u16(61),
                maxGlucose = bytes.getFloat(63),
                minGlucose = bytes.getFloat(67),
                libraryVersion = bytes.u8(71),
            )
            return header.takeIf {
                it.ycept.isFinite() && it.slope100.isFinite() && it.slope.isFinite() &&
                    it.r2.isFinite() && it.t90.isFinite() && it.slopeRatio.isFinite() &&
                    it.maxGlucose.isFinite() && it.minGlucose.isFinite()
            }
        }
    }
}

private fun ByteBuffer.asciiField(offset: Int, size: Int): String =
    String(array(), offset, size, Charsets.US_ASCII).trimEnd('\u0000')
