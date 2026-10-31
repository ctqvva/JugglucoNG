package tk.glucodata.data.calibration

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Calibration migrations on the H3 runner (plan task H4). The released starting
 * point is v3 (1.1.2-Alpha, recovered in H2). Later versions add
 * `journalEntryId`, `sensorValueStock`, and unit provenance, so an existing row must
 * come through with sensible values for the new columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Keep Conscrypt from becoming the JVM-wide top JCA provider; see HistoryMigrationTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalibrationMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CalibrationDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesFromTheReleasedV3SchemaToCurrent() {
        helper.createDatabase(DB_NAME, 3).close()
        helper.runMigrationsAndValidate(DB_NAME, CALIBRATION_DATABASE_VERSION, true, *CalibrationDatabase.ALL_MIGRATIONS).close()
    }

    @Test
    fun keepsACalibrationRowAndFillsTheNewColumns() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO calibrations " +
                    "(timestamp, sensorId, sensorValue, sensorValueRaw, userValue, isEnabled, isRawMode) " +
                    "VALUES (1000, 'S-1', 5.5, 5.4, 6.0, 1, 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, CALIBRATION_DATABASE_VERSION, true, *CalibrationDatabase.ALL_MIGRATIONS)

        migrated.query(
            "SELECT sensorId, sensorValue, sensorValueRaw, userValue, sensorValueStock, journalEntryId, valueUnit " +
                "FROM calibrations"
        ).use { cursor ->
            assertTrue("the calibration survived", cursor.moveToFirst())
            assertEquals("S-1", cursor.getString(0))
            assertEquals(5.5, cursor.getDouble(1), 0.001)
            assertEquals("the sensor's own raw value survives the migration", 5.4, cursor.getDouble(2), 0.001)
            assertEquals(6.0, cursor.getDouble(3), 0.001)
            assertEquals("sensorValueStock is a column added at v5 with a zero default", 0.0, cursor.getDouble(4), 0.001)
            assertTrue("journalEntryId starts null (entered by hand)", cursor.isNull(5))
            assertEquals("legacy unit provenance remains unknown until first load", -1, cursor.getInt(6))
            assertEquals(1, cursor.count)
        }
        migrated.close()
    }

    @Test
    fun recoveryBuildReopensAnExistingV6DatabaseWithoutLosingUnitProvenance() {
        helper.createDatabase(DB_NAME, 6).use { db ->
            db.execSQL(
                "INSERT INTO calibrations " +
                    "(id, timestamp, sensorId, sensorValue, sensorValueRaw, userValue, isEnabled, " +
                    "isRawMode, journalEntryId, sensorValueStock, valueUnit) " +
                    "VALUES (42, 1000, 'S-1', 5.5, 5.4, 6.0, 1, 0, 7, 5.6, 1)"
            )
        }
        // Reinstalling a build that understands v6 reopens the existing file; no downgrade or
        // destructive fallback is needed after an older v5 build failed to open it.
        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CalibrationDatabase::class.java,
            DB_NAME
        ).addMigrations(*CalibrationDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(6, db.version)
            db.query("SELECT sensorId, userValue, valueUnit, journalEntryId FROM calibrations WHERE id = 42").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("S-1", row.getString(0))
                assertEquals(6.0, row.getDouble(1), 0.001)
                assertEquals(1, row.getInt(2))
                assertEquals(7L, row.getLong(3))
                assertEquals(1, row.count)
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB_NAME = "calibration-migration-test.db"
    }
}
