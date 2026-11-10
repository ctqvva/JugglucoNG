package tk.glucodata.data

import android.database.SQLException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.BuildConfig

/**
 * Clone recovery migrations on the H3 runner (plan task H4). Replaces the JDBC
 * harness that read Room's generated code from `build/generated/ksp`; the
 * production `Migration` objects and the committed schemas are now the only
 * inputs, and `runMigrationsAndValidate` checks the resulting shape.
 *
 * The clone recovery artifacts (`recoveryId` backfill, `clone_journal_recovery_tombstones`,
 * `clone_recovery_imports`) are all created by the step that calls
 * `ensureCloneSchema` (v31 -> v32 here), so a released v11 history exercises them
 * end to end. The Clone histories that reach v31 are covered separately: one that
 * stopped at v20 ([cloneV20HistoryGainsIdentitiesAndKeepsTombstones]), one that
 * stopped at v21 ([cloneV21HistoryKeepsItsIdentities]), and one that reached v23 or
 * later, with both recovery tables in use ([cloneV23HistoryKeepsRecoveryTables]).
 *
 * Not covered: the old harness injected a failing statement mid-migration to
 * check the transaction rollback. `MigrationTestHelper` runs the real chain and
 * cannot inject a failure inside a migration, so that case is dropped rather
 * than faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Keep Conscrypt from becoming the JVM-wide top JCA provider; see HistoryMigrationTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
class CloneRecoveryMigrationTests {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HistoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    // The schemas are debug-only assets (Common/build.gradle), but gradle.properties keeps
    // the release unit-test variants, where MigrationTestHelper would find no schema file.
    // The migrations do not depend on the build type, so the debug run covers them.
    @Before
    fun schemasAreDebugAssets() = assumeTrue("Room schemas are debug-only assets", BuildConfig.DEBUG)

    private fun seedReleasedV11(seed: SupportSQLiteDatabase.() -> Unit) {
        helper.createDatabase(DB_NAME, 11).use { it.seed() }
    }

    private fun migrateToCurrent(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(DB_NAME, HISTORY_DATABASE_VERSION, true, *HistoryDatabase.ALL_MIGRATIONS)

    @Test
    fun recoverySchemaIsCreatedAndRowsSurviveFromV11() {
        seedReleasedV11 {
            execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt, nsUploadedAt) " +
                    "VALUES (7, 1000, 'note', 'Keep this note', 'manual', 900, 1100, 1200)"
            )
            execSQL(
                "INSERT INTO history_readings (id, timestamp, sensorSerial, value, rawValue) " +
                    "VALUES (9, 1000, 'SENSOR', 120.0, 119.0)"
            )
        }

        migrateToCurrent().use { db ->
            db.query("SELECT title, nsUploadedAt FROM journal_entries WHERE id = 7").use { cursor ->
                assertTrue("the journal row survived", cursor.moveToFirst())
                assertEquals("Keep this note", cursor.getString(0))
                assertEquals(1200L, cursor.getLong(1))
            }
            db.query("SELECT value FROM history_readings WHERE id = 9").use { cursor ->
                assertTrue("the reading survived", cursor.moveToFirst())
                assertEquals(120.0, cursor.getDouble(0), 0.001)
            }
            db.query("SELECT recoveryId FROM journal_entries WHERE id = 7").use { cursor ->
                assertTrue("the recovery identity was assigned", cursor.moveToFirst())
                assertTrue(
                    "recoveryId is a 32-hex id",
                    cursor.getString(0).matches(Regex("[0-9a-f]{32}"))
                )
            }
            // Both recovery tables are Room entities, so validateDroppedTables already
            // checked their shape; this only proves they exist and are queryable.
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN ('clone_journal_recovery_tombstones', 'clone_recovery_imports')"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("both recovery tables exist", 2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun duplicateJournalRecoveryIdentityIsRejected() {
        seedReleasedV11 {
            execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                    "VALUES (7, 1000, 'note', 'A', 'manual', 900, 1100)"
            )
            execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                    "VALUES (8, 2000, 'note', 'B', 'manual', 1900, 2100)"
            )
        }

        migrateToCurrent().use { db ->
            try {
                db.execSQL(
                    "UPDATE journal_entries SET recoveryId = " +
                        "(SELECT recoveryId FROM journal_entries WHERE id = 7) WHERE id = 8"
                )
                fail("Duplicate journal recovery identity accepted")
            } catch (expected: SQLException) {
                assertTrue(expected.message.orEmpty().contains("UNIQUE"))
            }
            db.query("SELECT COUNT(DISTINCT recoveryId) FROM journal_entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("each row got its own identity", 2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun recoveryTombstoneRejectsDuplicateRecoveryIdentity() {
        seedReleasedV11 { }

        migrateToCurrent().use { db ->
            db.execSQL(
                "INSERT INTO clone_journal_recovery_tombstones " +
                    "(stableBaseId, recoveryId, deletedAt) " +
                    "VALUES ('a', '0123456789abcdef0123456789abcdef', 700)"
            )
            try {
                db.execSQL(
                    "INSERT INTO clone_journal_recovery_tombstones " +
                        "(stableBaseId, recoveryId, deletedAt) " +
                        "VALUES ('b', '0123456789abcdef0123456789abcdef', 800)"
                )
                fail("Duplicate recovery tombstone identity accepted")
            } catch (expected: SQLException) {
                assertTrue(expected.message.orEmpty().contains("UNIQUE"))
            }
            db.query("SELECT COUNT(*) FROM clone_journal_recovery_tombstones").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun receiptTableRejectsDuplicateJobIds() {
        seedReleasedV11 { }

        migrateToCurrent().use { db ->
            db.execSQL("INSERT INTO clone_recovery_imports (jobId, sha256) VALUES ('job', 'first')")
            try {
                db.execSQL("INSERT INTO clone_recovery_imports (jobId, sha256) VALUES ('job', 'second')")
                fail("Duplicate receipt accepted")
            } catch (expected: SQLException) {
                assertTrue(expected.message.orEmpty().contains("UNIQUE"))
            }
            db.query("SELECT sha256 FROM clone_recovery_imports").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("first", cursor.getString(0))
                assertEquals(1, cursor.count)
            }
        }
    }

    @Test
    fun migrationDoesNotChangeSeededHistory() {
        seedReleasedV11 {
            execSQL(
                "INSERT INTO history_readings (id, timestamp, sensorSerial, value, rawValue) " +
                    "VALUES (9, 1000, 'SENSOR', 120.0, 119.0)"
            )
        }

        migrateToCurrent().use { db ->
            db.query("SELECT value, rawValue FROM history_readings WHERE id = 9").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(120.0, cursor.getDouble(0), 0.001)
                assertEquals(119.0, cursor.getDouble(1), 0.001)
            }
        }
    }

    /**
     * A Clone build that stopped at v20: `clone_journal_tombstones` as v20 created it, before
     * the column existed, journal recovery ids still empty (the bridge to v30 adds the column
     * without filling it), and neither later recovery table. v32 fills every missing journal
     * identity (the tombstone's stays NULL), keeps the tombstone, adds only what is missing,
     * and ends in the entity's shape.
     */
    @Test
    fun cloneV20HistoryGainsIdentitiesAndKeepsTombstones() {
        helper.createDatabase(DB_NAME, HISTORY_DATABASE_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                    "VALUES (7, 1000, 'note', 'A', 'manual', 900, 1100)"
            )
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                    "VALUES (8, 2000, 'note', 'B', 'manual', 1900, 2100)"
            )
            db.execSQL("DROP TABLE clone_journal_tombstones")
            db.execSQL(
                "CREATE TABLE clone_journal_tombstones " +
                    "(entryId INTEGER PRIMARY KEY NOT NULL, deletedAt INTEGER NOT NULL)"
            )
            db.execSQL("INSERT INTO clone_journal_tombstones (entryId, deletedAt) VALUES (4, 700)")
            db.execSQL("DROP TABLE clone_journal_recovery_tombstones")
            db.execSQL("DROP TABLE clone_recovery_imports")
            db.version = 31
        }

        migrateToCurrent().use { db ->
            db.query("SELECT recoveryId FROM journal_entries ORDER BY id").use { cursor ->
                val ids = ArrayList<String>()
                while (cursor.moveToNext()) ids += cursor.getString(0)
                assertEquals(2, ids.size)
                assertTrue("every row got an identity: $ids", ids.all { it.matches(Regex("[0-9a-f]{32}")) })
                assertEquals("each its own", 2, ids.toSet().size)
            }
            db.query("SELECT deletedAt, recoveryId FROM clone_journal_tombstones WHERE entryId = 4").use { cursor ->
                assertTrue("the v20 tombstone survived", cursor.moveToFirst())
                assertEquals(700L, cursor.getLong(0))
                assertTrue("its new recoveryId column is empty", cursor.isNull(1))
            }
            assertBothRecoveryTablesExist(db)
        }
    }

    /**
     * A Clone build that stopped at v21, before v22 and v23 added the two recovery tables.
     * v21 added both recoveryId columns but backfilled only the journal one; a local delete from
     * then on copies the deleted row's id into its tombstone (older tombstones, and ones restored
     * from a pre-v21 backup, keep NULL; see the v20 test).
     * Whatever v32 does to those tables, it must keep every identity and tombstone as they are.
     */
    @Test
    fun cloneV21HistoryKeepsItsIdentities() {
        helper.createDatabase(DB_NAME, HISTORY_DATABASE_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt, recoveryId) " +
                    "VALUES (7, 1000, 'note', 'A', 'manual', 900, 1100, '0123456789abcdef0123456789abcdef')"
            )
            db.execSQL(
                "INSERT INTO clone_journal_tombstones (entryId, deletedAt, recoveryId) " +
                    "VALUES (4, 700, 'abcdef0123456789abcdef0123456789')"
            )
            db.execSQL("DROP TABLE clone_journal_recovery_tombstones")
            db.execSQL("DROP TABLE clone_recovery_imports")
            db.version = 31
        }

        migrateToCurrent().use { db ->
            db.query("SELECT recoveryId FROM journal_entries WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("0123456789abcdef0123456789abcdef", cursor.getString(0))
            }
            db.query("SELECT deletedAt, recoveryId FROM clone_journal_tombstones WHERE entryId = 4").use { cursor ->
                assertTrue("the tombstone survived", cursor.moveToFirst())
                assertEquals(700L, cursor.getLong(0))
                assertEquals("abcdef0123456789abcdef0123456789", cursor.getString(1))
            }
            assertBothRecoveryTablesExist(db)
        }
    }

    /**
     * A Clone build at v23 or later: both recovery tables exist and hold rows — recovered
     * journal deletions and the import receipts that stop a destructive recovery import from
     * being replayed after a crash. v32 must keep those rows unchanged; it only creates the
     * tables where they are absent.
     */
    @Test
    fun cloneV23HistoryKeepsRecoveryTables() {
        helper.createDatabase(DB_NAME, HISTORY_DATABASE_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt, recoveryId) " +
                    "VALUES (7, 1000, 'note', 'A', 'manual', 900, 1100, '0123456789abcdef0123456789abcdef')"
            )
            db.execSQL(
                "INSERT INTO clone_journal_tombstones (entryId, deletedAt, recoveryId) " +
                    "VALUES (4, 700, 'abcdef0123456789abcdef0123456789')"
            )
            db.execSQL(
                "INSERT INTO clone_journal_recovery_tombstones (stableBaseId, recoveryId, deletedAt) " +
                    "VALUES ('base', 'fedcba9876543210fedcba9876543210', 800)"
            )
            db.execSQL("INSERT INTO clone_recovery_imports (jobId, sha256) VALUES ('job', 'digest')")
            db.version = 31
        }

        migrateToCurrent().use { db ->
            db.query("SELECT recoveryId FROM journal_entries WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("0123456789abcdef0123456789abcdef", cursor.getString(0))
            }
            db.query("SELECT deletedAt, recoveryId FROM clone_journal_tombstones WHERE entryId = 4").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(700L, cursor.getLong(0))
                assertEquals("abcdef0123456789abcdef0123456789", cursor.getString(1))
            }
            db.query(
                "SELECT stableBaseId, recoveryId, deletedAt FROM clone_journal_recovery_tombstones"
            ).use { cursor ->
                assertTrue("the recovered deletion survived", cursor.moveToFirst())
                assertEquals("base", cursor.getString(0))
                assertEquals("fedcba9876543210fedcba9876543210", cursor.getString(1))
                assertEquals(800L, cursor.getLong(2))
                assertEquals(1, cursor.count)
            }
            db.query("SELECT jobId, sha256 FROM clone_recovery_imports").use { cursor ->
                assertTrue("the import receipt survived", cursor.moveToFirst())
                assertEquals("job", cursor.getString(0))
                assertEquals("digest", cursor.getString(1))
                assertEquals(1, cursor.count)
            }
        }
    }

    private fun assertBothRecoveryTablesExist(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('clone_journal_recovery_tombstones', 'clone_recovery_imports')"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("both recovery tables were created", 2, cursor.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "clone-recovery-migration-test.db"
    }
}
