package tk.glucodata.drivers.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiIobSnapshotTests {

    private val senderMillis = 1_788_000_000_000L

    @After
    fun tearDown() {
        ApiIobSnapshot.clear()
    }

    private fun senderPayload(
        timestamp: Long = senderMillis,
        journalIob: Double? = 1.85,
        bareJournalIob: Double? = 1.85,
        journalEiob: Double? = 1.20,
        bareJournalEiob: Double? = 1.20,
        journalCob: Double? = 24.0,
        bareJournalCob: Double? = 24.0,
        withJournal: Boolean = true,
        topJournalIob: Double? = 1.85,
        topBareIob: Double? = 1.85,
        topJournalCob: Double? = 24.0,
        topBareCob: Double? = 24.0,
    ): String {
        val root = JSONObject()
            .put("schema", "tk.glucodata.outbound.glucose.v1")
            .put("type", "glucose")
            .put("timestamp", timestamp)
            .put("glucose_mgdl", 142)
        topJournalIob?.let { root.put("journal_iob", it) }
        topBareIob?.let { root.put("iob", it) }
        topJournalCob?.let { root.put("journal_cob", it) }
        topBareCob?.let { root.put("cob", it) }
        if (withJournal) {
            val journal = JSONObject()
                .put("schema", "tk.glucodata.journal.snapshot.v3")
                .put("timestamp", timestamp)
                .put("events", JSONArray())
                .put("treatments", JSONArray())
            journalIob?.let { journal.put("journal_iob", it) }
            bareJournalIob?.let { journal.put("iob", it) }
            journalEiob?.let { journal.put("journal_eiob", it) }
            bareJournalEiob?.let { journal.put("eiob", it) }
            journalCob?.let { journal.put("journal_cob", it) }
            bareJournalCob?.let { journal.put("cob", it) }
            root.put("journal", journal)
        }
        return root.toString()
    }

    @Test
    fun parsesNestedJournalSnapshot() {
        val remote = ApiIobSnapshot.parse(senderPayload())
        assertNotNull(remote)
        assertEquals(1.85f, remote!!.iobUnits, 0.0001f)
        assertEquals(1.20f, remote.eiobUnits, 0.0001f)
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
        assertEquals(senderMillis, remote.timestampMillis)
    }

    @Test
    fun nestedJournalWinsOverTopLevel() {
        val body = senderPayload(topJournalIob = 9.99, topBareIob = 9.99, topBareCob = 1.0, topJournalCob = null)
        val remote = ApiIobSnapshot.parse(body)
        assertNotNull(remote)
        assertEquals(1.85f, remote!!.iobUnits, 0.0001f)
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
    }

    @Test
    fun topLevelIsUsedWithoutNestedJournal() {
        val remote = ApiIobSnapshot.parse(senderPayload(withJournal = false))
        assertNotNull(remote)
        assertEquals(1.85f, remote!!.iobUnits, 0.0001f)
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
        assertEquals(senderMillis, remote.timestampMillis)
        assertTrue(remote.eiobUnits.isNaN())
    }

    @Test
    fun journalPrefixedKeysWinOverBareKeys() {
        val body = senderPayload(
            journalIob = 1.50,
            bareJournalIob = 1.10,
            journalCob = 20.0,
            bareJournalCob = 10.0,
        )
        val remote = ApiIobSnapshot.parse(body)
        assertEquals(1.50f, remote!!.iobUnits, 0.0001f)
        assertEquals(20.0f, remote.cobGrams, 0.0001f)
    }

    @Test
    fun iobOnlyPayloadKeepsCobAsNaN() {
        val body = senderPayload(
            journalCob = null,
            bareJournalCob = null,
            topJournalCob = null,
            topBareCob = null,
            journalEiob = null,
            bareJournalEiob = null,
        )
        val remote = ApiIobSnapshot.parse(body)
        assertNotNull(remote)
        assertEquals(1.85f, remote!!.iobUnits, 0.0001f)
        assertTrue(remote.cobGrams.isNaN())
        assertTrue(remote.eiobUnits.isNaN())
    }

    @Test
    fun cobOnlyPayloadKeepsIobAsNaN() {
        val body = senderPayload(
            journalIob = null,
            bareJournalIob = null,
            topJournalIob = null,
            topBareIob = null,
            journalEiob = null,
            bareJournalEiob = null,
        )
        val remote = ApiIobSnapshot.parse(body)
        assertNotNull(remote)
        assertTrue(remote!!.iobUnits.isNaN())
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
    }

    @Test
    fun payloadWithoutAnySnapshotValueIsIgnored() {
        assertNull(
            ApiIobSnapshot.parse(
                senderPayload(
                    journalIob = null,
                    bareJournalIob = null,
                    journalCob = null,
                    bareJournalCob = null,
                    topJournalIob = null,
                    topBareIob = null,
                    topJournalCob = null,
                    topBareCob = null,
                )
            )
        )
        assertNull(ApiIobSnapshot.parse("{\"timestamp\":$senderMillis,\"glucose_mgdl\":142}"))
    }

    @Test
    fun payloadWithoutTimestampIsIgnored() {
        assertNull(ApiIobSnapshot.parse(senderPayload(timestamp = 0L)))
    }

    @Test
    fun malformedBodiesParseToNull() {
        assertNull(ApiIobSnapshot.parse(""))
        assertNull(ApiIobSnapshot.parse("   "))
        assertNull(ApiIobSnapshot.parse("not json"))
        assertNull(ApiIobSnapshot.parse("{}"))
        assertNull(ApiIobSnapshot.parse("[]"))
    }

    @Test
    fun arrayBodyPicksNewestSnapshot() {
        val older = senderPayload(timestamp = senderMillis - 300_000L)
        val body = "[$older,${senderPayload()}]"
        assertEquals(senderMillis, ApiIobSnapshot.parse(body)!!.timestampMillis)
        val reversed = "[${senderPayload()},$older]"
        assertEquals(senderMillis, ApiIobSnapshot.parse(reversed)!!.timestampMillis)
    }

    @Test
    fun readingsWrapperBodyPicksNewestSnapshot() {
        val newer = JSONObject(senderPayload())
        val older = JSONObject(senderPayload(timestamp = senderMillis - 300_000L))
        val body = JSONObject()
            .put("readings", JSONArray().put(older).put(newer))
            .toString()
        val remote = ApiIobSnapshot.parse(body)
        assertNotNull(remote)
        assertEquals(senderMillis, remote!!.timestampMillis)
        assertEquals(1.85f, remote.iobUnits, 0.0001f)
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
        assertEquals(1.20f, remote.eiobUnits, 0.0001f)
    }

    @Test
    fun entriesWrapperBodyFallsBackToTopLevelSnapshot() {
        val entry = JSONObject()
            .put("timestamp", senderMillis)
            .put("glucose_mgdl", 142)
        val body = JSONObject()
            .put("timestamp", senderMillis)
            .put("journal_iob", 2.5)
            .put("cob", 30.0)
            .put("entries", JSONArray().put(entry))
            .toString()
        val remote = ApiIobSnapshot.parse(body)
        assertNotNull(remote)
        assertEquals(2.5f, remote!!.iobUnits, 0.0001f)
        assertEquals(30.0f, remote.cobGrams, 0.0001f)
        assertEquals(senderMillis, remote.timestampMillis)
    }

    @Test
    fun wrapperWithoutAnySnapshotParsesToNull() {
        val entry = JSONObject()
            .put("timestamp", senderMillis)
            .put("glucose_mgdl", 142)
        assertNull(
            ApiIobSnapshot.parse(
                JSONObject().put("readings", JSONArray().put(entry)).toString()
            )
        )
        assertNull(ApiIobSnapshot.parse(JSONObject().put("entries", JSONArray()).toString()))
        assertNull(ApiIobSnapshot.parse(JSONObject().put("readings", JSONArray()).toString()))
    }

    @Test
    fun textFieldsParseIobCobAndTimestamp() {
        val remote = ApiIobSnapshot.fromTextFields(
            mapOf("GV" to "7.88", "IOB" to "1.85", "COB" to "24.0", "TS" to senderMillis.toString())
        )
        assertNotNull(remote)
        assertEquals(1.85f, remote!!.iobUnits, 0.0001f)
        assertEquals(24.0f, remote.cobGrams, 0.0001f)
        assertEquals(senderMillis, remote.timestampMillis)
        assertTrue(remote.eiobUnits.isNaN())
    }

    @Test
    fun textFieldsWithoutTimestampOrValuesAreIgnored() {
        assertNull(ApiIobSnapshot.fromTextFields(mapOf("IOB" to "1.0", "COB" to "2.0")))
        assertNull(ApiIobSnapshot.fromTextFields(mapOf("TS" to senderMillis.toString())))
        assertNull(ApiIobSnapshot.fromTextFields(emptyMap()))
    }

    @Test
    fun failedPollKeepsPreviousSnapshotUntilItExpires() {
        ApiIobSnapshot.update(ApiIobSnapshot.parse(senderPayload()))
        ApiIobSnapshot.update(null)
        assertNotNull(ApiIobSnapshot.fresh(senderMillis + 60_000L))
    }

    @Test
    fun delayedRetransmissionDoesNotReplaceNewerSnapshot() {
        assertTrue(ApiIobSnapshot.update(ApiIobSnapshot.parse(senderPayload())!!))
        assertFalse(ApiIobSnapshot.update(ApiIobSnapshot.parse(senderPayload(timestamp = senderMillis - 60_000L))!!))
        assertEquals(senderMillis, ApiIobSnapshot.fresh(senderMillis)!!.timestampMillis)
    }

    @Test
    fun freshnessWindowMatchesNightscoutRecency() {
        ApiIobSnapshot.update(ApiIobSnapshot.parse(senderPayload()))
        val window = ApiIobSnapshot.FRESHNESS_WINDOW_MS
        assertNotNull(ApiIobSnapshot.fresh(senderMillis))
        assertNotNull(ApiIobSnapshot.fresh(senderMillis + window))
        assertNull(ApiIobSnapshot.fresh(senderMillis + window + 1L))
        // The sender's clock running slightly ahead still counts as fresh.
        assertNotNull(ApiIobSnapshot.fresh(senderMillis - 60_000L))
    }

    @Test
    fun clearDropsTheSnapshot() {
        ApiIobSnapshot.update(ApiIobSnapshot.parse(senderPayload()))
        ApiIobSnapshot.clear()
        assertNull(ApiIobSnapshot.fresh(senderMillis))
    }
}
