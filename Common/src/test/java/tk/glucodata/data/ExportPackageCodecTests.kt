package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ExportPackageCodecTests {
    private val json = """{"schema":"tk.glucodata.export-package","history":"${"reading,".repeat(20_000)}"}"""

    @Test
    fun gzipRoundTripUsesExpectedMagic() {
        val encoded = encode(ExportCompression.GZIP)

        assertEquals(0x1f, encoded[0].toInt() and 0xff)
        assertEquals(0x8b, encoded[1].toInt() and 0xff)
        assertEquals(json, ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded)))
    }

    @Test
    fun zstdRoundTripUsesExpectedMagicAndMultipleWorkers() {
        val encoded = encode(ExportCompression.ZSTD, zstdWorkers = 2)

        assertEquals(listOf(0x28, 0xb5, 0x2f, 0xfd), encoded.take(4).map { it.toInt() and 0xff })
        assertEquals(json, ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded)))
    }

    @Test
    fun existingPlainJsonRemainsReadable() {
        assertEquals(
            json,
            ExportPackageCodec.readUtf8(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun structuredWriterStreamsValidJsonWithoutChangingValues() {
        val payload = JSONObject()
            .put("text", "line\n\"quoted\"")
            .put("number", 42.5)
            .put("enabled", true)
            .put("missing", JSONObject.NULL)
            .put("items", org.json.JSONArray().put("first").put(2))
        val encoded = ByteArrayOutputStream().also { output ->
            ExportPackageCodec.writeJson(output, payload, ExportCompression.GZIP)
        }.toByteArray()

        val decoded = JSONObject(ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded)))

        assertEquals(payload.toString(), decoded.toString())
    }

    @Test
    fun gzipIsTheDefaultExportCompression() {
        val request = ExportPackageExporter.ExportRequest(
            includeSettings = true,
            includeHistory = true,
            includeCalibrations = true,
            historyDays = null
        )

        assertEquals(ExportCompression.GZIP, request.compression)
        assertTrue(ExportPackageExporter.suggestedFileName(request).endsWith(".json.gz"))
        assertEquals("application/gzip", ExportPackageExporter.mimeTypeFor(request))
    }

    @Test
    fun zstdUsesRegisteredFileExtensionAndMimeType() {
        val request = ExportPackageExporter.ExportRequest(
            includeSettings = true,
            includeHistory = true,
            includeCalibrations = true,
            historyDays = null,
            compression = ExportCompression.ZSTD
        )

        assertTrue(ExportPackageExporter.suggestedFileName(request).endsWith(".json.zst"))
        assertEquals("application/zstd", ExportPackageExporter.mimeTypeFor(request))
    }

    @Test
    fun zstdWorkerCountLeavesOneCoreFreeAndCapsAtFour() {
        assertEquals(1, ExportPackageCodec.defaultZstdWorkers(processors = 1))
        assertEquals(1, ExportPackageCodec.defaultZstdWorkers(processors = 2))
        assertEquals(3, ExportPackageCodec.defaultZstdWorkers(processors = 4))
        assertEquals(4, ExportPackageCodec.defaultZstdWorkers(processors = 16))
    }

    @Test
    fun importClassifierRecognizesBothExportSchemas() {
        assertEquals(
            ExportPackageExporter.ImportFileType.EXPORT_PACKAGE,
            ExportPackageExporter.classifyPayload(JSONObject().put("schema", ExportPackageExporter.SCHEMA))
        )
        assertEquals(
            ExportPackageExporter.ImportFileType.SETTINGS,
            ExportPackageExporter.classifyPayload(JSONObject().put("schema", SettingsExporter.SCHEMA))
        )
        assertEquals(
            ExportPackageExporter.ImportFileType.OTHER,
            ExportPackageExporter.classifyPayload(JSONObject().put("schema", "unknown"))
        )
    }

    private fun encode(
        compression: ExportCompression,
        zstdWorkers: Int = 1
    ): ByteArray {
        return ByteArrayOutputStream().also { output ->
            ExportPackageCodec.writeUtf8(output, json, compression, zstdWorkers)
        }.toByteArray()
    }
}
