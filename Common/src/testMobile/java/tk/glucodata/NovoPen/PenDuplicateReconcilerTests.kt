package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler deletes insulin out of somebody's journal, so the cases that matter are
 * the ones where it would delete an injection that really happened.
 */
class PenDuplicateReconcilerTests {

    private val serial = "G252101365"
    private val anchor = 1_700_000_000L

    private fun dose(relative: Long, units: Float, at: Long = anchor) =
        PenDose(relativeSeconds = relative, timestampSeconds = at + relative, units = units, flags = 0)

    private fun entry(id: Long, timestampSeconds: Long, units: Float, recordId: String) =
        PenJournalEntry(id, timestampSeconds, units, recordId)

    private fun legacy(id: Long, timestampSeconds: Long, units: Float) =
        entry(id, timestampSeconds, units, "pen:$serial:$timestampSeconds")

    private fun stable(id: Long, timestampSeconds: Long, units: Float, relative: Long) =
        entry(id, timestampSeconds, units, "pen:$serial:rel:$relative")

    /** The bug in issue #195: a second tap renamed every dose and so re-imported it. */
    @Test
    fun aDoseKeepsItsNameWhenTheNextScanAnchorsElsewhere() {
        val raw = byteArrayOf(0x00, 0x00, 0x02, 0x58, 0xFF.toByte(), 0x00, 0x00, 0x55, 0x08, 0x00, 0x00, 0x00)
        val firstScan = PenDoseParser.parse(anchor, raw, anchor + 10_000)
        val secondScan = PenDoseParser.parse(anchor + 7, raw, anchor + 10_000)

        assertEquals(
            PenSourceIds.stable(serial, firstScan.single()),
            PenSourceIds.stable(serial, secondScan.single()),
        )
        // The displayed time is still allowed to move; only the identity may not.
        assertEquals(7L, secondScan.single().timestampSeconds - firstScan.single().timestampSeconds)
    }

    @Test
    fun renamesALegacyEntryOntoTheDoseItStandsFor() {
        val doses = listOf(dose(600, 8.5f))
        val plan = PenDuplicateReconciler.plan(serial, doses, listOf(legacy(1, anchor + 597, 8.5f)))

        assertEquals(mapOf(1L to "pen:$serial:rel:600"), plan.adopt)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun dropsTheCopyASecondScanLeftBehind() {
        // Same injection, imported twice three seconds apart because the anchor moved.
        // The row nearest to where the pen says the injection was is the one that stays.
        val doses = listOf(dose(600, 8.5f))
        val entries = listOf(legacy(1, anchor + 597, 8.5f), legacy(2, anchor + 600, 8.5f))

        val plan = PenDuplicateReconciler.plan(serial, doses, entries)

        assertEquals(mapOf(2L to "pen:$serial:rel:600"), plan.adopt)
        assertEquals(listOf(1L), plan.delete)
    }

    @Test
    fun collapsesThreeGenerationsOfTheSameDose() {
        val doses = listOf(dose(600, 4.0f))
        val entries = listOf(
            legacy(1, anchor + 598, 4.0f),
            legacy(2, anchor + 600, 4.0f),
            legacy(3, anchor + 603, 4.0f),
        )

        val plan = PenDuplicateReconciler.plan(serial, doses, entries)

        assertEquals(1, plan.adopt.size)
        assertEquals(2, plan.delete.size)
        assertTrue(plan.adopt.keys.first() !in plan.delete)
    }

    @Test
    fun keepsTwoRealInjectionsOfTheSameSizeAMinuteApart() {
        // The 14:03 / 14:04 pair from the report: the pen lists both, so both are real.
        val doses = listOf(dose(600, 6.0f), dose(660, 6.0f))
        val entries = listOf(legacy(1, anchor + 600, 6.0f), legacy(2, anchor + 660, 6.0f))

        val plan = PenDuplicateReconciler.plan(serial, doses, entries)

        assertEquals(2, plan.adopt.size)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun dropsTheLegacyCopyOfADoseAlreadyStoredUnderItsStableId() {
        val doses = listOf(dose(600, 8.5f))
        val entries = listOf(
            stable(1, anchor + 600, 8.5f, relative = 600),
            legacy(2, anchor + 604, 8.5f),
        )

        val plan = PenDuplicateReconciler.plan(serial, doses, entries)

        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf(2L), plan.delete)
    }

    @Test
    fun leavesAnEntryWhoseAmountTheReaderEdited() {
        // 8.5 U corrected to 9.0 by hand: nothing in the pen matches it any more, and it
        // has no same-sized neighbour, so it is somebody's record, not a duplicate.
        val doses = listOf(dose(600, 8.5f))
        val plan = PenDuplicateReconciler.plan(serial, doses, listOf(legacy(1, anchor + 600, 9.0f)))

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun leavesEntriesOutsideTheStretchTheScanCovered() {
        val doses = listOf(dose(600, 8.5f))
        val far = legacy(1, anchor + 600 - PenDuplicateReconciler.MATCH_WINDOW_SECONDS - 60, 8.5f)

        val plan = PenDuplicateReconciler.plan(serial, doses, listOf(far))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun ignoresEntriesFromAnotherPen() {
        val doses = listOf(dose(600, 8.5f))
        val other = entry(1, anchor + 600, 8.5f, "pen:OTHER123:${anchor + 600}")

        assertTrue(PenDuplicateReconciler.plan(serial, doses, listOf(other)).isEmpty)
    }

    @Test
    fun doesNothingWhenEverythingAlreadyCarriesAStableId() {
        val doses = listOf(dose(600, 8.5f))
        val entries = listOf(stable(1, anchor + 600, 8.5f, relative = 600))

        assertTrue(PenDuplicateReconciler.plan(serial, doses, entries).isEmpty)
    }

    @Test
    fun doesNothingWithoutDoses() {
        assertTrue(PenDuplicateReconciler.plan(serial, emptyList(), listOf(legacy(1, anchor, 8.5f))).isEmpty)
    }

    @Test
    fun givesEachDoseItsOwnEntryRatherThanPilingOntoTheNearestOne() {
        val doses = listOf(dose(600, 2.0f), dose(700, 2.0f), dose(800, 2.0f))
        val entries = listOf(
            legacy(1, anchor + 601, 2.0f),
            legacy(2, anchor + 701, 2.0f),
            legacy(3, anchor + 801, 2.0f),
        )

        val plan = PenDuplicateReconciler.plan(serial, doses, entries)

        assertEquals(3, plan.adopt.size)
        assertEquals(
            listOf("pen:$serial:rel:600", "pen:$serial:rel:700", "pen:$serial:rel:800").sorted(),
            plan.adopt.values.sorted(),
        )
        assertTrue(plan.delete.isEmpty())
    }
}
