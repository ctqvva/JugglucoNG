package tk.glucodata

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GluciferPayloadTests {
    private fun payload(
        selected: Set<String> = GluciferPayload.defaults,
        seq: Long = 1,
        now: Long = 1_788_696_000_000L,
        alerts: Map<String, Boolean?> = mapOf("low" to false, "high" to true),
        values: Map<String, Any?> = mapOf("trend" to "Flat", "delta_mgdl" to 0, "sensor_id" to "private-serial")
    ) = GluciferPayload.build("phone-test", seq, now, 1_788_695_990_000L, 123, selected, values, alerts)

    @Test fun `glucose remains present with every optional field disabled`() {
        val result = payload(selected = emptySet())
        assertEquals(123, result.getJSONObject("glucose").getInt("mgdl"))
        assertEquals(0, result.getJSONObject("fields").length())
        assertEquals(0, result.getJSONObject("alerts").length())
        assertFalse(result.toString().contains("private-serial"))
    }

    @Test fun `defaults include basic trends and booleans without sensor or treatment details`() {
        val result = payload()
        assertEquals(setOf("trend", "delta_mgdl"), result.getJSONObject("fields").keys().asSequence().toSet())
        assertEquals(false, result.getJSONObject("alerts").get("low"))
        assertEquals(true, result.getJSONObject("alerts").get("high"))
        assertTrue(result.getJSONObject("alerts").isNull("missed_reading"))
        assertFalse(result.toString().contains("private-serial"))
    }

    @Test fun `each optional field and alert can be selected independently`() {
        for (key in GluciferPayload.fields) {
            val result = payload(selected = setOf(key))
            assertEquals(setOf(key), result.getJSONObject("fields").keys().asSequence().toSet())
            assertEquals(0, result.getJSONObject("alerts").length())
        }
        for (key in GluciferPayload.alerts) {
            val result = payload(selected = setOf("alert:$key"))
            assertEquals(setOf(key), result.getJSONObject("alerts").keys().asSequence().toSet())
            assertEquals(0, result.getJSONObject("fields").length())
        }
    }

    @Test fun `unavailable numbers serialize to null while zero remains zero`() {
        val result = payload(selected = setOf("iob_u", "cob_g"), values = mapOf("iob_u" to Float.NaN, "cob_g" to 0f))
        assertTrue(result.getJSONObject("fields").isNull("iob_u"))
        assertEquals(0.0, result.getJSONObject("fields").getDouble("cob_g"), 0.0)
    }

    @Test fun `plain HTTP success and mismatched acknowledgement are not delivery success`() {
        assertFalse(GluciferPayload.acknowledged("", "phone-test", 1))
        assertFalse(GluciferPayload.acknowledged("{}", "phone-test", 1))
        for (status in listOf("accepted", "duplicate", "superseded")) {
            val ack = JSONObject().put("schema_version", 1).put("source_id", "phone-test")
                .put("sequence", 1).put("status", status)
            assertTrue(GluciferPayload.acknowledged(ack.toString(), "phone-test", 1))
            assertFalse(GluciferPayload.acknowledged(ack.toString(), "other", 1))
            assertFalse(GluciferPayload.acknowledged(ack.toString(), "phone-test", 2))
            ack.put("sequence", 1.5)
            assertFalse(GluciferPayload.acknowledged(ack.toString(), "phone-test", 1))
        }
    }

    @Test fun `retry preserves original sequence and timestamp after persistence round trip`() {
        val original = payload()
        val restored = JSONObject(original.toString())
        val candidate = payload(seq = 2, now = 1_788_696_010_000L)
        val retry = GluciferPayload.nextDelivery(restored, candidate, true, false)!!
        assertEquals(1, retry.getInt("sequence"))
        assertEquals(original.getLong("sent_at_ms"), retry.getLong("sent_at_ms"))
        assertTrue(GluciferPayload.sameData(original, retry))
    }

    @Test fun `alert transitions replace pending snapshots and field removal is immediate`() {
        val original = payload()
        val cleared = payload(seq = 2, alerts = mapOf("low" to false, "high" to false))
        assertSame(cleared, GluciferPayload.nextDelivery(original, cleared, true, false))
        val disabled = payload(selected = emptySet(), seq = 3)
        assertSame(disabled, GluciferPayload.nextDelivery(cleared, disabled, true, false))
    }

    @Test fun `acknowledged unchanged data stays silent across fallback intervals and restart`() {
        val original = JSONObject(payload().toString())
        for (elapsed in listOf(1_000L, 60_000L, 360_000L, 3_600_000L, 86_400_000L)) {
            val candidate = payload(seq = 2, now = original.getLong("sent_at_ms") + elapsed)
            assertNull(GluciferPayload.nextDelivery(original, candidate, false, false))
        }
    }

    @Test fun `explicit manual test can resend unchanged data`() {
        val original = payload()
        val candidate = payload(seq = 2)
        assertSame(candidate, GluciferPayload.nextDelivery(original, candidate, false, true))
    }

    @Test fun `new measurement sends even when its glucose value is identical`() {
        val original = payload()
        val candidate = payload(seq = 2)
        candidate.getJSONObject("glucose").put("time_ms", original.getJSONObject("glucose").getLong("time_ms") + 60_000L)
        assertSame(candidate, GluciferPayload.nextDelivery(original, candidate, false, false))
    }

    @Test fun `measurement rounding removes small IOB changes before comparison`() {
        val selected = setOf("iob_u", "cob_g", "rate_mgdl_min", "delta_mgdl")
        val first = payload(selected = selected, values = mapOf("iob_u" to 1.234567, "cob_g" to 12.367,
            "rate_mgdl_min" to -0.167, "delta_mgdl" to -1.267))
        val second = payload(selected = selected, seq = 2, values = mapOf("iob_u" to 1.234568, "cob_g" to 12.367,
            "rate_mgdl_min" to -0.167, "delta_mgdl" to -1.267))
        val fields = first.getJSONObject("fields")
        assertEquals(1.2, fields.getDouble("iob_u"), 0.0)
        assertEquals(12.4, fields.getDouble("cob_g"), 0.0)
        assertEquals(-0.2, fields.getDouble("rate_mgdl_min"), 0.0)
        assertEquals(-1.3, fields.getDouble("delta_mgdl"), 0.0)
        assertTrue(GluciferPayload.sameData(first, second))
        assertNull(GluciferPayload.nextDelivery(first, second, false, false))
    }

    @Test fun `journal and alert changes send without waiting for another glucose reading`() {
        val original = payload(selected = setOf("iob_u", "alert:high"), values = mapOf("iob_u" to 2.0))
        val changed = payload(selected = setOf("iob_u", "alert:high"), seq = 2, values = mapOf("iob_u" to 1.0))
        assertEquals(original.getJSONObject("glucose").toString(), changed.getJSONObject("glucose").toString())
        assertSame(changed, GluciferPayload.nextDelivery(original, changed, false, false))
        val cleared = payload(selected = setOf("iob_u", "alert:high"), seq = 3,
            values = mapOf("iob_u" to 1.0), alerts = mapOf("high" to false))
        assertSame(cleared, GluciferPayload.nextDelivery(changed, cleared, false, false))
    }

    @Test fun `rounding preserves lifecycle timestamps and boolean alerts`() {
        val timestamp = 1_788_600_123_456L
        val result = payload(selected = setOf("sensor_started_ms", "sensor_warmup", "alert:high"),
            values = mapOf("sensor_started_ms" to timestamp, "sensor_warmup" to false))
        assertEquals(timestamp, result.getJSONObject("fields").getLong("sensor_started_ms"))
        assertEquals(false, result.getJSONObject("fields").get("sensor_warmup"))
        assertEquals(true, result.getJSONObject("alerts").get("high"))
    }

    @Test fun `HTTP transport posts the snapshot and never follows redirects`() {
        val received = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/webhook/test") { exchange ->
            received.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/api/webhook/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/api/webhook/test")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val body = payload(selected = emptySet()).toString()
            assertEquals(200, GluciferSender.post("$base/api/webhook/test", body).first)
            assertEquals(body, received.get())
            received.set(null)
            assertEquals(302, GluciferSender.post("$base/api/webhook/redirect", body).first)
            assertNull(received.get())
        } finally {
            server.stop(0)
        }
    }
}
