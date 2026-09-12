package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ExportPackageCodecTests {
    @Test
    fun uncompressedJsonRoundTrips() {
        val encoded = encode(ExportCompression.NONE)

        assertEquals(json, ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded)))
    }

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
    fun corruptCompressedBackupFailsBeforeRestore() {
        val encoded = encode(ExportCompression.GZIP)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0xff).toByte()

        assertTrue(
            runCatching { ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded)) }.isFailure
        )
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
    fun uncompressedExportUsesJsonFileNameAndMimeType() {
        val request = ExportPackageExporter.ExportRequest(
            includeSettings = true,
            includeHistory = true,
            includeCalibrations = true,
            historyDays = null,
            compression = ExportCompression.NONE
        )

        assertTrue(ExportPackageExporter.suggestedFileName(request).endsWith(".json"))
        assertEquals("application/json", ExportPackageExporter.mimeTypeFor(request))
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

    @Test
    fun backupDryRunParsesEveryRestorableSectionWithoutWriting() {
        val summary = ExportPackageExporter.validateBackupPayload(validBackupPayload())

        assertTrue(summary.settingsIncluded)
        assertEquals(1, summary.historyReadings)
        assertEquals(1, summary.journalEntries)
        assertEquals(1, summary.journalFoods)
        assertEquals(1, summary.insulinPresets)
        assertEquals(1, summary.calibrations)
    }

    @Test
    fun backupDryRunRejectsARecordTheImporterWouldHaveSkipped() {
        val payload = validBackupPayload()
        payload.getJSONObject("history").getJSONArray("readings").put(
            JSONObject().put("timestamp", 0L).put("valueMgDl", 0.0)
        )

        assertTrue(runCatching { ExportPackageExporter.validateBackupPayload(payload) }.isFailure)
    }

    @Test
    fun backupDryRunRejectsTruncatedSettingsInsteadOfTreatingThemAsEmpty() {
        val payload = validBackupPayload()
        payload.getJSONObject("settings").remove("sharedPreferences")

        assertTrue(runCatching { ExportPackageExporter.validateBackupPayload(payload) }.isFailure)
    }

    @Test
    fun settingsOnlyBackupDryRunRequiresAndParsesJournalData() {
        val payload = JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 3)
            .put("sharedPreferences", JSONObject())
            .put("nativeSettingsFiles", JSONObject())
            .put(
                "journalData",
                JSONObject()
                    .put("entries", JSONArray())
                    .put("insulinPresets", JSONArray())
                    .put("foods", JSONArray())
            )

        val summary = ExportPackageExporter.validateBackupPayload(payload)

        assertTrue(summary.settingsIncluded)
        assertEquals(0, summary.journalEntries)
    }

    @Test
    fun settingsOnlyBackupDryRunRejectsMissingJournalData() {
        val payload = JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 3)
            .put("sharedPreferences", JSONObject())
            .put("nativeSettingsFiles", JSONObject())

        assertTrue(runCatching { ExportPackageExporter.validateBackupPayload(payload) }.isFailure)
    }

    private fun validBackupPayload(): JSONObject {
        val settings = JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 3)
            .put("sharedPreferences", JSONObject())
            .put("nativeSettingsFiles", JSONObject())
        val history = JSONObject()
            .put(
                "readings",
                JSONArray().put(
                    JSONObject()
                        .put("timestamp", 1_700_000_000_000L)
                        .put("sensorSerial", "test")
                        .put("valueMgDl", 120.0)
                        .put("rawValueMgDl", 121.0)
                )
            )
            .put(
                "journalEntries",
                JSONArray().put(
                    JSONObject()
                        .put("id", 1L)
                        .put("timestamp", 1_700_000_000_000L)
                        .put("entryType", "INSULIN")
                        .put("title", "Dose")
                )
            )
            .put(
                "journalInsulinPresets",
                JSONArray().put(
                    JSONObject()
                        .put("id", 1L)
                        .put("displayName", "Test")
                        .put("onsetMinutes", 10)
                        .put("durationMinutes", 300)
                        .put("accentColor", 1)
                )
            )
            .put(
                "journalFoods",
                JSONArray().put(
                    JSONObject()
                        .put("id", 1L)
                        .put("displayName", "Test food")
                        .put("carbsGrams", 10.0)
                )
            )
            .put(
                "deletedReadings",
                JSONArray().put(
                    JSONObject()
                        .put("timestamp", 1_699_999_000_000L)
                        .put("sensorSerial", "test")
                )
            )
            .put(
                "pendingJournalDeletes",
                JSONArray().put(
                    JSONObject()
                        .put("entryId", 1L)
                        .put("nsRemoteId", "remote")
                )
            )
        val calibrations = JSONObject().put(
            "calibrations",
            JSONArray().put(
                JSONObject()
                    .put("timestamp", 1_700_000_000_000L)
                    .put("sensorId", "test")
                    .put("sensorValue", 120.0)
                    .put("sensorValueRaw", 121.0)
                    .put("userValue", 119.0)
            )
        )
        return JSONObject()
            .put("schema", ExportPackageExporter.SCHEMA)
            .put("schemaVersion", 1)
            .put("sections", JSONArray().put("settings").put("history").put("calibrations"))
            .put("settings", settings)
            .put("history", history)
            .put("calibrations", calibrations)
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
