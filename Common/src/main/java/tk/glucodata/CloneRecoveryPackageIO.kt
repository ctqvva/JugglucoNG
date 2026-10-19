package tk.glucodata

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class CloneRecoveryPackageStats(
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val recordCounts: Map<String, Long>,
    val sha256: String,
)

internal data class CloneRecoveryRecord(
    val type: String,
    val payload: JSONObject,
)

internal object CloneRecoveryPackageIO {
    private const val RECORD_TYPE_KEY = "type"
    private const val RECORD_PAYLOAD_KEY = "payload"

    class RecordSink internal constructor(
        private val output: OutputStream,
        declaredRecordTypes: Set<String>,
    ) {
        private val counts = declaredRecordTypes
            .associateWithTo(linkedMapOf()) { 0L }

        var uncompressedBytes: Long = 0L
            private set

        init {
            require(counts.isNotEmpty()) { "Missing Clone recovery record categories" }
            counts.keys.forEach(CloneHistoryRecoveryProtocol::validateRecordName)
        }

        fun write(type: String, payload: JSONObject) {
            CloneHistoryRecoveryProtocol.validateRecordName(type)
            require(type in counts) { "Undeclared Clone recovery record category" }
            val envelope = JSONObject()
                .put(RECORD_TYPE_KEY, type)
                .put(RECORD_PAYLOAD_KEY, payload)
                .toString()
            val bytes = envelope.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= CloneHistoryRecoveryProtocol.MAXIMUM_RECORD_BYTES) {
                "Clone recovery record is too large"
            }
            val nextSize = uncompressedBytes + bytes.size + 1L
            require(nextSize <= CloneHistoryRecoveryProtocol.MAXIMUM_UNCOMPRESSED_BYTES) {
                "Clone recovery payload is too large"
            }
            output.write(bytes)
            output.write('\n'.code)
            uncompressedBytes = nextSize
            val previous = counts.getValue(type)
            require(previous < CloneHistoryRecoveryProtocol.MAXIMUM_RECORD_COUNT) {
                "Too many Clone recovery records"
            }
            counts[type] = previous + 1L
        }

        internal fun recordCounts(): Map<String, Long> = counts.toMap()
    }

    suspend fun write(
        file: File,
        declaredRecordTypes: Set<String>,
        block: suspend (RecordSink) -> Unit,
    ): CloneRecoveryPackageStats {
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Could not create Clone recovery staging directory"
        }
        var completed = false
        try {
            val bounded = BoundedCountingOutputStream(
                output = file.outputStream(),
                maximumBytes = CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES,
            )
            lateinit var sink: RecordSink
            GZIPOutputStream(bounded).use { gzip ->
                sink = RecordSink(gzip, declaredRecordTypes)
                block(sink)
            }
            return CloneRecoveryPackageStats(
                compressedBytes = bounded.count,
                uncompressedBytes = sink.uncompressedBytes,
                recordCounts = sink.recordCounts(),
                sha256 = CloneHistoryRecoveryProtocol.sha256(file),
            ).also { completed = true }
        } finally {
            if (!completed) file.delete()
        }
    }

    suspend fun validate(
        file: File,
        manifest: CloneRecoveryManifest,
        recordValidator: suspend (CloneRecoveryRecord) -> Unit = {},
    ) {
        verifyFile(file, manifest)
        scan(file, manifest, recordValidator)
    }

    suspend fun visitValidated(
        file: File,
        manifest: CloneRecoveryManifest,
        recordValidator: suspend (CloneRecoveryRecord) -> Unit = {},
        transaction: suspend (suspend () -> Unit) -> Unit = { operation -> operation() },
        beforeVisit: suspend () -> Unit = {},
        afterVisit: suspend () -> Unit = {},
        visitor: suspend (CloneRecoveryRecord) -> Unit,
    ) {
        validate(file, manifest, recordValidator)
        transaction {
            verifyFile(file, manifest)
            beforeVisit()
            scan(file, manifest, visitor)
            afterVisit()
        }
    }

    private fun verifyFile(file: File, manifest: CloneRecoveryManifest) {
        CloneHistoryRecoveryProtocol.validateManifest(manifest)
        require(file.isFile) { "Clone recovery package is missing" }
        require(file.length() == manifest.compressedBytes) {
            "Clone recovery package size does not match its manifest"
        }
        require(CloneHistoryRecoveryProtocol.sha256(file) == manifest.sha256) {
            "Clone recovery package digest does not match its manifest"
        }
    }

    private suspend fun scan(
        file: File,
        manifest: CloneRecoveryManifest,
        visitor: suspend (CloneRecoveryRecord) -> Unit,
    ) {
        val counts = manifest.recordCounts.keys.associateWithTo(linkedMapOf()) { 0L }
        var uncompressedBytes = 0L
        GZIPInputStream(file.inputStream().buffered()).buffered().use { input ->
            while (true) {
                val line = readLine(input) ?: break
                uncompressedBytes += line.consumedBytes
                require(uncompressedBytes <= manifest.uncompressedBytes) {
                    "Clone recovery payload exceeds its declared size"
                }
                val text = decodeUtf8(line.content)
                val envelope = JSONObject(text)
                require(envelope.length() == 2) { "Invalid Clone recovery record envelope" }
                val type = envelope.requireString(RECORD_TYPE_KEY)
                CloneHistoryRecoveryProtocol.validateRecordName(type)
                require(type in counts) { "Unexpected Clone recovery record category" }
                val payload = envelope.optJSONObject(RECORD_PAYLOAD_KEY)
                    ?: throw IllegalArgumentException("Invalid Clone recovery record payload")
                val previous = counts.getValue(type)
                require(previous < CloneHistoryRecoveryProtocol.MAXIMUM_RECORD_COUNT) {
                    "Too many Clone recovery records"
                }
                counts[type] = previous + 1L
                visitor(CloneRecoveryRecord(type, payload))
            }
        }
        require(uncompressedBytes == manifest.uncompressedBytes) {
            "Clone recovery payload size does not match its manifest"
        }
        require(counts == manifest.recordCounts) {
            "Clone recovery record counts do not match its manifest"
        }
    }

    private data class RecordLine(
        val content: ByteArray,
        val consumedBytes: Long,
    )

    private fun readLine(input: java.io.InputStream): RecordLine? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw IllegalArgumentException("Truncated Clone recovery record")
            }
            if (value == '\n'.code) {
                require(bytes.size() > 0) { "Empty Clone recovery record" }
                return RecordLine(bytes.toByteArray(), bytes.size().toLong() + 1L)
            }
            require(value != '\r'.code) { "Invalid Clone recovery record separator" }
            require(bytes.size() < CloneHistoryRecoveryProtocol.MAXIMUM_RECORD_BYTES) {
                "Clone recovery record is too large"
            }
            bytes.write(value)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8 in Clone recovery record", error)
    }

    private fun JSONObject.requireString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing Clone recovery record $name" }
        return (get(name) as? String)
            ?: throw IllegalArgumentException("Invalid Clone recovery record $name")
    }

    private class BoundedCountingOutputStream(
        output: OutputStream,
        private val maximumBytes: Long,
    ) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "Invalid Clone recovery output range"
            }
            ensureCapacity(length)
            out.write(buffer, offset, length)
            count += length
        }

        private fun ensureCapacity(additionalBytes: Int) {
            require(additionalBytes >= 0 && count <= maximumBytes - additionalBytes) {
                "Clone recovery package is too large"
            }
        }
    }
}
