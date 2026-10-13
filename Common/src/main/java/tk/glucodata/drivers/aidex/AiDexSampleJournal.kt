package tk.glucodata.drivers.aidex

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

internal data class AiDexSourceSample(
    val timestampMs: Long,
    val sessionStartMs: Long,
    val factoryMgDl: Float,
    val rawMgDl: Float,
    val temperatureC: Float = Float.NaN,
    /** Factory inputs only: bootstrap < broadcast < matched history < direct live. */
    val origin: Int = HISTORY,
) {
    val minute: Long get() = timestampMs / 60_000L
    companion object {
        const val BOOTSTRAP = 0
        const val BROADCAST = 1
        const val HISTORY = 2
        const val LIVE = 3
    }
}

/** Original factory/raw inputs are durable before any derived value is published. */
internal class AiDexSampleJournal(private val file: File) {
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }
    private var loadedLength = -1L
    private val samples = TreeMap<Long, AiDexSourceSample>()

    fun snapshot(): List<AiDexSourceSample> = synchronized(lock) { load(); samples.values.toList() }
    fun first(): AiDexSourceSample? = synchronized(lock) { load(); samples.firstEntry()?.value }
    fun last(): AiDexSourceSample? = synchronized(lock) { load(); samples.lastEntry()?.value }
    fun atMinute(minute: Long): AiDexSourceSample? = synchronized(lock) { load(); samples[minute] }

    fun appendAll(incoming: Collection<AiDexSourceSample>): Int = synchronized(lock) {
        load()
        val changed = TreeMap<Long, AiDexSourceSample>()
        incoming.forEach { sample ->
            require(valid(sample)) { "Invalid AiDex source sample" }
            val previous = changed[sample.minute] ?: samples[sample.minute]
            if (previous == null || sample.origin >= previous.origin) {
                if (sample != previous) changed[sample.minute] = sample
            }
        }
        if (changed.isEmpty()) return@synchronized 0
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        FileOutputStream(file, true).use { stream ->
            val output = DataOutputStream(stream)
            changed.values.forEach { output.write(encode(it)) }
            output.flush()
            stream.fd.sync()
        }
        samples.putAll(changed)
        loadedLength = file.length()
        if (loadedLength > (samples.size * 2L + 256) * RECORD_BYTES) compact()
        changed.size
    }

    fun bytes(): ByteArray = synchronized(lock) { load(); file.takeIf { it.exists() }?.readBytes() ?: ByteArray(0) }

    private fun load() {
        val length = if (file.exists()) file.length() else 0L
        if (loadedLength == length) return
        require(length <= MAX_BYTES) { "AiDex source journal is too large" }
        val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
        val complete = bytes.size / RECORD_BYTES * RECORD_BYTES
        val decoded = decode(bytes.copyOf(complete))
        samples.clear()
        decoded.forEach { sample ->
            val held = samples[sample.minute]
            if (held == null || sample.origin >= held.origin) samples[sample.minute] = sample
        }
        // A torn append cannot have been followed by a derived write (append is
        // fsynced first). Full-record corruption fails closed rather than losing
        // original factory data and later treating derived history as factory.
        if (complete != bytes.size) RandomAccessFile(file, "rw").use { it.setLength(complete.toLong()); it.fd.sync() }
        loadedLength = complete.toLong()
    }

    private fun compact() {
        val staging = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(staging).use { stream ->
            samples.values.forEach { stream.write(encode(it)) }
            stream.flush()
            stream.fd.sync()
        }
        if (!staging.renameTo(file)) throw IOException("Cannot replace AiDex source journal")
        loadedLength = file.length()
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val MAGIC = 0x4144_5331
        private const val VERSION = 1
        private const val PAYLOAD_BYTES = 40
        private const val RECORD_BYTES = PAYLOAD_BYTES + 4
        private const val MAX_BYTES = 64 * 1024 * 1024

        private fun valid(s: AiDexSourceSample): Boolean = s.sessionStartMs > 0 &&
            s.timestampMs >= s.sessionStartMs && s.factoryMgDl.isFinite() && s.factoryMgDl in 1f..1022f &&
            (s.rawMgDl.isNaN() || (s.rawMgDl.isFinite() && s.rawMgDl in 0f..6553.5f)) &&
            (s.temperatureC.isNaN() || s.temperatureC.isFinite()) && s.origin in 0..3

        private fun encode(s: AiDexSourceSample): ByteArray {
            val payload = ByteBuffer.allocate(PAYLOAD_BYTES).putInt(MAGIC).putInt(VERSION)
                .putLong(s.timestampMs).putLong(s.sessionStartMs).putFloat(s.factoryMgDl)
                .putFloat(s.rawMgDl).putFloat(s.temperatureC).putInt(s.origin).array()
            return ByteBuffer.allocate(RECORD_BYTES).put(payload).putInt(CRC32().apply { update(payload) }.value.toInt()).array()
        }

        fun decode(bytes: ByteArray): List<AiDexSourceSample> {
            require(bytes.size <= MAX_BYTES && bytes.size % RECORD_BYTES == 0) { "Incomplete AiDex source journal" }
            val result = ArrayList<AiDexSourceSample>(bytes.size / RECORD_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                repeat(bytes.size / RECORD_BYTES) {
                    val payload = ByteArray(PAYLOAD_BYTES)
                    input.readFully(payload)
                    require(input.readInt() == CRC32().apply { update(payload) }.value.toInt()) { "Corrupt AiDex source journal" }
                    val b = ByteBuffer.wrap(payload)
                    require(b.int == MAGIC && b.int == VERSION) { "Foreign AiDex source journal" }
                    val sample = AiDexSourceSample(b.long, b.long, b.float, b.float, b.float, b.int)
                    require(valid(sample)) { "Invalid AiDex journal record" }
                    result += sample
                }
            }
            return result
        }
    }
}
