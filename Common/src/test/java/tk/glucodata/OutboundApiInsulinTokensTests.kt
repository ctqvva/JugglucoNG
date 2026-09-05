package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token vocabulary an API destination gets for insulin types (issue #196):
 * one family per active type, named after the type, so a chat message can say
 * which insulin was logged instead of only the aggregate IOB.
 */
class OutboundApiInsulinTokensTests {

    private val atMillis = 1_700_000_000_000L

    private fun time(millis: Long) = "T${millis / 1000}"

    private fun typeJson(
        slug: String,
        name: String = slug,
        lastUnits: Double? = null,
        lastTimestamp: Long = 0L,
        iob: Double? = null,
        todayUnits: Double? = null
    ): JSONObject = JSONObject()
        .put("slug", slug)
        .put("name", name)
        .put("last_units", lastUnits)
        .put("last_timestamp", lastTimestamp)
        .put("iob", iob)
        .put("today_units", todayUnits)

    private fun snapshot(vararg types: JSONObject): JSONObject =
        JSONObject().put(OutboundApiInsulinTokens.JSON_ARRAY, JSONArray().also { array ->
            types.forEach(array::put)
        })

    @Test
    fun stockPresetNamesSlugToTheTokensTheIssueAsksFor() {
        assertEquals("long_acting_insulin", OutboundApiInsulinTokens.slugFor("Long acting insulin"))
        assertEquals("rapid_acting_insulin", OutboundApiInsulinTokens.slugFor("Rapid acting insulin"))
    }

    @Test
    fun punctuationCollapsesAndNeverLeadsOrTrails() {
        assertEquals("humulin_r_novolin_r", OutboundApiInsulinTokens.slugFor("Humulin R/Novolin R"))
        assertEquals("fiasp", OutboundApiInsulinTokens.slugFor("  Fiasp!  "))
        assertEquals("nph_100", OutboundApiInsulinTokens.slugFor("NPH (100)"))
    }

    /** A localized preset name has to stay usable: the chips insert it verbatim. */
    @Test
    fun nonLatinNamesKeepTheirOwnScript() {
        assertEquals(
            "инсулин_длительного_действия",
            OutboundApiInsulinTokens.slugFor("Инсулин длительного действия")
        )
    }

    @Test
    fun namesThatSlugAlikeStillGetDistinctTokens() {
        val slugs = OutboundApiInsulinTokens.assignSlugs(listOf("Basal", "basal", "Basal!", "Bolus"))
        assertEquals(listOf("basal", "basal_2", "basal_3", "bolus"), slugs)
    }

    @Test
    fun aNameWithNothingToSlugStillGetsAToken() {
        assertEquals(listOf("insulin", "insulin_2"), OutboundApiInsulinTokens.assignSlugs(listOf("***", "###")))
    }

    @Test
    fun everyTypeOffersItsFullTokenFamily() {
        assertEquals(
            listOf("{basal}", "{basal_time}", "{basal_ago}", "{basal_iob}", "{basal_today}"),
            OutboundApiInsulinTokens.tokensForSlug("basal")
        )
    }

    @Test
    fun aLoggedDoseFillsInEveryTokenOfItsType() {
        val doseAt = atMillis - 90 * 60_000L
        val types = OutboundApiInsulinTokens.parse(
            snapshot(
                typeJson(
                    slug = "long_acting_insulin",
                    lastUnits = 12.0,
                    lastTimestamp = doseAt,
                    iob = 9.5,
                    todayUnits = 12.0
                )
            )
        )
        val rendered = OutboundApiInsulinTokens.expand(
            template = "{long_acting_insulin} U at {long_acting_insulin_time} " +
                "({long_acting_insulin_ago} min, {long_acting_insulin_iob} left, " +
                "{long_acting_insulin_today} today)",
            types = types,
            atMillis = atMillis,
            formatTime = ::time
        )
        assertEquals("12 U at ${time(doseAt)} (90 min, 9.5 left, 12 today)", rendered)
    }

    /** No dose is not a zero dose: the dose tokens go empty, the totals read 0. */
    @Test
    fun aTypeWithNoDoseRendersEmptyDoseTokens() {
        val types = OutboundApiInsulinTokens.parse(snapshot(typeJson(slug = "rapid_acting_insulin")))
        val rendered = OutboundApiInsulinTokens.expand(
            template = "[{rapid_acting_insulin}][{rapid_acting_insulin_time}]" +
                "[{rapid_acting_insulin_ago}][{rapid_acting_insulin_iob}][{rapid_acting_insulin_today}]",
            types = types,
            atMillis = atMillis,
            formatTime = ::time
        )
        assertEquals("[][][][0][0]", rendered)
    }

    @Test
    fun fractionalUnitsKeepTheirDecimalsAndWholeUnitsDoNotGrowAny() {
        val types = OutboundApiInsulinTokens.parse(
            snapshot(
                typeJson(slug = "a", lastUnits = 2.5, lastTimestamp = atMillis, iob = 0.25, todayUnits = 8.0)
            )
        )
        assertEquals(
            "2.5|0.25|8",
            OutboundApiInsulinTokens.expand("{a}|{a_iob}|{a_today}", types, atMillis, ::time)
        )
    }

    /** One type's tokens must not be eaten by another whose slug is a prefix. */
    @Test
    fun aSlugThatPrefixesAnotherDoesNotSwallowIt() {
        val types = OutboundApiInsulinTokens.parse(
            snapshot(
                typeJson(slug = "basal", lastUnits = 10.0, lastTimestamp = atMillis),
                typeJson(slug = "basal_insulin", lastUnits = 4.0, lastTimestamp = atMillis)
            )
        )
        assertEquals(
            "10 4",
            OutboundApiInsulinTokens.expand("{basal} {basal_insulin}", types, atMillis, ::time)
        )
    }

    @Test
    fun aDoseEnteredAheadOfTheReadingNeverReportsNegativeMinutes() {
        val types = OutboundApiInsulinTokens.parse(
            snapshot(typeJson(slug = "a", lastUnits = 3.0, lastTimestamp = atMillis + 5 * 60_000L))
        )
        assertEquals("0", OutboundApiInsulinTokens.expand("{a_ago}", types, atMillis, ::time))
    }

    @Test
    fun unknownTokensAreLeftAloneWhenNoTypeClaimsThem() {
        val types = OutboundApiInsulinTokens.parse(snapshot(typeJson(slug = "a")))
        assertEquals("{b} {a_bogus}", OutboundApiInsulinTokens.expand("{b} {a_bogus}", types, atMillis, ::time))
    }

    @Test
    fun aSnapshotWithoutTheArrayParsesToNothing() {
        assertTrue(OutboundApiInsulinTokens.parse(null).isEmpty())
        assertTrue(OutboundApiInsulinTokens.parse(JSONObject().put("iob", 1.0)).isEmpty())
    }

    /** The journal read costs a Room query per send, so only pay for it when asked. */
    @Test
    fun onlyTemplatesThatNeedTheJournalTriggerASnapshotRead() {
        assertTrue(OutboundApi.needsJournalSnapshot("{long_acting_insulin} U"))
        assertTrue(OutboundApi.needsJournalSnapshot("IOB {iob}"))
        assertTrue(OutboundApi.needsJournalSnapshot("{journal_cob}"))
        assertFalse(OutboundApi.needsJournalSnapshot("{value} {unit} {trend_arrow} {time}"))
        assertFalse(OutboundApi.needsJournalSnapshot("no tokens at all"))
    }
}
