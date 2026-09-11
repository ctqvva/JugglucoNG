package tk.glucodata.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDatabaseSafetyTests {
    private fun source(relativePath: String): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val repositoryPath = File(directory, "Common/$relativePath")
            if (repositoryPath.isFile) return repositoryPath.readText()

            val modulePath = File(directory, relativePath)
            if (modulePath.isFile) return modulePath.readText()

            directory = directory.parentFile
        }
        error("Could not locate $relativePath")
    }

    private fun historyDatabaseSource() =
        source("src/mobile/java/tk/glucodata/data/HistoryDatabase.kt")

    @Test
    fun historyDatabaseDoesNotUseDestructiveMigrationFallback() {
        assertFalse(
            "An incompatible history database must fail to open without deleting stored history",
            historyDatabaseSource().contains("fallbackToDestructiveMigration")
        )
    }

    @Test
    fun startupValidatesAnExistingDatabaseBeforeContinuing() {
        assertTrue(
            "The startup probe must force Room to validate the existing database",
            historyDatabaseSource().contains("openHelper.writableDatabase")
        )
        assertTrue(
            "MainActivity must stop before normal startup when the history database is incompatible",
            source("src/main/java/tk/glucodata/MainActivity.java")
                .contains("if (!Specific.historyDatabaseCompatible(this))")
        )
    }

    @Test
    fun journalRecoveryIdentityMigrationIsRegisteredAndNonDestructive() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("version = 23"))
        assertTrue(source.contains("Migration(20, 21)"))
        assertTrue(source.contains("ALTER TABLE journal_entries ADD COLUMN recoveryId TEXT"))
        assertTrue(source.contains("lower(hex(randomblob(16)))"))
        assertTrue(source.contains("index_journal_entries_recoveryId"))
        assertTrue(source.contains("ALTER TABLE clone_journal_tombstones ADD COLUMN recoveryId TEXT"))
        assertTrue(source.contains("MIGRATION_20_21"))
        assertFalse(source.contains("DROP TABLE journal_entries"))
    }

    @Test
    fun recoveredJournalDeletionMigrationIsRegisteredAndNonDestructive() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("Migration(21, 22)"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_journal_recovery_tombstones"))
        assertTrue(source.contains("PRIMARY KEY(stableBaseId)"))
        assertTrue(source.contains("index_clone_journal_recovery_tombstones_recoveryId"))
        assertTrue(source.contains("MIGRATION_21_22"))
        assertFalse(source.contains("DROP TABLE clone_journal_tombstones"))
    }
}
