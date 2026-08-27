package tk.glucodata.data

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class ExportCompression(
    val fileSuffix: String,
    val mimeType: String
) {
    GZIP(
        fileSuffix = ".json.gz",
        mimeType = "application/gzip"
    ),
    ZSTD(
        fileSuffix = ".json.zst",
        mimeType = "application/zstd"
    )
}

internal object ExportPackageCodec {
    private val gzipMagic = byteArrayOf(0x1f, 0x8b.toByte())
    private val zstdMagic = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())

    fun writeUtf8(
        output: OutputStream,
        text: String,
        compression: ExportCompression,
        zstdWorkers: Int = defaultZstdWorkers()
    ) {
        val compressed = when (compression) {
            ExportCompression.GZIP -> GZIPOutputStream(output)
            ExportCompression.ZSTD -> ZstdOutputStreamNoFinalizer(output)
                .setChecksum(true)
                .setWorkers(zstdWorkers.coerceAtLeast(1))
        }
        OutputStreamWriter(compressed, StandardCharsets.UTF_8).use { writer ->
            writer.write(text)
        }
    }

    fun writeJson(
        output: OutputStream,
        payload: JSONObject,
        compression: ExportCompression,
        zstdWorkers: Int = defaultZstdWorkers()
    ) {
        compressedWriter(output, compression, zstdWorkers).use { writer ->
            writeJsonValue(writer, payload)
        }
    }

    fun readUtf8(input: InputStream): String {
        val source = PushbackInputStream(input, zstdMagic.size)
        val header = ByteArray(zstdMagic.size)
        val count = source.read(header)
        if (count > 0) source.unread(header, 0, count)

        val decoded = when {
            header.startsWith(gzipMagic, count) -> GZIPInputStream(source)
            header.startsWith(zstdMagic, count) -> ZstdInputStreamNoFinalizer(source)
            else -> source
        }
        return decoded.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    internal fun defaultZstdWorkers(processors: Int = Runtime.getRuntime().availableProcessors()): Int {
        return (processors - 1).coerceIn(1, 4)
    }

    private fun compressedWriter(
        output: OutputStream,
        compression: ExportCompression,
        zstdWorkers: Int
    ): OutputStreamWriter {
        val compressed = when (compression) {
            ExportCompression.GZIP -> GZIPOutputStream(output)
            ExportCompression.ZSTD -> ZstdOutputStreamNoFinalizer(output)
                .setChecksum(true)
                .setWorkers(zstdWorkers.coerceAtLeast(1))
        }
        return OutputStreamWriter(compressed, StandardCharsets.UTF_8)
    }

    private fun writeJsonValue(writer: OutputStreamWriter, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> writer.write("null")
            is JSONObject -> {
                writer.write('{'.code)
                var first = true
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) writer.write(','.code)
                    first = false
                    writer.write(JSONObject.quote(key))
                    writer.write(':'.code)
                    writeJsonValue(writer, value.opt(key))
                }
                writer.write('}'.code)
            }
            is JSONArray -> {
                writer.write('['.code)
                for (index in 0 until value.length()) {
                    if (index > 0) writer.write(','.code)
                    writeJsonValue(writer, value.opt(index))
                }
                writer.write(']'.code)
            }
            is Boolean -> writer.write(value.toString())
            is Number -> writer.write(JSONObject.numberToString(value))
            else -> writer.write(JSONObject.quote(value.toString()))
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray, available: Int): Boolean {
        if (available < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }
}
