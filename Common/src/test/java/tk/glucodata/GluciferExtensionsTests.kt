package tk.glucodata

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GluciferExtensionsTests {
    private val now = 1788650000000L

    @Test fun `QR accepts only a bounded HTTP webhook URL without embedded credentials`() {
        assertEquals("https://ha.example/api/webhook/abc-def", GluciferSetup.parseQr(" https://ha.example/api/webhook/abc-def\n"))
        assertEquals("https://ha.example:8123", GluciferSetup.hostLabel("https://ha.example:8123/api/webhook/secret"))
        for (url in listOf("javascript:alert(1)", "https://user:password@ha.example/api/webhook/secret", "https://ha.example/api/webhook/a?secret=b", "https://ha.example/api/webhook/a#x", "https://ha.example/api/webhook/a/another", "https://ha.example/not-a-webhook", "x".repeat(2049))) {
            assertNull(url, GluciferSetup.parseQr(url))
        }
    }

    @Test fun `history batches are ordered bounded and contain no alert or live fields`() {
        val points = (1..400).reversed().map { GlucosePoint(now - it * 60000L, 123f, 110f) }
        val batch = GluciferHistory.build("phone", points, 0, now, now, false)!!
        val readings = batch.getJSONArray("readings")
        assertEquals(256, readings.length())
        assertEquals(now - 400 * 60000L, readings.getJSONObject(0).getLong("time_ms"))
        assertFalse(batch.has("alerts"))
        assertFalse(batch.has("fields"))
        assertEquals(123.0, readings.getJSONObject(0).getDouble("mgdl"), 0.0)
        val restored = JSONObject(batch.toString())
        assertEquals(batch.toString(), restored.toString())
    }

    @Test fun `history excludes future expired already acknowledged and invalid points`() {
        val points = listOf(GlucosePoint(now + 1, 123f, 0f), GlucosePoint(now - GluciferHistory.RETENTION_MS - 1, 123f, 0f), GlucosePoint(now - 100, Float.NaN, 0f), GlucosePoint(now - 90, -1f, 0f), GlucosePoint(now - 80, 140f, 130f), GlucosePoint(now - 80, 140f, 130f))
        val batch = GluciferHistory.build("phone", points, now - 1000, now, now, true)!!
        assertEquals(1, batch.getJSONArray("readings").length())
        assertEquals(130.0, batch.getJSONArray("readings").getJSONObject(0).getDouble("mgdl"), 0.0)
        assertNull(GluciferHistory.build("phone", points, now - 80, now, now, true))
    }

    @Test fun `history cursor advances only for matching durable batch acknowledgement`() {
        val batch = GluciferHistory.build("phone", listOf(GlucosePoint(now - 60000, 123f, 0f)), 0, now, now, false)!!
        val ack = JSONObject().put("schema_version", 2).put("type", "history").put("source_id", "phone").put("batch_id", batch.getString("batch_id")).put("status", "accepted").put("through_ms", now - 60000)
        assertTrue(GluciferHistory.acknowledged(ack.toString(), batch))
        assertFalse(GluciferHistory.acknowledged("", batch))
        ack.put("batch_id", "other")
        assertFalse(GluciferHistory.acknowledged(ack.toString(), batch))
        ack.put("batch_id", batch.getString("batch_id")).put("through_ms", now)
        assertFalse(GluciferHistory.acknowledged(ack.toString(), batch))
    }

    @Test fun `version two preserves optional warmup boolean and mandatory glucose`() {
        val body = GluciferPayload.build("phone", 1, now, now, 123, setOf("sensor_started_ms", "sensor_warmup"), mapOf("sensor_started_ms" to now - 3600000, "sensor_warmup" to false), emptyMap(), 2)
        assertEquals(2, body.getInt("schema_version"))
        assertEquals(false, body.getJSONObject("fields").get("sensor_warmup"))
        assertEquals(now - 3600000, body.getJSONObject("fields").getLong("sensor_started_ms"))
        assertEquals(123, body.getJSONObject("glucose").getInt("mgdl"))
        assertFalse(GluciferPayload.defaults.contains("sensor_warmup"))
        assertFalse(OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_GLUCIFER).gluciferHistory)
    }
}
