package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenImportCursorTests {

    private fun dose(relativeSeconds: Long) =
        PenDose(relativeSeconds = relativeSeconds, timestampSeconds = 1_700_000_000L + relativeSeconds, units = 4f, flags = 0)

    @Test
    fun staysBelowADoseTheJournalWasNotSeenToHold() {
        // The newest dose was never found in the journal — here because nothing checked
        // it. Were the cursor to pass it, no later scan would offer it again.
        val doses = listOf(dose(100), dose(200), dose(300))
        val present = setOf(100L, 200L)

        val cursor = PenImportCursor.provenUpTo(doses) { it.relativeSeconds in present }

        assertEquals(200L, cursor)
    }

    @Test
    fun doesNotMoveWhenNothingWasVouchedFor() {
        // A whole read landing outside the review window — what a wrong anchor looks like —
        // says nothing about the journal, so it moves nothing.
        val doses = listOf(dose(100), dose(200))

        assertNull(PenImportCursor.provenUpTo(doses) { false })
    }

    @Test
    fun reachesTheNewestDoseWhenEveryDoseWasFound() {
        val doses = listOf(dose(100), dose(300), dose(200))

        assertEquals(300L, PenImportCursor.provenUpTo(doses) { true })
    }

    @Test
    fun hasNothingToSayAboutAnEmptyRead() {
        assertNull(PenImportCursor.provenUpTo(emptyList()) { true })
    }
}
