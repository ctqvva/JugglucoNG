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

        assertTrue(source.contains("version = 25"))
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

    @Test
    fun recordedMainValueMigrationIsRegisteredAndKeyedByTheMinute() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("Migration(23, 24)"))
        assertTrue(source.contains("MIGRATION_23_24"))
        // The whole point of the migration: one row per minute, so the record can
        // say which sensor the dashboard drew instead of what each would have.
        assertTrue(source.contains("PRIMARY KEY(timestamp)"))
        // Rebuilding this one table is deliberate and is the only table the
        // migration is allowed to drop.
        assertTrue(source.contains("DROP TABLE IF EXISTS reading_display"))
        assertFalse(source.contains("DROP TABLE IF EXISTS history_readings"))
        assertFalse(source.contains("DROP TABLE IF EXISTS reading_uncertainty"))
    }

    @Test
    fun mainValuesSealedAgainstTheWrongSensorAreDiscarded() {
        val source = historyDatabaseSource()

        // Insert-or-ignore is what makes a recorded value durable, and also what
        // stops a bad row ever being corrected in place. Clearing the table is
        // the only way to retire the coin-flip ownership the first pass wrote.
        assertTrue(source.contains("Migration(24, 25)"))
        assertTrue(source.contains("MIGRATION_24_25"))
        assertTrue(source.contains("DELETE FROM reading_display"))
        assertFalse(source.contains("DELETE FROM history_readings"))
    }
}
