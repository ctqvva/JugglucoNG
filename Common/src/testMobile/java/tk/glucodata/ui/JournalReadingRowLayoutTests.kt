package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalReadingRowLayoutTests {
    @Test fun chipsStayBesideTheValueWhileEachFitsAlone() {
        // Two chips wider together than the gap still wrap there, as before.
        assertFalse(journalChipsNeedOwnLine(listOf(60, 60), inlineWidth = 80))
        assertFalse(journalChipsNeedOwnLine(listOf(80), inlineWidth = 80))
    }

    @Test fun oneChipTooWideMovesThemToTheirOwnLine() {
        // #990: a calibrated value beside its source value leaves too little room for a chip label.
        assertTrue(journalChipsNeedOwnLine(listOf(40, 81), inlineWidth = 80))
        assertTrue(journalChipsNeedOwnLine(listOf(40), inlineWidth = 0))
    }

    @Test fun packingWrapsOnlyWhenTheNextChipDoesNotFit() {
        val lines = packJournalChipLines(
            widths = intArrayOf(50, 40, 30, 120),
            heights = intArrayOf(20, 24, 20, 22),
            availableWidth = 100,
            spacing = 6
        )
        assertArrayEquals(intArrayOf(0, 56, 0, 0), lines.x)
        assertArrayEquals(intArrayOf(0, 0, 1, 2), lines.line)
        assertArrayEquals(intArrayOf(24, 20, 22), lines.lineHeights)
    }
}
