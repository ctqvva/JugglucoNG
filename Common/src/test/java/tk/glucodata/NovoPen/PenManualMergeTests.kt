package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType

/**
 * The merge rewrites insulin somebody wrote in their own journal, so the cases that matter
 * are the ones where the sequence does not line up: a wrong pairing there moves an entry to
 * the wrong time and hides an injection that was never confirmed.
 */
class PenManualMergeTests {

    private val serial = "G252101365"
    private val anchor = 1_700_000_000L
    private val rapid = 7L
    private val basal = 9L
    private val minute = 60L

    private fun dose(relative: Long, units: Float, atMinute: Long, priming: Boolean = false) =
        PenDose(
            relativeSeconds = relative,
            timestampSeconds = anchor + atMinute * minute,
            units = units,
            flags = 0,
            priming = priming,
        )

    private fun entry(id: Long, units: Float, atMinute: Long, preset: Long? = rapid) =
        ManualInsulinEntry(id, anchor + atMinute * minute, units, preset)

    private fun plan(
        doses: List<PenDose>,
        entries: List<ManualInsulinEntry>,
        penInsulin: Long? = rapid,
    ) = PenManualMergePlanner.plan(serial, doses, entries, penInsulin)

    /** The case this exists for: written down, injected a minute later, scanned afterwards. */
    @Test
    fun aDoseWrittenDownBeforeInjectingBecomesTheSameEntry() {
        val dose = dose(relative = 3_600, units = 4f, atMinute = 10)
        val written = entry(id = 42, units = 4f, atMinute = 8)

        val plan = plan(listOf(dose), listOf(written))

        assertEquals(1, plan.merges.size)
        val merge = plan.merges.single()
        assertEquals(42L, merge.entryId)
        assertEquals(PenSourceIds.stable(serial, dose), merge.sourceRecordId)
        // The pen's time is the measured one, so the entry moves onto it.
        assertEquals(dose.timestampSeconds, merge.timestampSeconds)
        assertEquals(3_600L, merge.doseRelativeSeconds)
        assertNull(plan.alignmentBreak)
    }

    @Test
    fun threeInARowMergeInOrder() {
        val doses = listOf(
            dose(relative = 100, units = 6f, atMinute = 0),
            dose(relative = 200, units = 4f, atMinute = 300),
            dose(relative = 300, units = 8f, atMinute = 700),
        )
        val entries = listOf(
            entry(id = 1, units = 6f, atMinute = -2),
            entry(id = 2, units = 4f, atMinute = 297),
            entry(id = 3, units = 8f, atMinute = 695),
        )

        val plan = plan(doses, entries)

        assertNull(plan.alignmentBreak)
        assertEquals(listOf(1L, 2L, 3L), plan.merges.map(PenManualMerge::entryId))
        assertEquals(listOf(100L, 200L, 300L), plan.merges.map(PenManualMerge::doseRelativeSeconds))
    }

    /** Air shots are in the pen's log and never in the journal, so they cannot take a place. */
    @Test
    fun anAirShotBetweenTwoDosesDoesNotShiftTheSequence() {
        val doses = listOf(
            dose(relative = 100, units = 6f, atMinute = 0),
            dose(relative = 150, units = 1f, atMinute = 299, priming = true),
            dose(relative = 200, units = 4f, atMinute = 300),
        )
        val entries = listOf(
            entry(id = 1, units = 6f, atMinute = -1),
            entry(id = 2, units = 4f, atMinute = 298),
        )

        val plan = plan(doses, entries)

        assertNull(plan.alignmentBreak)
        assertEquals(listOf(1L, 2L), plan.merges.map(PenManualMerge::entryId))
        assertTrue(plan.merges.none { it.doseRelativeSeconds == 150L })
    }

