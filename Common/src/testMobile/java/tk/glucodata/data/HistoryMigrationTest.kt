package tk.glucodata.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The Room migration runner (plan tasks H3/H4). Runs the production `Migration`
 * objects against the exported schemas in `Common/schemas/` and lets
 * `MigrationTestHelper` check the result matches the committed schema, so a
 * migration that drops a column or a table fails here.
 *
 * The starting points are the released schemas recovered in H2: v11
 * (1.1.2-Alpha) and v12 (1.1.3-Alpha). v32 is the current version. The row tests
 * matter most: Room migrations are the one place a bug permanently destroys
 * user history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Robolectric installs Conscrypt as the top JCA provider for the whole test JVM;
// leave the platform provider in place so this class cannot change what the JCA
// tests that share the JVM (Ottai, iCan, Anytime, AiDex crypto/auth) exercise.
@ConscryptMode(ConscryptMode.Mode.OFF)
class HistoryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HistoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun migrate(from: Int, databaseName: String = DB_NAME) {
        helper.createDatabase(databaseName, from).close()
        helper.runMigrationsAndValidate(databaseName, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS).close()
    }

    /**
     * Every committed schema version must migrate to the current one. This is the version-pin
     * check's other half (plan task H5): a version bump that commits a JSON but no migration, or a
     * migration with no JSON, fails here rather than in a release.
     */
    @Test
    fun everyCommittedSchemaVersionMigratesToCurrent() {
        committedSchemaVersions()
            .filter { it < HISTORY_DATABASE_VERSION }
            .forEach { from -> migrate(from, "history-migration-from-$from.db") }
    }

    @Test
    fun keepsReadingsAndTombstonesThroughV11ToCurrent() {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO history_readings (timestamp, sensorSerial, value, rawValue, rate) " +
                    "VALUES (1000, 'S-1', 123.0, 456.0, 1.5)"
            )
            db.execSQL(
                "INSERT INTO history_deleted_readings (timestamp, sensorSerial, deletedAt) " +
                    "VALUES (2000, 'S-1', 3000)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

        migrated.query("SELECT id, sensorSerial, value, rate, source, firstStoredAt FROM history_readings").use { cursor ->
            assertTrue("the reading survived", cursor.moveToFirst())
            assertEquals("S-1", cursor.getString(1))
            assertEquals(123.0, cursor.getDouble(2), 0.001)
            assertEquals(1.5, cursor.getDouble(3), 0.001)
            assertEquals("an old reading is a sensor reading", "sensor", cursor.getString(4))
            assertEquals(
                "firstStoredAt is backfilled from the row id, not left at the column default",
                cursor.getLong(0),
                cursor.getLong(5),
            )
            assertEquals(1, cursor.count)
        }
        migrated.query("SELECT sensorSerial FROM history_deleted_readings").use { cursor ->
            assertTrue("the tombstone survived", cursor.moveToFirst())
            assertEquals("S-1", cursor.getString(0))
            assertEquals(1, cursor.count)
        }
        // The v17/v18 step rebuilds reading_display; the minute-keyed schema has to be there
        // afterwards or the dashboard writes into the old per-sensor table (the divergent-history
        // guard). A missing index would also mean the rebuild silently did not run.
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == "index_reading_display_sensorSerial") found = true
            }
            assertTrue("reading_display was rebuilt with its per-sensor index", found)
        }
        migrated.close()
    }

    @Test
    fun keepsReadingsThroughV12ToCurrent() {
        helper.createDatabase(DB_NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO history_readings (timestamp, sensorSerial, value, rawValue, rate) " +
                    "VALUES (1000, 'S-2', 99.0, 100.0, -0.5)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

        migrated.query("SELECT sensorSerial, value FROM history_readings").use { cursor ->
            assertTrue("the reading survived", cursor.moveToFirst())
            assertEquals("S-2", cursor.getString(0))
            assertEquals(99.0, cursor.getDouble(1), 0.001)
        }
        migrated.close()
    }

    @Test
    fun keepsJournalRowsThroughV11ToCurrent() {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO journal_foods " +
                    "(id, displayName, carbsGrams, proteinGrams, fatGrams, absorptionMinutes, " +
                    "accentColor, isBuiltIn, isArchived, sortOrder, createdAt, updatedAt) " +
                    "VALUES (7, 'Oatmeal', 40.0, 6.0, 5.0, 120, 100, 0, 0, 3, 111, 222)"
            )
            db.execSQL(
                "INSERT INTO journal_insulin_presets " +
                    "(id, displayName, onsetMinutes, durationMinutes, accentColor, curveJson, " +
                    "isBuiltIn, isArchived, countsTowardIob, sortOrder) " +
                    "VALUES (9, 'Rapid', 15, 240, 200, 'curve-json', 0, 0, 1, 5)"
            )
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, sensorSerial, entryType, title, amount, insulinPresetId, foodId, " +
                    "source, createdAt, updatedAt) " +
                    "VALUES (3, 5000, 'S-1', 'insulin', 'Breakfast dose', 4.5, 9, 7, 'manual', 6000, 7000)"
            )
            db.execSQL(
                "INSERT INTO journal_pending_deletes (entryId, nsRemoteId, deletedAt) " +
                    "VALUES (3, 'remote-3', 8000)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

        migrated.query("SELECT displayName, carbsGrams, isArchived FROM journal_foods WHERE id = 7").use { cursor ->
            assertTrue("the food survived", cursor.moveToFirst())
            assertEquals("Oatmeal", cursor.getString(0))
            assertEquals(40.0, cursor.getDouble(1), 0.001)
            assertEquals(0, cursor.getInt(2))
        }
        migrated.query(
            "SELECT displayName, useForCalculation, curveModelVersion, curveEvidence, curveProfileId " +
                "FROM journal_insulin_presets WHERE id = 9"
        ).use { cursor ->
            assertTrue("the preset survived", cursor.moveToFirst())
            assertEquals("Rapid", cursor.getString(0))
            assertEquals("useForCalculation defaults on for a non-built-in preset", 1, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("unverified", cursor.getString(3))
            assertTrue("curveProfileId starts null", cursor.isNull(4))
        }
        migrated.query(
            "SELECT title, amount, insulinPresetId, foodId, source, originSource, recoveryId, " +
                "insulinCurveJsonSnapshot, insulinCurveWasApproximated FROM journal_entries WHERE id = 3"
        ).use { cursor ->
            assertTrue("the entry survived", cursor.moveToFirst())
            assertEquals("Breakfast dose", cursor.getString(0))
            assertEquals(4.5, cursor.getDouble(1), 0.001)
            assertEquals("the preset link survived", 9, cursor.getInt(2))
            assertEquals("the food link survived", 7, cursor.getInt(3))
            assertEquals("manual", cursor.getString(4))
            assertEquals("originSource is backfilled from source", "manual", cursor.getString(5))
            assertTrue("recoveryId is assigned", !cursor.isNull(6))
            assertEquals("the preset curve is frozen into the entry", "curve-json", cursor.getString(7))
            assertEquals(1, cursor.getInt(8))
        }
        migrated.query("SELECT nsRemoteId, attempts, lastAttemptAt FROM journal_pending_deletes").use { cursor ->
            assertTrue("the pending delete survived", cursor.moveToFirst())
            assertEquals("remote-3", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
        migrated.close()
    }

    @Test
    fun keepsJournalRowsThroughV12ToCurrent() {
        helper.createDatabase(DB_NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO journal_foods " +
                    "(id, displayName, carbsGrams, absorptionMinutes, accentColor, isBuiltIn, " +
                    "isArchived, sortOrder, createdAt, updatedAt) " +
                    "VALUES (8, 'Rice', 55.0, 90, 101, 0, 0, 4, 111, 222)"
            )
            db.execSQL(
                "INSERT INTO journal_insulin_presets " +
                    "(id, displayName, onsetMinutes, durationMinutes, accentColor, curveJson, " +
                    "isBuiltIn, isArchived, countsTowardIob, sortOrder, useForCalculation) " +
                    "VALUES (10, 'Basal', 30, 360, 201, 'basal-json', 0, 0, 0, 6, 0)"
            )
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, sensorSerial, entryType, title, amount, insulinPresetId, foodId, " +
                    "source, createdAt, updatedAt) " +
                    "VALUES (4, 5100, 'S-2', 'insulin', 'Lunch dose', 6.0, 10, 8, 'manual', 6100, 7100)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

        migrated.query("SELECT displayName, carbsGrams FROM journal_foods WHERE id = 8").use { cursor ->
            assertTrue("the food survived", cursor.moveToFirst())
            assertEquals("Rice", cursor.getString(0))
            assertEquals(55.0, cursor.getDouble(1), 0.001)
        }
        migrated.query(
            "SELECT useForCalculation, curveEvidence FROM journal_insulin_presets WHERE id = 10"
        ).use { cursor ->
            assertTrue("the preset survived", cursor.moveToFirst())
            assertEquals("the v12 flag survives", 0, cursor.getInt(0))
            assertEquals("unverified", cursor.getString(1))
        }
        migrated.query(
            "SELECT insulinPresetId, foodId, originSource, recoveryId, insulinCurveJsonSnapshot, " +
                "insulinCurveWasApproximated FROM journal_entries WHERE id = 4"
        ).use { cursor ->
            assertTrue("the entry survived", cursor.moveToFirst())
            assertEquals("the preset link survived", 10, cursor.getInt(0))
            assertEquals("the food link survived", 8, cursor.getInt(1))
            assertEquals("originSource is backfilled from source", "manual", cursor.getString(2))
            assertTrue("recoveryId is assigned", !cursor.isNull(3))
            assertEquals("the preset curve is frozen into the entry", "basal-json", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
        }
        migrated.close()
    }

    private fun committedSchemaVersions(): List<Int> {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "Common/schemas/tk.glucodata.data.HistoryDatabase")
            if (candidate.isDirectory) {
                return candidate.listFiles { file -> file.extension == "json" }!!
                    .map { it.nameWithoutExtension.toInt() }
                    .sorted()
            }
            directory = directory.parentFile
        }
        error("Could not locate committed history schemas")
    }

    @Test
    fun onlyLocalJournalSourcesAreBackfilledAsOrigin() {
        // ensureCloneSchema backfills originSource from source only for the local entry kinds
        // (manual / health_connect / meter / pen). A Nightscout- or Clone-sourced row must stay
        // NULL: backfilling it as "manual" would let Clone recovery treat a foreign entry as local
        // content it authored. CloneJournalRecoveryStore then resolves the missing origin as
        // `originSource ?: source`, so the NULL rows keep their real source on import.
        helper.createDatabase(DB_NAME, 11).use { db ->
            listOf(1L to "manual", 2L to "health_connect", 3L to "meter", 4L to "pen").forEach { (id, source) ->
                db.execSQL(
                    "INSERT INTO journal_entries " +
                        "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                        "VALUES ($id, ${id * 1000}, 'note', '$source', '$source', 1, 2)"
                )
            }
            listOf(5L to "nightscout", 6L to "clone_turn", 7L to "api").forEach { (id, source) ->
                db.execSQL(
                    "INSERT INTO journal_entries " +
                        "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                        "VALUES ($id, ${id * 1000}, 'note', '$source', '$source', 1, 2)"
                )
            }
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

        listOf(1L to "manual", 2L to "health_connect", 3L to "meter", 4L to "pen").forEach { (id, source) ->
            migrated.query("SELECT source, originSource FROM journal_entries WHERE id = $id").use { cursor ->
                assertTrue("entry $id survived", cursor.moveToFirst())
                assertEquals("source is preserved", source, cursor.getString(0))
                assertEquals("a local source becomes its own origin", source, cursor.getString(1))
            }
        }
        listOf(5L to "nightscout", 6L to "clone_turn", 7L to "api").forEach { (id, source) ->
            migrated.query("SELECT source, originSource, recoveryId FROM journal_entries WHERE id = $id").use { cursor ->
                assertTrue("entry $id survived", cursor.moveToFirst())
                assertEquals("source is preserved", source, cursor.getString(0))
                assertTrue("a non-local source is not claimed as manual", cursor.isNull(1))
                assertTrue("the recovery identity is still assigned", !cursor.isNull(2))
            }
        }
        migrated.close()
    }

    private companion object {
        const val DB_NAME = "history-migration-test.db"
    }
}
