package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalPresetMergeTests {
    private fun preset(
        id: Long,
        name: String,
        sortOrder: Int
    ) = JournalInsulinPresetEntity(
        id = id,
        displayName = name,
        onsetMinutes = 1,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = true,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = sortOrder
    )

    @Test
    fun currentElevenPresetLayoutMatchesTheSameSortOrderAfterRenaming() {
        val existing = (0..10).map { preset(it.toLong() + 1, "Old localized $it", it) }
        val renamedRapid = preset(0, "Rapid acting (generic)", 0)
        val renamedLong = preset(0, "Long acting (generic)", 1)

        assertEquals(1L, matchExistingBuiltInPreset(renamedRapid, existing)?.id)
        assertEquals(2L, matchExistingBuiltInPreset(renamedLong, existing)?.id)
    }

    @Test
    fun olderSixPresetLayoutStillUsesItsHistoricalMapping() {
        val existing = (0..5).map { preset(it.toLong() + 10, "Legacy $it", it) }

        assertEquals(11L, matchExistingBuiltInPreset(preset(0, "Rapid", 0), existing)?.id)
        assertEquals(13L, matchExistingBuiltInPreset(preset(0, "NPH", 9), existing)?.id)
        assertNull(matchExistingBuiltInPreset(preset(0, "Fiasp", 6), existing))
    }

    @Test
    fun exactNameWinsEvenWhenSortOrdersDiffer() {
        val expected = preset(42, "Fiasp (aspart)", 99)
        val existing = listOf(expected)

        assertEquals(
            expected,
            matchExistingBuiltInPreset(preset(0, "Fiasp (aspart)", 6), existing)
        )
    }
}
