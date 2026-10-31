package tk.glucodata.data.journal

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearJournalSync

class WearJournalIobDisplayTests {

    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun insulin(agoMin: Int, units: Float, pending: Boolean = false, withCurve: Boolean = true) =
        WearJournalSync.Entry(
            timestampMs = now - agoMin * minute, id = 1L + agoMin, type = WearJournalSync.TYPE_INSULIN,
            amount = units, title = "i",
            curveMinutes = if (withCurve && !pending) intArrayOf(0, 120, 240) else IntArray(0),
            curveActivity = if (withCurve && !pending) floatArrayOf(0f, 1f, 0f) else FloatArray(0),
            pending = pending,
        )

    private fun carbs(agoMin: Int, grams: Float, minutes: Int?, pending: Boolean = false) =
        WearJournalSync.Entry(
            timestampMs = now - agoMin * minute, id = 100L + agoMin, type = WearJournalSync.TYPE_CARBS,
            amount = grams, title = "c", absorptionMinutes = minutes, pending = pending,
        )

    private fun journal(vararg e: WearJournalSync.Entry) =
        WearJournalSync.Journal(enabled = true, entries = e.toList())

    private fun display(j: WearJournalSync.Journal) =
        WearJournalIobDisplay.of(j, now, Locale.US)

    @Test
    fun emptyJournalShowsNothing() {
        val d = display(journal())
        assertNull(d.iob); assertNull(d.eiob); assertNull(d.cob)
        assertTrue(d.isEmpty)
    }

    @Test
    fun noPeerYetJournalShowsNothing() {
        assertTrue(display(WearJournalSync.Journal()).isEmpty)
    }

    @Test
    fun freshDoseShowsIobAndFreshCarbsShowCob() {
        val d = display(journal(insulin(0, 4f), carbs(0, 40f, 120)))
        assertEquals("4", d.iob)          // whole numbers lose the decimal, as on the phone
        assertEquals("40", d.cob)
        assertFalse(d.isEmpty)
    }

    @Test
    fun valuesBelowOneUseOneDecimalAndHalfCountsAsFraction() {
        val d = display(journal(carbs(0, 0.5f, 60)))
        assertEquals("0.5", d.cob)
    }

    @Test
    fun valueUnderPointZeroFiveIsHidden() {
        // 0.04 g of carbs would render as "0": hidden, not shown as zero.
        assertNull(display(journal(carbs(0, 0.04f, 60))).cob)
        assertEquals("0.1", display(journal(carbs(0, 0.05f, 60))).cob)
    }

    @Test
    fun fullyAbsorbedCarbsAndLongGoneDoseAreHidden() {
        val d = display(journal(insulin(600, 4f), carbs(600, 40f, 120)))
        assertNull(d.iob); assertNull(d.eiob); assertNull(d.cob)
    }

    @Test
    fun cobIsHiddenNotZeroWhenACountedCarbEntryHasNoMinutes() {
        val d = display(journal(insulin(0, 2f), carbs(0, 30f, null)))
        assertNull(d.cob)
        assertEquals("2", d.iob)
    }

    @Test
    fun pendingEntriesAreNotCountedAndDoNotWithholdCob() {
        val d = display(
            journal(
                insulin(0, 5f, pending = true),
                carbs(0, 50f, null, pending = true),
                carbs(0, 20f, 120),
            )
        )
        assertNull(d.iob)
        assertEquals("20", d.cob)
    }

    @Test
    fun allPendingJournalShowsNothing() {
        val d = display(journal(insulin(0, 5f, pending = true), carbs(0, 50f, null, pending = true)))
        assertTrue(d.isEmpty)
    }

    @Test
    fun nonFiniteAmountsDoNotCrashOrShow() {
        val d = display(journal(insulin(0, Float.NaN), carbs(0, Float.POSITIVE_INFINITY, 60)))
        assertTrue(d.isEmpty)
    }

    @Test
    fun decimalSeparatorFollowsTheGivenLocale() {
        val j = journal(carbs(0, 0.5f, 60))
        assertEquals("0,5", WearJournalIobDisplay.of(j, now, Locale.GERMANY).cob)
    }

    @Test
    fun formatMatchesPhoneRule() {
        assertNull(WearJournalIobDisplay.format(0.049f, Locale.US))
        assertNull(WearJournalIobDisplay.format(Float.NaN, Locale.US))
        assertNull(WearJournalIobDisplay.format(-1f, Locale.US))
        assertEquals("1", WearJournalIobDisplay.format(1.02f, Locale.US))
        assertEquals("1.5", WearJournalIobDisplay.format(1.5f, Locale.US))
        assertEquals("12.3", WearJournalIobDisplay.format(12.3f, Locale.US))
    }

    // ---- truncation ---------------------------------------------------------------------------

    private fun note(agoMin: Int, n: Int) = WearJournalSync.Entry(
        timestampMs = now - agoMin * minute, id = 1000L + n, type = WearJournalSync.TYPE_NOTE,
        amount = Float.NaN, title = "n$n",
    )

    private fun notes(count: Int, oldestAgoMin: Int): List<WearJournalSync.Entry> =
        (0 until count).map { note(oldestAgoMin * it / (count - 1).coerceAtLeast(1), it) }

    private fun fullJournal(oldestAgoMin: Int, vararg extra: WearJournalSync.Entry) =
        WearJournalSync.Journal(
            enabled = true,
            entries = notes(WearJournalSync.SERVED_ENTRY_CAP - extra.size, oldestAgoMin) + extra.toList()
        )

    @Test
    fun belowTheCapIsNeverTruncated() {
        val j = WearJournalSync.Journal(enabled = true, entries = notes(WearJournalSync.SERVED_ENTRY_CAP - 1, 10))
        assertFalse(WearJournalIob.isPossiblyTruncated(j, now))
    }

    @Test
    fun atTheCapWithAnOldOldestEntryIsNotTruncated() {
        assertFalse(WearJournalIob.isPossiblyTruncated(fullJournal(oldestAgoMin = 361), now))
    }

    @Test
    fun atTheCapWithARecentOldestEntryIsTruncated() {
        assertTrue(WearJournalIob.isPossiblyTruncated(fullJournal(oldestAgoMin = 200), now))
    }

    @Test
    fun aLongCustomCurveExtendsTheHorizon() {
        val longDose = WearJournalSync.Entry(
            timestampMs = now - 10 * minute, id = 5L, type = WearJournalSync.TYPE_INSULIN,
            amount = 2f, title = "i",
            curveMinutes = intArrayOf(0, 300, 720), curveActivity = floatArrayOf(0f, 1f, 0f),
        )
        // 500 minutes old is outside the 360 minute COB horizon but inside the 720 minute curve.
        assertFalse(WearJournalIob.isPossiblyTruncated(fullJournal(500), now))
        assertTrue(WearJournalIob.isPossiblyTruncated(fullJournal(500, longDose), now))
    }

    @Test
    fun aTruncatedListShowsNoTotals() {
        val j = fullJournal(200, insulin(0, 4f), carbs(0, 40f, 120))
        assertTrue(display(j).isEmpty)
        assertFalse(display(fullJournal(361, insulin(0, 4f), carbs(0, 40f, 120))).isEmpty)
    }
}
