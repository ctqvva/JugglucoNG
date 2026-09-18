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

        assertTrue(source.contains("version = 33"))
        assertTrue(source.contains("Migration(18, 19)"))
        assertTrue(source.contains("MIGRATION_18_19"))
        assertTrue(source.contains("ALTER TABLE journal_entries ADD COLUMN insulinCurveJsonSnapshot TEXT"))
        assertTrue(source.contains("ALTER TABLE journal_insulin_presets ADD COLUMN curveProfileId TEXT"))
        // Existing doses freeze the curve they were recorded under; the backfill
        // must read the preset row, never invent a shape.
        assertTrue(source.contains("SET insulinCurveJsonSnapshot = ("))
        assertFalse(source.contains("DROP TABLE journal_insulin_presets"))
    }

    @Test
    fun cloneTestBuildBridgeIsRegisteredAndAdditive() {
        val source = historyDatabaseSource()

        // Phones that ran Clone-branch test builds report v20–v30; this build must
        // open them as an upgrade, never a downgrade.
        assertTrue(source.contains("Migration(19, 30)"))
        assertTrue(source.contains("MIGRATION_19_30"))
        assertTrue(source.contains("bridgeCloneToV30(20)"))
        assertTrue(source.contains("bridgeCloneToV30(29)"))
        // v30 alone is not enough: at equal versions Room compares the
        // whole-schema identity hash, which covers Clone-only tables this build
        // does not own. The 30→31 step forces the migration path, where Room
        // validates the owned tables and rewrites the hash.
        assertTrue(source.contains("Migration(30, 31)"))
        assertTrue(source.contains("MIGRATION_30_31"))
        // Compatibility columns are kept, never read; the bridge must not drop
        // user data tables.
        assertTrue(source.contains("ADD COLUMN source TEXT NOT NULL DEFAULT 'sensor'"))
        assertTrue(source.contains("ADD COLUMN firstStoredAt INTEGER NOT NULL DEFAULT 0"))
        assertTrue(source.contains("ADD COLUMN originSource TEXT"))
        assertTrue(source.contains("ADD COLUMN recoveryId TEXT"))
        assertFalse(source.contains("DROP TABLE IF EXISTS history_readings"))
        assertFalse(source.contains("DROP TABLE journal_insulin_presets"))
    }

    @Test
    fun cloneTablesBecomeOwnedAtV32ByGuardedCreationOnly() {
        val source = historyDatabaseSource()

        // Three histories reach v31 -- main, a Clone build, a test build -- and
        // Room validates owned tables on open, so v32 must guarantee them on all.
        assertTrue(source.contains("Migration(31, 32)"))
        assertTrue(source.contains("MIGRATION_31_32"))
        assertTrue(source.contains("CloneJournalTombstoneEntity::class"))
        assertTrue(source.contains("CloneJournalRecoveryTombstoneEntity::class"))
        assertTrue(source.contains("CloneRecoveryImportEntity::class"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_journal_tombstones"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_journal_recovery_tombstones"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_recovery_imports"))
        assertTrue(source.contains("index_clone_journal_recovery_tombstones_recoveryId"))
        // The identities the Clone code keys on, filled only where empty.
        assertTrue(source.contains("SET recoveryId = lower(hex(randomblob(16)))"))
        assertTrue(source.contains("WHERE recoveryId IS NULL"))
        // Nothing here may drop a table a user's data lives in.
        assertFalse(source.contains("DROP TABLE clone_journal_tombstones"))
        assertFalse(source.contains("DROP TABLE journal_entries"))
    }
}
