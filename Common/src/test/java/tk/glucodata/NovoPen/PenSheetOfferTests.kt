package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field report this exists for: a scan that read one air shot opened a sheet headed
 * "1 new dose" with a single unticked row and a disabled "add 0 doses" button under it.
 * Three numbers from three different filter states, and no way out of the dialog.
 */
class PenSheetOfferTests {

    private val anchor = 1_700_000_000L

    private fun dose(relative: Long, units: Float, minutesAgo: Long, priming: Boolean = false) =
        PenDose(
            relativeSeconds = relative,
            timestampSeconds = anchor - minutesAgo * 60L,
            units = units,
            flags = 0,
            priming = priming,
        )

    @Test
    fun airShotsAreNeitherOfferedNorCounted() {
        val doses = listOf(
            dose(relative = 100, units = 6f, minutesAgo = 30),
            dose(relative = 150, units = 1f, minutesAgo = 20, priming = true),
            dose(relative = 200, units = 4f, minutesAgo = 10),
        )

        val offered = PenSheetOffer.offerable(doses)

        assertEquals(listOf(100L, 200L), offered.map(PenDose::relativeSeconds))
        assertEquals(1, PenSheetOffer.skipped(doses))
    }

    /** The reported case: nothing to confirm, so the caller knows not to open a sheet. */
    @Test
    fun aReadOfNothingButAnAirShotOffersNothing() {
        val doses = listOf(dose(relative = 150, units = 1f, minutesAgo = 20, priming = true))

        assertTrue(PenSheetOffer.offerable(doses).isEmpty())
        assertTrue(PenSheetOffer.preselection(doses, emptySet(), 0L).isEmpty())
        assertEquals(1, PenSheetOffer.skipped(doses))
    }

    @Test
    fun headingListAndButtonCountTheSameThing() {
        val doses = listOf(
            dose(relative = 100, units = 6f, minutesAgo = 30),
            dose(relative = 150, units = 1f, minutesAgo = 20, priming = true),
            dose(relative = 200, units = 4f, minutesAgo = 10),
        )

        val offered = PenSheetOffer.offerable(doses)
        val ticked = PenSheetOffer.preselection(doses, emptySet(), preselectFromSeconds = 0L)

        // What the heading says, what the list holds and what the button would add.
        assertEquals(offered.size, ticked.size)
        assertEquals(2, offered.size)
    }

    /** A dose that stands for an entry already written starts ticked, whatever its age. */
    @Test
    fun aDoseReplacingAnEntryIsTickedEvenOutsideThePreselectWindow() {
        val old = dose(relative = 100, units = 6f, minutesAgo = 60 * 48)
        val recent = dose(relative = 200, units = 4f, minutesAgo = 10)
        val doses = listOf(old, recent)
        val preselectFrom = anchor - 24 * 60 * 60L

        val withoutProposal = PenSheetOffer.preselection(doses, emptySet(), preselectFrom)
        val withProposal = PenSheetOffer.preselection(doses, setOf(100L), preselectFrom)

        assertEquals(setOf(200L), withoutProposal)
        assertEquals(setOf(100L, 200L), withProposal)
    }

    /** An air shot is never ticked, not even if something proposed it. */
    @Test
    fun anAirShotIsNeverTicked() {
        val doses = listOf(dose(relative = 150, units = 1f, minutesAgo = 20, priming = true))

        assertTrue(PenSheetOffer.preselection(doses, setOf(150L), 0L).isEmpty())
    }

    @Test
    fun nothingReadOffersNothing() {
        assertTrue(PenSheetOffer.offerable(emptyList()).isEmpty())
        assertTrue(PenSheetOffer.preselection(emptyList(), emptySet(), 0L).isEmpty())
        assertEquals(0, PenSheetOffer.skipped(emptyList()))
    }
}
