package tk.glucodata.drivers.nightscout

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutFollowerV3Tests {

    private val baseUrl = "https://example.com"

    // -- endpoints ---------------------------------------------------------

    @Test
    fun followingV3ReadsTheV3CollectionsWithLimitPaging() {
        val treatments = NightscoutFollowerJournalEndpoints.treatments(baseUrl, useV3 = true)
        assertTrue(treatments, treatments.startsWith("$baseUrl/api/v3/treatments?"))
        // v3 pages with limit and has no .json suffix; sending the v1 form answers 401.
        assertTrue(treatments, treatments.contains("limit=512"))
        assertFalse(treatments, treatments.contains(".json"))
        assertFalse(treatments, treatments.contains("count="))
    }

    @Test
    fun v1FollowersKeepTheirExistingEndpointsExactly() {
        assertEquals(
            "$baseUrl/api/v1/treatments.json?count=512",
            NightscoutFollowerJournalEndpoints.treatments(baseUrl),
        )
        assertEquals(
            "$baseUrl/api/v1/entries/mbg.json?count=512",
            NightscoutFollowerJournalEndpoints.fingersticks(baseUrl),
        )
        assertTrue(
            NightscoutFollowerHistoryPaging.endpoint(baseUrl, null, null)
                .endsWith("/api/v1/entries/sgv.json?count=1000"),
        )
    }

    @Test
    fun sgvAndMbgAreSeparatedByTypeFilterRatherThanBySeparatePaths() {
        // v3 has no entries/sgv path; the collection is one, filtered by type.
        val sgv = NightscoutFollowerHistoryPaging.endpoint(baseUrl, null, null, useV3 = true)
        assertTrue(sgv, sgv.startsWith("$baseUrl/api/v3/entries?"))
        assertTrue(sgv, sgv.contains("type%24eq=sgv"))

        val mbg = NightscoutFollowerJournalEndpoints.fingersticks(baseUrl, useV3 = true)
        assertTrue(mbg, mbg.startsWith("$baseUrl/api/v3/entries?"))
        assertTrue(mbg, mbg.contains("type%24eq=mbg"))
    }

    @Test
    fun pagingBoundsTravelAsV3DateFilters() {
        val page = NightscoutFollowerHistoryPaging.endpoint(baseUrl, 1_000L, 5_000L, useV3 = true)
        assertTrue(page, page.contains("date%24gte=1000"))
        assertTrue(page, page.contains("date%24lt=5000"))
        assertTrue(page, page.contains("limit=1000"))
    }

    @Test
    fun deviceStatusFollowsTheSameV3Shape() {
        val url = NightscoutFollowerV3.deviceStatusUrl(baseUrl, 5)
        assertTrue(url, url.startsWith("$baseUrl/api/v3/devicestatus?"))
        assertTrue(url, url.contains("limit=5"))
        assertFalse(url, url.contains(".json"))
    }

    // -- envelope ----------------------------------------------------------

    @Test
    fun theV3ResultEnvelopeIsPeeledForParsersThatExpectAnArray() {
        val body = """{"status":200,"result":[{"identifier":"a"},{"identifier":"b"}]}"""
        val array = JSONArray(NightscoutFollowerV3.arrayBody(body))
        assertEquals(2, array.length())
        assertEquals("a", array.getJSONObject(0).getString("identifier"))
    }

    @Test
    fun aBodyThatIsAlreadyAnArrayPassesThrough() {
        val array = """[{"identifier":"a"}]"""
        assertEquals(array, NightscoutFollowerV3.arrayBody(array))
    }

    @Test
    fun anEnvelopeWithoutResultBecomesEmptyRatherThanAParseFailure() {
        assertEquals("[]", NightscoutFollowerV3.arrayBody("""{"status":403}"""))
    }

    // -- what a refusal reports --------------------------------------------

    @Test
    fun aRefusedReadReportsThePermissionTheServerNamed() {
        // A follower token without the read role answers exactly this.
        assertEquals(
            "Missing permission api:entries:read",
            NightscoutFollowerV3.serverMessage("""{"status":403,"message":"Missing permission api:entries:read"}"""),
        )
    }

    @Test
    fun aBodyWithoutAMessageStillReportsSomething() {
        assertEquals("Unauthorized", NightscoutFollowerV3.serverMessage("Unauthorized"))
    }

    // -- auth --------------------------------------------------------------

    @Test
    fun secretsThatAreAlreadyHeaderValuesAreLeftToTheExistingAuthPath() {
        val now = 1_000_000L
        // No exchange to make, so the caller falls back to applyAuth rather than
        // spending a round trip or sending a header it cannot build.
        assertNull(NightscoutFollowerV3.authorizationHeader(baseUrl, "Bearer abc", now))
        assertNull(NightscoutFollowerV3.authorizationHeader(baseUrl, "token=abc", now))
        assertNull(NightscoutFollowerV3.authorizationHeader(baseUrl, "a".repeat(40), now))
        assertNull(NightscoutFollowerV3.authorizationHeader(baseUrl, "   ", now))
    }

    // -- repeating failure logging -----------------------------------------

    @Test
    fun anUnchangedFailureIsLoggedOncePerInterval() {
        val log = RepeatedFollowerError(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 403", start))
        assertEquals(-1, log.suppressedSince("HTTP 403", start + 30_000L))
        assertEquals(-1, log.suppressedSince("HTTP 403", start + 60_000L))
        assertEquals(2, log.suppressedSince("HTTP 403", start + 5 * 60_000L))
    }

    @Test
    fun aDifferentFailureIsNeverHiddenBehindTheOldOnesInterval() {
        val log = RepeatedFollowerError(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 403", start))
        assertEquals(0, log.suppressedSince("HTTP 500", start + 1_000L))
    }

    @Test
    fun aSuccessEndsTheEpisodeAndABackwardsClockDoesNotSilenceIt() {
        val log = RepeatedFollowerError(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 403", start))
        log.reset()
        assertEquals(0, log.suppressedSince("HTTP 403", start + 1_000L))
        assertEquals(0, log.suppressedSince("HTTP 403", start - 60_000L))
    }
}
