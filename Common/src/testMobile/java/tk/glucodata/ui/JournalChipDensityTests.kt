package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType

class JournalChipDensityTests {
    private val hour = 3_600_000L
    private fun entry(minute: Long, key: Any? = "carbs", width: Float = 80f) =
        JournalChipDensity.Entry(minute * 60_000L, width, key)

    private fun leaders(
        entries: List<JournalChipDensity.Entry>,
        hours: Long = 24,
        width: Float = 360f,
        height: Float = 240f,
        chipHeight: Float = 26f
    ) = JournalChipDensity.leaders(entries, hours * hour, width, height, chipHeight)

    @Test fun sparseThreeDayViewKeepsIndividualLabels() {
        assertArrayEquals(intArrayOf(0, 1, 2), leaders(listOf(entry(0), entry(1440), entry(2880)), hours = 72))
    }

    @Test fun crowdedSixHourViewCompactsLocally() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), leaders((0L..3L).map { entry(it * 5) }, hours = 6))
    }

    @Test fun largerScreenRetainsLabelsAtTheSameZoom() {
        val entries = (0L..3L).map { entry(it * 30) }
        assertArrayEquals(intArrayOf(0, 0, 0, 0), leaders(entries))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), leaders(entries, width = 1200f))
    }

    @Test fun measuredTextSizeAndAvailableHeightAffectCrowding() {
        val entries = (0L..2L).map { entry(it * 15) }
        assertArrayEquals(intArrayOf(0, 1, 2), leaders(entries))
        assertArrayEquals(intArrayOf(0, 0, 0), leaders(entries.map { it.copy(width = 120f) }, chipHeight = 40f))
        assertArrayEquals(intArrayOf(0, 0, 0), leaders(entries, height = 160f))
    }

    @Test fun onlyAdditiveEntriesOfTheSameIdentityShareTotals() {
        val entries = listOf(
            entry(0, "carbs"), entry(1, "rapid"), entry(2, "basal"), entry(3, null),
            entry(4, "rapid"), entry(5, "carbs"), entry(6, null)
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 1, 0, 6), leaders(entries))
    }

    @Test fun continuousDosesCannotChainIntoOneHugePile() {
        val entries = (0L..47L).map { entry(it * 30, "rapid") }
        val result = leaders(entries)
        assertTrue(result.toSet().size > 1)
        result.indices.groupBy { result[it] }.values.forEach { members ->
            val spanPx = (entries[members.last()].timestamp - entries[members.first()].timestamp) * 360.0 / (24 * hour)
            assertTrue(spanPx <= 80.0)
        }
    }

    @Test fun distantDenseHistoryDoesNotCompactASparseNeighbourhood() {
        val entries = (0L..5L).map { entry(it) } + listOf(entry(600), entry(1200))
        val result = leaders(entries)
        assertEquals(6, result[6])
        assertEquals(7, result[7])
    }

    @Test fun panDoesNotRegroupTheJournal() {
        val entries = (0L..10L).map { entry(it * 30) }
        // Translating time (equivalent to moving the chart origin) leaves the layout intact.
        assertArrayEquals(leaders(entries), leaders(entries.map { it.copy(timestamp = it.timestamp + 123 * hour) }))
    }

    @Test fun densityFoldsWorkWithExistingPileCountsAndSpread() {
        val entries = (0L..3L).map { entry(it * 5) }
        val result = leaders(entries, hours = 6)
        val placements = JournalChipLayout.place(entries.mapIndexed { index, entry ->
            JournalChipRequest(
                anchorX = 80f + index * 5f, baseTop = 100f, width = entry.width,
                previous = null, foldInto = result[index].takeIf { it != index }, kind = "carbs"
            )
        }, JournalChipLayout.Spec(26f, 10f, 30f, 12f, 4f, 0f, 360f, 8f, 210f))
        val pile = JournalChipLayout.piles(placements, 12f, 8f).single()
        assertEquals(4, pile.size)
        assertEquals(3, placements.count { it.folded })
        val spread = JournalChipLayout.spread(
            placements.map { it.box }, pile, 0f, 360f, 8f, 210f, 4f, 4f, 160f
        )
        assertEquals(0, spread.stuckCount)
    }

    @Test fun visiblePileLeaderNeverFoldsBehindAnOffscreenChip() {
        val folds = arrayOf<Int?>(null, 0, 0, null, null)
        JournalChipDensity.foldInViewport(
            listOf(10L, 10L, 10L, 20L, 20L), listOf(-20f, 2f, 20f, 100f, 110f), folds
        )
        assertEquals(listOf(null, null, 1, null, 3), folds.toList())
    }

    @Test fun pileKeepsWorkingAfterOriginalLeaderLeavesTheComposition() {
        val folds = arrayOfNulls<Int>(3)
        JournalChipDensity.foldInViewport(listOf(10L, 10L, 10L), listOf(2f, 20f, 25f), folds)
        assertEquals(listOf(null, 0, 0), folds.toList())
    }

    @Test fun equalDoseAmountsDoNotEraseInsulinIdentity() {
        val rapid = JournalChartMarker(1L, 0L, JournalEntryType.INSULIN, "Rapid", 1, "", "2 U", amount = 2f)
        assertEquals(journalChipKind(rapid), journalChipKind(rapid.copy(entryId = 2L, amount = 4f)))
        assertNotEquals(journalChipKind(rapid), journalChipKind(rapid.copy(title = "Basal")))
        assertNotEquals(journalChipKind(rapid), journalChipKind(rapid.copy(accentColor = 2)))
        val kinds = listOf(rapid, rapid.copy(title = "Basal"), rapid.copy(accentColor = 2)).map(::journalChipKind)
        val placed = JournalChipLayout.place(kinds.map { kind ->
            JournalChipRequest(100f, 100f, 80f, null, stackKey = kind to "2 U", kind = kind)
        }, JournalChipLayout.Spec(26f, 10f, 30f, 12f, 4f, 0f, 360f, 8f, 210f, repeatReach = 100f))
        assertTrue(placed.all { it.tuck == null })
    }
}
