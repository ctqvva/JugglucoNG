package tk.glucodata.drivers.sibionics

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.zip.CRC32

internal data class SibionicsSourceSample(
    val index: Int,
    val timestampMs: Long,
    val rawMmol: Float,
    val temperatureC: Float,
    val impedance: Float,
    val variantId: Int,
)

/**
 * Durable source data for deterministic local algorithm rebuilds.
 *
 * Room/native history only retains the rendered glucose value. Replaying the
 * Sibionics model also needs the original current, temperature, impedance and
 * sensor index, so those immutable inputs are kept in a small append-only file.
 */
internal class SibionicsSampleJournal(
    private val file: File,
) {
    private val samples = TreeMap<Int, SibionicsSourceSample>()
    private var loaded = false
    private var recordCount = 0

    @Synchronized
    fun append(sample: SibionicsSourceSample): Boolean = appendAll(listOf(sample)) > 0

    @Synchronized
    fun appendAll(incoming: Collection<SibionicsSourceSample>): Int {
        ensureLoaded()
        val changed = incoming.mapNotNull { sample ->
            if (!isValid(sample)) return@mapNotNull null
            val previous = samples[sample.index]
            val normalized = if (previous == null) sample else sample.copy(timestampMs = previous.timestampMs)
            normalized.takeIf { it != previous }
        }
        if (changed.isEmpty()) return 0

        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                changed.forEach { writeRecord(output, it) }
                output.flush()
                stream.fd.sync()
            }
        }
        changed.forEach { samples[it.index] = it }
        recordCount += changed.size
        if (recordCount > samples.size * 2 + COMPACT_SLACK_RECORDS) compact()
        return changed.size
    }

    @Synchronized
    fun snapshot(): List<SibionicsSourceSample> {
        ensureLoaded()
        return samples.values.toList()
    }

    @Synchronized
    fun hasContiguousBeginning(): Boolean {
        ensureLoaded()
        if (samples.isEmpty()) return false
        val indices = samples.keys.iterator()
        var expected = indices.next()
        if (expected !in 0..1) return false
        while (indices.hasNext()) {
            val index = indices.next()
            if (index != expected + 1) return false
            expected = index
        }
        return true
    }

    /**
     * First index in `[from, untilExclusive)` the journal does not hold, or null
     * when that whole range is present. A backfill asks the sensor for this page
     * next; it is cheap enough to re-evaluate after every batch.
     */
    @Synchronized
    fun firstMissingIndex(from: Int, untilExclusive: Int): Int? {
        ensureLoaded()
        var index = from.coerceAtLeast(1)
        while (index < untilExclusive) {
            if (!samples.containsKey(index)) return index
            index++
        }
        return null
    }

    @Synchronized
    fun clear() {
        samples.clear()
        recordCount = 0
        loaded = true
        if (file.exists() && !file.delete()) {
            FileOutputStream(file, false).use { it.fd.sync() }
        }
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return

        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            while (true) {
                val record = try {
                    readRecord(input)
                } catch (_: EOFException) {
                    break
                }
                if (record == null) break
                recordCount++
                if (isValid(record)) samples[record.index] = record
            }
        }
        val validLength = recordCount.toLong() * RECORD_SIZE
        if (file.length() != validLength) {
            RandomAccessFile(file, "rw").use { it.setLength(validLength) }
        }
        if (recordCount > samples.size * 2 + COMPACT_SLACK_RECORDS) compact()
    }

    private fun compact() {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary, false).use { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                samples.values.forEach { writeRecord(output, it) }
                output.flush()
                stream.fd.sync()
            }
        }
        if (temporary.renameTo(file)) {
            recordCount = samples.size
        } else {
            temporary.delete()
        }
    }

    private fun readRecord(input: DataInputStream): SibionicsSourceSample? {
        val payload = ByteArray(PAYLOAD_SIZE)
        input.readFully(payload)
        val storedCrc = input.readInt()
        val computedCrc = CRC32().apply { update(payload) }.value.toInt()
        if (storedCrc != computedCrc) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC || buffer.int != VERSION) return null
        return SibionicsSourceSample(
            index = buffer.int,
            timestampMs = buffer.long,
            rawMmol = buffer.float,
            temperatureC = buffer.float,
            impedance = buffer.float,
            variantId = buffer.int,
        )
    }

    private fun writeRecord(output: DataOutputStream, sample: SibionicsSourceSample) {
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putInt(sample.index)
            .putLong(sample.timestampMs)
            .putFloat(sample.rawMmol)
            .putFloat(sample.temperatureC)
            .putFloat(sample.impedance)
            .putInt(sample.variantId)
            .array()
        output.write(payload)
        output.writeInt(CRC32().apply { update(payload) }.value.toInt())
    }

    private fun isValid(sample: SibionicsSourceSample): Boolean =
        sample.index >= 0 && sample.timestampMs > 0L && sample.rawMmol.isFinite() &&
            sample.temperatureC.isFinite() && sample.impedance.isFinite() && sample.variantId >= 0

    private companion object {
        private const val MAGIC = 0x534A_5231 // SJR1
        private const val VERSION = 1
        private const val PAYLOAD_SIZE = 36
        private const val RECORD_SIZE = PAYLOAD_SIZE + 4
        private const val COMPACT_SLACK_RECORDS = 64
    }
}
