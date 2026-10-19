package tk.glucodata.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalObservationSafetyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("DashboardViewModel source not found")
    }

    @Test
    fun stoppingTheObserverDoesNotPresentAnEmptyDatabase() {
        val source = File(
            repoRoot(),
            "Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt",
        ).readText()
        val start = source.indexOf("private fun stopJournalEntriesObservation()")
        val end = source.indexOf("private fun ensureJournalPresetsObserved()", start)
        assertTrue("journal observer stop function not found", start >= 0 && end > start)
        val function = source.substring(start, end)

        assertTrue(function.contains("journalEntriesJob?.cancel()"))
        assertFalse(function.contains("_journalEntries.value = emptyList()"))
    }
}
