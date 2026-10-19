package tk.glucodata.drivers.nightscout

import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutFollowerDeviceStatusTests {

    private val docTime = "2026-08-03T12:00:00.000Z"
    private val docMillis = Instant.parse(docTime).toEpochMilli()

    @After
    fun tearDown() {
        NightscoutFollowerDeviceStatus.clear()
    }

    private fun uploaderDocument(
        createdAt: String = docTime,
        openapsTimestamp: String? = createdAt,
        eiob: String = ",\"eiob\":1.25",
        cob: String = ",\"cob\":18.00",
    ): String {
        val openaps = openapsTimestamp
            ?.let { "\"openaps\":{\"iob\":{\"iob\":2.50,\"timestamp\":\"$it\"}}," }
            .orEmpty()
        return "{\"device\":\"JugglucoNG\",\"created_at\":\"$createdAt\",$openaps" +
            "\"jugglucong\":{\"iob\":2.50$eiob$cob}}"
    }

    @Test
    fun parsesUploaderDocument() {
        val remote = NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument()}]")
        assertNotNull(remote)
        assertEquals(2.50f, remote!!.iobUnits, 0.0001f)
        assertEquals(1.25f, remote.eiobUnits, 0.0001f)
        assertEquals(18.00f, remote.cobGrams, 0.0001f)
        assertEquals(docMillis, remote.timestampMillis)
    }

    @Test
    fun ignoresForeignUploaderDocuments() {
        // A real loop system publishes openaps.iob but no jugglucong block.
        val foreign = "[{\"device\":\"loop://iPhone\",\"created_at\":\"$docTime\"," +
            "\"openaps\":{\"iob\":{\"iob\":3.10,\"timestamp\":\"$docTime\"}}}]"
        assertNull(NightscoutFollowerDeviceStatus.parseNewest(foreign))
    }

    @Test
    fun picksNewestDocumentByTimestamp() {
        val older = uploaderDocument(createdAt = "2026-08-03T11:40:00.000Z")
        val body = "[$older,${uploaderDocument()}]"
        assertEquals(docMillis, NightscoutFollowerDeviceStatus.parseNewest(body)!!.timestampMillis)
        val reversed = "[${uploaderDocument()},$older]"
        assertEquals(docMillis, NightscoutFollowerDeviceStatus.parseNewest(reversed)!!.timestampMillis)
    }

    @Test
    fun prefersOpenapsTimestampOverCreatedAt() {
        val body = "[${uploaderDocument(createdAt = "2026-08-03T12:05:00.000Z", openapsTimestamp = docTime)}]"
        assertEquals(docMillis, NightscoutFollowerDeviceStatus.parseNewest(body)!!.timestampMillis)
    }

    @Test
    fun fallsBackToCreatedAtWithoutOpenapsBlock() {
        val body = "[${uploaderDocument(openapsTimestamp = null)}]"
        assertEquals(docMillis, NightscoutFollowerDeviceStatus.parseNewest(body)!!.timestampMillis)
    }

    @Test
    fun missingCobAndEiobParseAsNaN() {
        val remote = NightscoutFollowerDeviceStatus.parseNewest(
            "[${uploaderDocument(eiob = "", cob = "")}]"
        )
        assertNotNull(remote)
        assertTrue(remote!!.eiobUnits.isNaN())
        assertTrue(remote.cobGrams.isNaN())
        assertEquals(2.50f, remote.iobUnits, 0.0001f)
    }

    @Test
    fun documentWithoutIobIsIgnored() {
        val body = "[{\"created_at\":\"$docTime\",\"jugglucong\":{\"eiob\":1.0,\"cob\":10.0}}]"
        assertNull(NightscoutFollowerDeviceStatus.parseNewest(body))
    }

    @Test
    fun valuesTooLargeForFloatAreRejectedOrOmitted() {
        val invalidIob = "[{\"created_at\":\"$docTime\",\"jugglucong\":{\"iob\":1e100}}]"
        assertNull(NightscoutFollowerDeviceStatus.parseNewest(invalidIob))

        val oversizedOptional = "[{\"created_at\":\"$docTime\",\"jugglucong\":" +
            "{\"iob\":2.5,\"eiob\":1e100,\"cob\":-1e100}}]"
        val remote = NightscoutFollowerDeviceStatus.parseNewest(oversizedOptional)!!
        assertTrue(remote.eiobUnits.isNaN())
        assertTrue(remote.cobGrams.isNaN())
    }

    @Test
    fun malformedBodiesParseToNull() {
        assertNull(NightscoutFollowerDeviceStatus.parseNewest("not json"))
        assertNull(NightscoutFollowerDeviceStatus.parseNewest("{}"))
        assertNull(NightscoutFollowerDeviceStatus.parseNewest("[]"))
        assertNull(NightscoutFollowerDeviceStatus.parseNewest("[{\"created_at\":\"nonsense\",\"jugglucong\":{\"iob\":1.0}}]"))
    }

    @Test
    fun freshnessWindowMatchesNightscoutRecency() {
        NightscoutFollowerDeviceStatus.update(NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument()}]"))
        val window = NightscoutFollowerDeviceStatus.FRESHNESS_WINDOW_MS
        assertNotNull(NightscoutFollowerDeviceStatus.fresh(docMillis))
        assertNotNull(NightscoutFollowerDeviceStatus.fresh(docMillis + window))
        assertNull(NightscoutFollowerDeviceStatus.fresh(docMillis + window + 1L))
        // The uploader's clock running slightly ahead still counts as fresh.
        assertNotNull(NightscoutFollowerDeviceStatus.fresh(docMillis - 60_000L))
    }

    @Test
    fun staleThenFreshDocumentSwitchesBackWithoutStickingValues() {
        NightscoutFollowerDeviceStatus.update(NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument()}]"))
        val laterTime = "2026-08-03T13:00:00.000Z"
        val laterMillis = Instant.parse(laterTime).toEpochMilli()
        // Old document has aged out: local computation applies.
        assertNull(NightscoutFollowerDeviceStatus.fresh(laterMillis))
        // A new upload arrives: remote applies again, with the new values.
        NightscoutFollowerDeviceStatus.update(
            NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument(createdAt = laterTime)}]")
        )
        assertEquals(laterMillis, NightscoutFollowerDeviceStatus.fresh(laterMillis)!!.timestampMillis)
    }

    @Test
    fun failedPollKeepsPreviousSnapshotUntilItExpires() {
        NightscoutFollowerDeviceStatus.update(NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument()}]"))
        NightscoutFollowerDeviceStatus.update(null)
        assertNotNull(NightscoutFollowerDeviceStatus.fresh(docMillis + 60_000L))
    }

    @Test
    fun clearDropsTheSnapshot() {
        NightscoutFollowerDeviceStatus.update(NightscoutFollowerDeviceStatus.parseNewest("[${uploaderDocument()}]"))
        NightscoutFollowerDeviceStatus.clear()
        assertNull(NightscoutFollowerDeviceStatus.fresh(docMillis))
    }
}