    /**
     * An entry that was written and never injected — the reader changed their mind. It sits
     * inside the stretch the read covers, so it takes a place in the walk that no dose has,
     * and everything after it would shift by one.
     */
    @Test
    fun anEntryWithoutADoseStopsTheWalkAndTheRestIsMatchedByTimeAndAmount() {
        val doses = listOf(
            dose(relative = 200, units = 4f, atMinute = 300),
            dose(relative = 300, units = 8f, atMinute = 700),
        )
        val entries = listOf(
            entry(id = 1, units = 6f, atMinute = 299), // decided against, never injected
            entry(id = 2, units = 4f, atMinute = 302),
            entry(id = 3, units = 8f, atMinute = 699),
        )

        val plan = plan(doses, entries)

        val stop = plan.alignmentBreak
        assertNotNull("the walk has to notice the extra entry", stop)
        assertEquals(0, stop!!.position)
        assertEquals(4f, stop.doseUnits, 0.001f)
        assertEquals(6f, stop.entryUnits, 0.001f)
        // Nothing is merged onto the abandoned entry, and the two real ones still pair.
        assertTrue(plan.merges.none { it.entryId == 1L })
        assertEquals(listOf(2L, 3L), plan.merges.map(PenManualMerge::entryId).sorted())
        assertEquals(
            listOf(200L, 300L),
            plan.merges.map(PenManualMerge::doseRelativeSeconds).sorted(),
        )
    }

    /** 4 U written, 4.5 U given: the amounts are the checksum, so this is not a merge. */
    @Test
    fun anAmountThatDisagreesIsNeverMergedSilently() {
        val doses = listOf(dose(relative = 200, units = 4.5f, atMinute = 300))
        val entries = listOf(entry(id = 2, units = 4f, atMinute = 298))

        val plan = plan(doses, entries)

        assertTrue("nothing may be merged", plan.isEmpty)
        val stop = plan.alignmentBreak
        assertNotNull(stop)
        assertEquals(4.5f, stop!!.doseUnits, 0.001f)
        assertEquals(4f, stop.entryUnits, 0.001f)
    }

    /**
     * The order proposes the pairing, the clock still has to agree: an entry this far from
     * the dose the sequence hands it is not that dose being written down.
     */
    @Test
    fun aPairTooFarApartStopsTheWalk() {
        val doses = listOf(
            dose(relative = 100, units = 4f, atMinute = 0),
            dose(relative = 200, units = 4f, atMinute = 100),
        )
        // Written between the two, so it is inside the read the scan covers, but nowhere
        // near the dose the order hands it.
        val entries = listOf(entry(id = 1, units = 4f, atMinute = 50))

        val plan = plan(doses, entries)

        assertTrue(plan.isEmpty)
        val stop = plan.alignmentBreak
        assertNotNull(stop)
        assertEquals(50 * minute, stop!!.secondsApart)
    }

    /** An entry from outside the stretch the read covers never enters the walk at all. */
    @Test
    fun anEntryOutsideTheReadIsIgnored() {
        val doses = listOf(dose(relative = 100, units = 6f, atMinute = 0))
        val entries = listOf(entry(id = 1, units = 6f, atMinute = -60))

        val plan = plan(doses, entries)

        assertTrue(plan.isEmpty)
        assertNull(plan.alignmentBreak)
    }

    /** A spare pen of the same insulin: its entries interleave, so the walk has to give up. */
    @Test
    fun aSecondPenOfTheSameInsulinDoesNotShiftTheAlignment() {
        // This pen gave 6 U and 8 U; the spare gave 3 U in between, written down like the rest.
        val doses = listOf(
            dose(relative = 100, units = 6f, atMinute = 0),
            dose(relative = 300, units = 8f, atMinute = 700),
        )
        val entries = listOf(
            entry(id = 1, units = 6f, atMinute = -1),
            entry(id = 2, units = 3f, atMinute = 350), // the spare pen's dose
            entry(id = 3, units = 8f, atMinute = 698),
        )

        val plan = plan(doses, entries)

        assertNotNull("the spare pen's entry has to break the walk", plan.alignmentBreak)
        assertEquals(1, plan.alignmentBreak!!.position)
        // The spare pen's entry is left alone, and the 8 U dose still finds its own.
        assertTrue(plan.merges.none { it.entryId == 2L })
        assertEquals(listOf(1L, 3L), plan.merges.map(PenManualMerge::entryId).sorted())
    }

