package tk.glucodata.data

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
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
    NONE(
        fileSuffix = ".json",
        mimeType = "application/json"
    ),
    GZIP(
        fileSuffix = ".json.gz",
        mimeType = "application/gzip"
    ),
    ZSTD(
        fileSuffix = ".json.zst",
        mimeType = "application/zstd"
    )
}

/**
 * An array written element by element as [ExportPackageCodec.writeJson] reaches it. A full
 * history held as one JSONObject tree outgrew the heap (#627); this keeps one page at a time.
 */
internal class StreamedJsonArray(val produce: suspend (emit: suspend (Any?) -> Unit) -> Unit)

/**
 * Receives the elements of one array while [ExportPackageCodec.readJson] parses it, instead of
 * the array being kept in the returned payload. Non-object elements arrive as null.
 */
internal class StreamedArraySink(private val onElement: suspend (JSONObject?) -> Unit) {
    var present = false
        private set
    var elements = 0
        private set

    internal fun begin() {
        present = true
    }

    internal suspend fun accept(element: JSONObject?) {
        elements++
        onElement(element)
    }
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
        compressedWriter(output, compression, zstdWorkers).use { writer ->
            writer.write(text)
        }
    }

    suspend fun writeJson(
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
        return decoded(input).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    /**
     * Parses a package without holding its text. Arrays named in [streamed] by their dotted
     * path (e.g. "history.readings") go to their sink element by element and are left out of
     * the returned object.
     */
    suspend fun readJson(
        input: InputStream,
        streamed: Map<String, StreamedArraySink> = emptyMap()
    ): JSONObject {
        return JsonReader(InputStreamReader(decoded(input), StandardCharsets.UTF_8)).use { reader ->
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Backup is not a JSON object" }
            val payload = readJsonValue(reader, "", streamed) as JSONObject
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after backup" }
            payload
        }
    }

    /** The top-level "schema" field, read without parsing anything after it. */
    fun readSchema(input: InputStream): String? {
        return JsonReader(InputStreamReader(decoded(input), StandardCharsets.UTF_8)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@use null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "schema" && reader.peek() == JsonToken.STRING) {
                    return@use reader.nextString()
                }
                reader.skipValue()
            }
            null
        }
    }

    private fun decoded(input: InputStream): InputStream {
        val source = PushbackInputStream(input, zstdMagic.size)
        val header = ByteArray(zstdMagic.size)
        val count = source.read(header)
        if (count > 0) source.unread(header, 0, count)

        return when {
            header.startsWith(gzipMagic, count) -> GZIPInputStream(source)
            header.startsWith(zstdMagic, count) -> ZstdInputStreamNoFinalizer(source)
            else -> source
        }
    }

    /** [path] is the dotted name of this value, or null inside an array where nothing streams. */
    private suspend fun readJsonValue(
        reader: JsonReader,
        path: String?,
        streamed: Map<String, StreamedArraySink>
    ): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val childPath = path?.let { if (it.isEmpty()) name else "$it.$name" }
                    val sink = childPath?.let(streamed::get)
                    if (sink != null && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        sink.begin()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            sink.accept(readJsonValue(reader, null, streamed) as? JSONObject)
                        }
                        reader.endArray()
                    } else {
                        result.put(name, readJsonValue(reader, childPath, streamed))
                    }
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) result.put(readJsonValue(reader, null, streamed))
                reader.endArray()
                result
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                // As JSONObject(String) would type it, so opt* calls see the same values.
                val literal = reader.nextString()
                literal.toIntOrNull() ?: literal.toLongOrNull() ?: literal.toDouble()
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }
            else -> error("Unexpected JSON token ${reader.peek()}")
        }
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
            ExportCompression.NONE -> output
            ExportCompression.GZIP -> GZIPOutputStream(output)
            ExportCompression.ZSTD -> ZstdOutputStreamNoFinalizer(output)
                .setChecksum(true)
                .setWorkers(zstdWorkers.coerceAtLeast(1))
        }
        return OutputStreamWriter(compressed, StandardCharsets.UTF_8)
    }

    private suspend fun writeJsonValue(writer: OutputStreamWriter, value: Any?) {
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
            is StreamedJsonArray -> {
                writer.write('['.code)
                var first = true
                value.produce { element ->
                    if (!first) writer.write(','.code)
                    first = false
                    writeJsonValue(writer, element)
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
