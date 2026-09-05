package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutIobDeviceStatusTests {

    private val now = 1_754_200_000_000L // 2025-08-03T06:26:40.000Z

    // -- document shape ----------------------------------------------------

    @Test
    fun `document carries classic IOB in the openaps container Nightscout reads`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 0f)!!
        val entry = JSONArray(doc).getJSONObject(0)
        assertEquals("JugglucoNG", entry.getString("device"))
        val iob = entry.getJSONObject("openaps").getJSONObject("iob")
        assertEquals(1.8, iob.getDouble("iob"), 1e-9)
        assertTrue(
            "timestamp must be ISO8601: " + iob.getString("timestamp"),
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(iob.getString("timestamp"))
        )
        assertEquals(entry.getString("created_at"), iob.getString("timestamp"))
    }

    @Test
    fun `eiob and cob travel in the jugglucong namespace not the standard field`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 24f)!!
        val entry = JSONArray(doc).getJSONObject(0)
        val own = entry.getJSONObject("jugglucong")
        assertEquals(1.8, own.getDouble("iob"), 1e-9)
        assertEquals(1.5, own.getDouble("eiob"), 1e-9)
        assertEquals(24.0, own.getDouble("cob"), 1e-9)
        // The standard container must stay the classic-IOB contract.
        val openapsIob = entry.getJSONObject("openaps").getJSONObject("iob")
        assertFalse(openapsIob.has("eiob"))
        assertFalse(openapsIob.has("cob"))
    }

    @Test
    fun `activity and basaliob are absent because the journal has no such values`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 0f)!!
        val iob = JSONArray(doc).getJSONObject(0).getJSONObject("openaps").getJSONObject("iob")
        assertFalse(iob.has("activity"))
        assertFalse(iob.has("basaliob"))
    }

    @Test
    fun `values without data of that kind are omitted`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, Float.NaN, Float.NaN)!!
        val own = JSONArray(doc).getJSONObject(0).getJSONObject("jugglucong")
        assertFalse(own.has("eiob"))
        assertFalse(own.has("cob"))
    }

    @Test
    fun `no classic IOB means no document`() {
        assertNull(NightscoutIobDeviceStatus.buildDocument(now, Float.NaN, Float.NaN, 24f))
        assertNull(NightscoutIobDeviceStatus.buildDocument(now, Float.POSITIVE_INFINITY, 1f, 24f))
    }

    @Test
    fun `non-finite optional values are omitted instead of producing invalid JSON`() {
        val own = JSONArray(
            NightscoutIobDeviceStatus.buildDocument(
                now,
                1.8f,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )!!
        ).getJSONObject(0).getJSONObject("jugglucong")
        assertFalse(own.has("eiob"))
        assertFalse(own.has("cob"))
    }

    @Test
    fun `insulin units pass through without glucose unit conversion`() {
        // 18 units would come back as 1.0 if an mg-dl-to-mmol conversion leaked in.
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 18f, Float.NaN, Float.NaN)!!
        val entry = JSONArray(doc).getJSONObject(0)
        assertEquals(18.0, entry.getJSONObject("openaps").getJSONObject("iob").getDouble("iob"), 1e-9)
    }

    @Test
    fun `timestamp is rendered in GMT`() {
        assertEquals("2026-01-02T03:04:05.678Z", NightscoutIobDeviceStatus.isoTimestamp(1_767_323_045_678L))
    }

    // -- cadence -----------------------------------------------------------

    private val fast = NightscoutIobDeviceStatus.FAST_INTERVAL_MILLIS
    private val slow = NightscoutIobDeviceStatus.SLOW_INTERVAL_MILLIS

    private fun due(
        elapsed: Long,
        iob: Float,
        eiob: Float = iob * 0.8f,
        cob: Float = 0f,
        lastIob: Float = iob,
        lastEiob: Float = eiob,
        lastCob: Float = cob
    ) = NightscoutIobDeviceStatus.shouldUpload(now + elapsed, now, iob, eiob, cob, lastIob, lastEiob, lastCob)

    // -- v3 document shape -------------------------------------------------

    @Test
    fun `a v3 document is a single object carrying the app field the server requires`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 24f, true)!!
        // v3 refuses an array, and refuses a document without "app" ("Bad or missing app field").
        assertTrue(doc, doc.startsWith("{"))
        val entry = JSONObject(doc)
        assertEquals("JugglucoNG", entry.getString("app"))
        assertEquals(now, entry.getLong("date"))
        assertEquals(1.8, entry.getJSONObject("openaps").getJSONObject("iob").getDouble("iob"), 1e-9)
        assertEquals(24.0, entry.getJSONObject("jugglucong").getDouble("cob"), 1e-9)
    }

    @Test
    fun `the v1 document keeps its array wrapper and needs no app field`() {
        val doc = NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 24f, false)!!
        assertTrue(doc, doc.startsWith("["))
        val entry = JSONArray(doc).getJSONObject(0)
        assertFalse(entry.has("app"))
        assertEquals(1.8, entry.getJSONObject("openaps").getJSONObject("iob").getDouble("iob"), 1e-9)
    }

    @Test
    fun `both versions carry the same payload`() {
        val v1 = JSONArray(NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 24f, false)!!).getJSONObject(0)
        val v3 = JSONObject(NightscoutIobDeviceStatus.buildDocument(now, 1.8f, 1.5f, 24f, true)!!)
        assertEquals(v1.getJSONObject("jugglucong").toString(), v3.getJSONObject("jugglucong").toString())
        assertEquals(v1.getJSONObject("openaps").toString(), v3.getJSONObject("openaps").toString())
        assertEquals(v1.getString("created_at"), v3.getString("created_at"))
    }

    @Test
    fun `first upload is always due`() {
        assertTrue(NightscoutIobDeviceStatus.shouldUpload(now, 0L, 1.8f, 1.5f, 0f, Float.NaN, Float.NaN, Float.NaN))
    }

    @Test
    fun `changing values upload at most every five minutes`() {
        assertFalse(due(fast - 1, iob = 1.6f, lastIob = 1.8f))
        assertTrue(due(fast, iob = 1.6f, lastIob = 1.8f))
    }

    @Test
    fun `active insulin keeps the fast cadence even when the rounded value is unchanged`() {
        assertTrue(due(fast, iob = 1.8f))
    }

    @Test
    fun `idle at zero falls back to the slow cadence`() {
        assertFalse(due(fast, iob = 0f, eiob = 0f))
        assertFalse(due(slow - 1, iob = 0f, eiob = 0f))
        assertTrue(due(slow, iob = 0f, eiob = 0f))
    }

    @Test
    fun `cob movement at zero insulin still uploads on the fast cadence`() {
        assertTrue(due(fast, iob = 0f, eiob = 0f, cob = 12f, lastCob = 24f))
    }

    @Test
    fun `value jitter below the display quantum does not count as a change`() {
        assertFalse(due(fast, iob = 0f, eiob = 0f, cob = 24.001f, lastCob = 24.004f))
    }

    @Test
    fun `fast gate matches the cadence decision`() {
        assertTrue(NightscoutIobDeviceStatus.fastIntervalElapsed(now, 0L))
        assertFalse(NightscoutIobDeviceStatus.fastIntervalElapsed(now + fast - 1, now))
        assertTrue(NightscoutIobDeviceStatus.fastIntervalElapsed(now + fast, now))
    }

    // -- ancillary token retry ---------------------------------------------

    @Test
    fun `an empty cache with no prior attempt fetches a token immediately`() {
        assertTrue(NightscoutIobDeviceStatus.tokenRetryDue(now, 0L))
    }

    @Test
    fun `a refused token request is not repeated within the interval`() {
        val retry = NightscoutIobDeviceStatus.TOKEN_RETRY_MILLIS
        assertFalse(NightscoutIobDeviceStatus.tokenRetryDue(now + retry - 1, now))
        assertTrue(NightscoutIobDeviceStatus.tokenRetryDue(now + retry, now))
    }

    @Test
    fun `a clock that moved backwards does not block token requests forever`() {
        assertTrue(NightscoutIobDeviceStatus.tokenRetryDue(now - 1, now))
    }
}
