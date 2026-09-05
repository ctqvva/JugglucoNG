package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What confirming the sheet writes. The rule that matters is exclusivity: a dose that takes
 * over an entry the reader wrote by hand must not also be written as a row of its own, or
 * the merge creates the duplicate it exists to prevent.
 */
class PenImportPlanTests {

    private val anchor = 1_700_000_000L

    private fun dose(relative: Long, units: Float = 4f) =
        PenDose(relative, anchor + relative, units, flags = 0)

    private fun proposal(dose: PenDose, entryId: Long) = PenManualMerge(
        entryId = entryId,
        sourceRecordId = "pen:S:rel:${dose.relativeSeconds}",
        timestampSeconds = dose.timestampSeconds,
        doseRelativeSeconds = dose.relativeSeconds,
        entryTimestampSeconds = dose.timestampSeconds - 120,
        entryUnits = dose.units,
    )

    @Test
    fun aDoseThatTakesOverAnEntryIsNotAlsoWritten() {
        val merged = dose(relative = 100)
        val plain = dose(relative = 200, units = 6f)

        val plan = PenImportPlan.of(
            listOf(merged, plain),
            mapOf(merged.relativeSeconds to proposal(merged, entryId = 7)),
        ) { false }

        assertEquals(listOf(7L), plan.adopt.map(PenManualMerge::entryId))
        assertEquals(listOf(200L), plan.insert.map(PenDose::relativeSeconds))
        // The one that was taken over appears exactly once, on the adopt side.
        assertTrue(plan.insert.none { it.relativeSeconds == merged.relativeSeconds })
    }

    @Test
    fun aDoseWithoutAProposalIsWritten() {
        val plain = dose(relative = 100)

        val plan = PenImportPlan.of(listOf(plain), emptyMap()) { false }

        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf(100L), plan.insert.map(PenDose::relativeSeconds))
    }

    /** An earlier scan already wrote it: neither written again nor merged onto anything. */
    @Test
    fun aDoseTheJournalAlreadyHoldsIsLeftAlone() {
        val known = dose(relative = 100)

        val plan = PenImportPlan.of(
            listOf(known),
            mapOf(known.relativeSeconds to proposal(known, entryId = 7)),
        ) { it.relativeSeconds == 100L }

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.insert.isEmpty())
    }

    /** A proposal for a dose nobody confirmed is not carried out by the back door. */
    @Test
    fun onlyConfirmedDosesAreLookedUp() {
        val confirmed = dose(relative = 100)
        val unticked = dose(relative = 200)

        val plan = PenImportPlan.of(
            listOf(confirmed),
            mapOf(
                confirmed.relativeSeconds to proposal(confirmed, entryId = 7),
                unticked.relativeSeconds to proposal(unticked, entryId = 8),
            ),
        ) { false }

        assertEquals(listOf(7L), plan.adopt.map(PenManualMerge::entryId))
        assertTrue(plan.insert.isEmpty())
    }

    /** Two doses cannot both take over the same entry, whatever a stale proposal says. */
    @Test
    fun oneEntryStandsForOneDose() {
        val first = dose(relative = 100)
        val second = dose(relative = 200)

        val plan = PenImportPlan.of(
            listOf(first, second),
            mapOf(
                first.relativeSeconds to proposal(first, entryId = 7),
                second.relativeSeconds to proposal(second, entryId = 7),
            ),
        ) { false }

        assertEquals(1, plan.adopt.size)
        assertEquals(listOf(200L), plan.insert.map(PenDose::relativeSeconds))
    }

    @Test
    fun nothingConfirmedWritesNothing() {
        val plan = PenImportPlan.of(emptyList(), mapOf(100L to proposal(dose(100), 7))) { false }

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.insert.isEmpty())
    }
}