    @Test
    fun withoutHandWrittenEntriesNothingHappens() {
        val doses = listOf(dose(relative = 100, units = 6f, atMinute = 0))

        assertTrue(plan(doses, emptyList()).isEmpty)
        assertNull(plan(doses, emptyList()).alignmentBreak)
        assertTrue(plan(emptyList(), listOf(entry(id = 1, units = 6f, atMinute = 0))).isEmpty)
    }

    /** Bolus and basal are told apart by the insulin, which is the only thing that can. */
    @Test
    fun anotherInsulinIsNeverMerged() {
        val doses = listOf(dose(relative = 100, units = 6f, atMinute = 0))
        val entries = listOf(entry(id = 1, units = 6f, atMinute = -1, preset = basal))

        assertTrue(plan(doses, entries).isEmpty)
    }

    /** A pen read for the first time has no insulin yet, so it cannot know what it gave. */
    @Test
    fun aPenWithNoInsulinRecordedMergesNothing() {
        val doses = listOf(dose(relative = 100, units = 6f, atMinute = 0))
        val entries = listOf(entry(id = 1, units = 6f, atMinute = -1))

        assertTrue(plan(doses, entries, penInsulin = null).isEmpty)
        assertTrue(plan(doses, entries, penInsulin = 0L).isEmpty)
    }

    /** The window is a boundary, and a boundary is where a rule quietly changes meaning. */
    @Test
    fun exactlyTheWindowStillMerges() {
        val doses = listOf(dose(relative = 100, units = 4f, atMinute = 0))
        val onTheLine = ManualInsulinEntry(1, anchor - PenManualMergePlanner.MATCH_WINDOW_SECONDS, 4f, rapid)
        val justOver = ManualInsulinEntry(1, anchor - PenManualMergePlanner.MATCH_WINDOW_SECONDS - 1, 4f, rapid)

        assertEquals(1, plan(doses, listOf(onTheLine)).merges.size)
        assertTrue(plan(doses, listOf(justOver)).isEmpty)
    }

    /**
     * What may be considered at all. A dose already imported is a pen row, and reading it
     * back as somebody's hand-written record of itself would merge a dose onto itself.
     */
    @Test
    fun onlyHandWrittenInsulinWithAnAmountIsACandidate() {
        fun journalEntry(
            id: Long,
            type: JournalEntryType = JournalEntryType.INSULIN,
            source: JournalEntrySource = JournalEntrySource.MANUAL,
            amount: Float? = 4f,
        ) = JournalEntry(
            id = id,
            timestamp = (anchor + 60) * 1000L,
            sensorSerial = null,
            type = type,
            title = "Rapid",
            note = null,
            amount = amount,
            glucoseValueMgDl = null,
            durationMinutes = null,
            intensity = null,
            insulinPresetId = rapid,
            foodId = null,
            proteinGrams = null,
            fatGrams = null,
            source = source,
            sourceRecordId = null,
            createdAt = 0L,
            updatedAt = 0L,
        )

        val candidates = PenManualMergePlanner.candidates(
            listOf(
                journalEntry(id = 1),
                journalEntry(id = 2, source = JournalEntrySource.PEN),
                journalEntry(id = 3, type = JournalEntryType.CARBS),
                journalEntry(id = 4, amount = null),
                journalEntry(id = 5, source = JournalEntrySource.NIGHTSCOUT),
            ),
        )

        assertEquals(listOf(1L), candidates.map(ManualInsulinEntry::id))
        // Seconds, not milliseconds: the planner compares against the pen's own clock.
        assertEquals(anchor + 60, candidates.single().timestampSeconds)
        assertEquals(rapid, candidates.single().insulinPresetId)
    }

    /** Tenths, like the pen reports them: 4.0 and 4.04 are the same dose, 4.0 and 4.1 are not. */
    @Test
    fun amountsAreComparedInTenths() {
        val dose = dose(relative = 100, units = 4.04f, atMinute = 0)

        assertEquals(1, plan(listOf(dose), listOf(entry(id = 1, units = 4f, atMinute = 0))).merges.size)
        assertTrue(plan(listOf(dose), listOf(entry(id = 1, units = 4.1f, atMinute = 0))).isEmpty)
    }
}
