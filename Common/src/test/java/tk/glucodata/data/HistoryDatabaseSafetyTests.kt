package tk.glucodata.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryDatabaseSafetyTests {
    private fun historyDatabaseSource(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val repositoryPath = File(
                directory,
                "Common/src/mobile/java/tk/glucodata/data/HistoryDatabase.kt"
            )
            if (repositoryPath.isFile) return repositoryPath.readText()

            val modulePath = File(
                directory,
                "src/mobile/java/tk/glucodata/data/HistoryDatabase.kt"
            )
            if (modulePath.isFile) return modulePath.readText()

            directory = directory.parentFile
        }
        error("Could not locate HistoryDatabase.kt")
    }

    @Test
    fun historyDatabaseDoesNotUseDestructiveMigrationFallback() {
        assertFalse(
            "An incompatible history database must fail to open without deleting stored history",
            historyDatabaseSource().contains("fallbackToDestructiveMigration")
        )
    }
}
