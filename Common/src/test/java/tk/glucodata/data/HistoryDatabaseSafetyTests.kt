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
    fun recordedMainValueMigrationIsRegisteredAndKeyedByTheMinute() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("Migration(17, 18)"))
        assertTrue(source.contains("MIGRATION_17_18"))
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
    fun insulinCurveSnapshotMigrationIsRegisteredAndAdditive() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("version = 19"))
        assertTrue(source.contains("Migration(18, 19)"))
        assertTrue(source.contains("MIGRATION_18_19"))
        assertTrue(source.contains("ALTER TABLE journal_entries ADD COLUMN insulinCurveJsonSnapshot TEXT"))
        assertTrue(source.contains("ALTER TABLE journal_insulin_presets ADD COLUMN curveProfileId TEXT"))
        // Existing doses freeze the curve they were recorded under; the backfill
        // must read the preset row, never invent a shape.
        assertTrue(source.contains("SET insulinCurveJsonSnapshot = ("))
        assertFalse(source.contains("DROP TABLE journal_insulin_presets"))
    }
}
