package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.data.journal.JournalEntrySource

class JournalSourcePresentationTests {

    @Test
    fun manualEntriesCarryNoMark() {
        assertNull(JournalEntrySource.MANUAL.presentation())
    }

    @Test
    fun everyOtherSourceHasAnIconAndALabel() {
        JournalEntrySource.entries
            .filter { it != JournalEntrySource.MANUAL }
            .forEach { source ->
                val shown = source.presentation()
                assertNotNull("$source should be shown", shown)
                assertNotNull(shown!!.icon)
            }
    }

    @Test
    fun labelsTellTheSourcesApart() {
        // The icon is shared between the three "received from elsewhere" sources, so the
        // label is what distinguishes them in the detail view.
        val labels = JournalEntrySource.entries.mapNotNull { it.presentation()?.labelRes }

        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun anUnknownStoredValueIsShownAsManual() {
        // fromStorage falls back to MANUAL, so an entry written by a newer build with a
        // source this one does not know draws no mark rather than crashing.
        assertNull(JournalEntrySource.fromStorage("telepathy").presentation())
    }
}
