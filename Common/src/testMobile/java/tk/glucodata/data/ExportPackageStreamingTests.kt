package tk.glucodata.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A full-history backup outgrew the heap as one JSONObject tree (#627). These pin the streaming
 * path that replaced it: the file is unchanged, but neither side holds every reading at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExportPackageStreamingTests {

    @Test
    fun streamedArrayWritesTheSameJsonAsAHeldArray() = runBlocking {
        val readings = (1..5).map { JSONObject().put("timestamp", it.toLong()).put("valueMgDl", 100.5) }
        val held = JSONObject().put("history", JSONObject().put("readings", JSONArray(readings)))
        val streamed = JSONObject().put(
            "history",
            JSONObject().put("readings", StreamedJsonArray { emit -> readings.forEach { emit(it) } })
        )

        assertEquals(text(encode(held)), text(encode(streamed)))
    }

    @Test
    fun streamedArrayGoesToItsSinkAndTheRestMatchesJsonObject() = runBlocking {
        val readings = (1..2_500).map { index ->
            JSONObject().put("timestamp", 1_700_000_000_000L + index).put("valueMgDl", index % 300 + 0.5)
        }
        val payload = JSONObject()
            .put("schema", "tk.glucodata.export-package")
            .put("schemaVersion", 2)
            .put("history", JSONObject()
                .put("rangeStartEpochMillis", JSONObject.NULL)
                .put("readings", StreamedJsonArray { emit -> readings.forEach { emit(it) } })
                .put("journalEntries", JSONArray().put(JSONObject().put("amount", 2.5).put("big", 5_000_000_000L))))
            .put("settings", JSONObject().put("enabled", true).put("name", "line\n\"quoted\""))
        val encoded = encode(payload, ExportCompression.ZSTD)

        val received = mutableListOf<JSONObject?>()
        val sink = StreamedArraySink { received += it }
        val parsed = ExportPackageCodec.readJson(ByteArrayInputStream(encoded), mapOf("history.readings" to sink))

        assertTrue(sink.present)
        assertEquals(readings.size, sink.elements)
        assertEquals(readings.map { it.toString() }, received.map { it.toString() })
        assertFalse(parsed.getJSONObject("history").has("readings"))
        // Everything not streamed parses exactly as JSONObject(String) would have parsed it.
        val reference = JSONObject(text(encoded)).apply { getJSONObject("history").remove("readings") }
        assertEquals(reference.toString(), parsed.toString())
    }

    @Test
    fun arrayOutsideItsPathIsKeptAndNonObjectsReachTheSinkAsNull() = runBlocking {
        val json = """{"readings":[1],"history":{"readings":[{"a":1},2,null]}}"""
        val received = mutableListOf<JSONObject?>()
        val sink = StreamedArraySink { received += it }

        val parsed = ExportPackageCodec.readJson(json.byteInputStream(), mapOf("history.readings" to sink))

        assertEquals(1, parsed.getJSONArray("readings").length())
        assertEquals(3, sink.elements)
        assertEquals(listOf("""{"a":1}""", null, null), received.map { it?.toString() })
    }

    @Test
    fun missingStreamedArrayIsReportedAbsent() = runBlocking {
        val sink = StreamedArraySink {}
        ExportPackageCodec.readJson("""{"history":{"journalEntries":[]}}""".byteInputStream(), mapOf("history.readings" to sink))
        assertFalse(sink.present)
    }

    @Test
    fun truncatedBackupFailsToParse() {
        val encoded = encode(JSONObject().put("history", JSONObject().put("readings", JSONArray().put(1).put(2))))
        val truncated = text(encoded).dropLast(3).toByteArray()

        assertTrue(runCatching { runBlocking { ExportPackageCodec.readJson(ByteArrayInputStream(truncated)) } }.isFailure)
    }

    @Test
    fun schemaIsReadWithoutParsingTheRest() {
        // Nothing after the schema is read, so a body that would not fit (or not parse) is no obstacle.
        val head = """{"schema":"tk.glucodata.export-package","history":{"readings":[""".toByteArray()
        val endless = object : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < head.size) head[index++].toInt() else '{'.code
        }

        assertEquals("tk.glucodata.export-package", ExportPackageCodec.readSchema(endless))
        assertNull(ExportPackageCodec.readSchema("""{"other":[1,2]}""".byteInputStream()))
    }

    @Test
    fun readingsPageWalksEveryRowAcrossTimestampTies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.historyDao()
            val rows = (0 until 7).flatMap { minute ->
                listOf("A", "B", "C").map { serial ->
                    HistoryReading(timestamp = 60_000L * (minute + 1), sensorSerial = serial, value = 100f, rawValue = 100f, rate = null)
                }
            }
            dao.insertAll(rows)

            val walked = mutableListOf<Pair<Long, String>>()
            var afterTimestamp = Long.MIN_VALUE
            var afterSerial = ""
            while (true) {
                val page = dao.getReadingsPageSince(120_000L, afterTimestamp, afterSerial, limit = 4)
                if (page.isEmpty()) break
                page.mapTo(walked) { it.timestamp to it.sensorSerial }
                afterTimestamp = page.last().timestamp
                afterSerial = page.last().sensorSerial
            }

            val expected = rows.filter { it.timestamp >= 120_000L }.map { it.timestamp to it.sensorSerial }
            assertEquals(expected, walked)
        } finally {
            database.close()
        }
    }

    private fun encode(payload: JSONObject, compression: ExportCompression = ExportCompression.NONE): ByteArray =
        ByteArrayOutputStream().also { output ->
            runBlocking { ExportPackageCodec.writeJson(output, payload, compression) }
        }.toByteArray()

    private fun text(encoded: ByteArray): String = ExportPackageCodec.readUtf8(ByteArrayInputStream(encoded))
}
