// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32

/** The model state is reconstructed from a small, fsynced raw log from sequence one. */
internal class CareSensAirJournal(private val directory: File, engineId: String, sensorId: String) {
    private val sessionBinding = sha256("$engineId\n$sensorId".toByteArray(Charsets.UTF_8))
    private val configFile = File(directory, "factory.v1")
    private val records = File(directory, "records.v1")

    class Config(val packed: ByteArray, val wire: ByteArray, val chunkSizes: IntArray) {
        val startSeconds: Long get() = packed.littleEndian().u32(CareSensAirFactoryConfig.START_OFFSET)
    }

    class Entry(val frame: ByteArray, val receivedSeconds: Long, val timestampSeconds: Long) {
        val record = requireNotNull(CareSensAirProtocol.parseRecord(frame))
        val hasSensorTimestamp: Boolean get() = timestampSeconds == record.sensorTimestampSeconds
    }

    fun loadConfig(): Config? {
        if (!configFile.exists()) {
            check(!records.exists()) { "CareSens Air factory configuration is missing" }
            return null
        }
        require(configFile.length() in (4L + HASH_BYTES * 2 + CareSensAirFactoryConfig.PACKED_BYTES + 8)..4600L)
        val all = configFile.readBytes()
        val payload = all.copyOfRange(0, all.size - HASH_BYTES)
        require(sha256(payload).contentEquals(all.copyOfRange(payload.size, all.size))) {
            "Corrupt CareSens Air factory configuration"
        }
        val bytes = ByteBuffer.wrap(payload)
        require(bytes.int == CONFIG_MAGIC)
        require(readBytes(bytes, HASH_BYTES).contentEquals(sessionBinding)) {
            "CareSens Air journal belongs to another sensor or engine"
        }
        val packed = readBytes(bytes, CareSensAirFactoryConfig.PACKED_BYTES)
        val wire = readBytes(bytes, bytes.int.also { require(it in 0..726) })
        val sizes = IntArray(bytes.int.also { require(it in 0..655) }) { bytes.int }
        require(!bytes.hasRemaining())
        return Config(packed, wire, sizes)
    }

    fun saveConfig(config: Config) {
        check(!configFile.exists() && !records.exists())
        val bytes = ByteBuffer.allocate(
            4 + HASH_BYTES + config.packed.size + 4 + config.wire.size + 4 + config.chunkSizes.size * 4,
        ).putInt(CONFIG_MAGIC).put(sessionBinding)
            .put(config.packed).putInt(config.wire.size).put(config.wire).putInt(config.chunkSizes.size)
        config.chunkSizes.forEach { bytes.putInt(it) }
        val payload = bytes.array()
        val temporary = File(directory, "factory.tmp")
        FileOutputStream(temporary).use {
            it.write(payload)
            it.write(sha256(payload))
            it.fd.sync()
        }
        Files.move(temporary.toPath(), configFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun replay(config: Config, consume: (Entry) -> Unit): Int {
        if (!records.exists()) return 0
        return RandomAccessFile(records, "rw").use { file ->
            val remaining = file.length() - HEADER_BYTES
            require(remaining in 0..(CareSensAirFactoryConfig.MAX_SEQUENCE * ENTRY_BYTES + ENTRY_BYTES - 1)) {
                "Invalid CareSens Air journal size"
            }
            require(file.readInt() == RECORD_MAGIC)
            val binding = ByteArray(HASH_BYTES).also { file.readFully(it) }
            require(configBinding(config).contentEquals(binding))
            val entries = (remaining / ENTRY_BYTES).toInt()
            val data = ByteArray(ENTRY_BYTES - 4)
            for (sequence in 1..entries) {
                file.readFully(data)
                require(file.readInt() == crc(data)) { "Corrupt CareSens Air record" }
                val bytes = ByteBuffer.wrap(data)
                val entry = Entry(readBytes(bytes, FRAME_BYTES), bytes.long, bytes.long)
                require(entry.record.sequence == sequence.toLong()) { "Non-contiguous CareSens Air journal" }
                consume(entry)
            }
            if (file.filePointer != file.length()) {
                // An interrupted append can leave a partial final record. The next request
                // asks for it again using the last complete, validated sequence.
                file.setLength(file.filePointer)
                file.fd.sync()
            }
            entries
        }
    }

    fun append(config: Config, expectedPrevious: Int, entry: Entry) {
        check(entry.record.sequence == expectedPrevious + 1L)
        if (expectedPrevious == 0 && !records.exists()) {
            val temporary = File(directory, "records.tmp")
            FileOutputStream(temporary).use {
                it.write(ByteBuffer.allocate(HEADER_BYTES).putInt(RECORD_MAGIC).put(configBinding(config)).array())
                it.fd.sync()
            }
            Files.move(temporary.toPath(), records.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        RandomAccessFile(records, "rw").use { file ->
            check(file.length() == HEADER_BYTES + expectedPrevious.toLong() * ENTRY_BYTES)
            val data = ByteBuffer.allocate(ENTRY_BYTES - 4).put(entry.frame)
                .putLong(entry.receivedSeconds).putLong(entry.timestampSeconds).array()
            file.seek(file.length())
            file.write(data)
            file.writeInt(crc(data))
            file.fd.sync()
        }
    }

    private fun configBinding(config: Config): ByteArray = sha256(sessionBinding + config.packed)

    private companion object {
        const val CONFIG_MAGIC = 0x43534631 // CSF1
        const val RECORD_MAGIC = 0x43535231 // CSR1
        const val HASH_BYTES = 32
        const val HEADER_BYTES = 4 + HASH_BYTES
        const val FRAME_BYTES = 84
        const val ENTRY_BYTES = FRAME_BYTES + 8 + 8 + 4

        fun readBytes(buffer: ByteBuffer, count: Int): ByteArray = ByteArray(count).also { buffer.get(it) }
        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
        fun crc(value: ByteArray): Int = CRC32().apply { update(value) }.value.toInt()
    }
}
