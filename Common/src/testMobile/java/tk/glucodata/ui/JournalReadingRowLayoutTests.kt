package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JournalReadingRowLayoutTests {
    private fun pack(vararg widths: Int, beside: Int, below: Int) =
        packJournalChipLines(widths, firstLineWidth = beside, otherLinesWidth = below, spacing = 6)

    @Test fun chipsThatFitBesideTheValueStayThere() {
        val lines = pack(30, 40, beside = 80, below = 200)
        assertArrayEquals(intArrayOf(0, 0), lines.line)
        assertArrayEquals(intArrayOf(0, 36), lines.x)
    }

    @Test fun overflowFillsTheWiderLinesBelowSideBySide() {
        // The first chip fits beside the value; the rest share the full-width line under it
        // instead of stacking one per line in the narrow gap.
        val lines = pack(70, 70, 50, beside = 80, below = 200)
        assertArrayEquals(intArrayOf(0, 1, 1), lines.line)
        assertArrayEquals(intArrayOf(0, 0, 76), lines.x)
    }

    @Test fun aChipNeverSitsCutBesideTheValue() {
        // #990: a calibrated value beside its source value leaves less than one chip's width.
        val lines = pack(81, 40, beside = 80, below = 200)
        assertArrayEquals(intArrayOf(1, 1), lines.line)
        assertArrayEquals(intArrayOf(0, 87), lines.x)
        assertArrayEquals(intArrayOf(1), pack(40, beside = 0, below = 200).line)
    }

    @Test fun linesBelowWrapWhenFull() {
        val lines = pack(100, 100, 100, 300, beside = 50, below = 210)
        assertArrayEquals(intArrayOf(1, 1, 2, 3), lines.line)
        assertArrayEquals(intArrayOf(0, 106, 0, 0), lines.x)
    }
}
