package tk.glucodata.data

import androidx.room.testing.MigrationTestHelper
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
 * Populated Clone/test-build histories on the H3 runner (plan tasks H2b/H4,
 * follow-up from the #426 review).
 *
 * The released tests start from v11/v12 only, so the Clone-bridge steps and the
 * "already present" branches of the idempotent ensures were never exercised with
 * real Clone data on disk. These start from the schemas recovered from the Clone
 * commits:
 *
 * - v23 (`0b171161d`) and v29 (`4edf7faca`) are Clone builds: they already own the
 *   recovery tables and the provenance columns, so `ensureV30Compatibility` and
 *   `ensureCloneSchema` take their guarded "column/table already exists" paths.
 * - v30 (`c3a461722`) and v31 (`8eab03bd1`) are the test-branch stepping stones:
 *   they have the compatibility columns but not the recovery tables, so the
 *   tables are created and the identities backfilled at v32.
 *
 * What matters is that an existing `recoveryId` is kept (the `WHERE recoveryId IS
 * NULL` guard), that the populated recovery rows survive, and that the unique
 * indexes do not collide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Keep Conscrypt from becoming the JVM-wide top JCA provider; see HistoryMigrationTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
class CloneBuildMigrationTests {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HistoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun populatedCloneV23KeepsItsRecoveryState() = assertPopulatedCloneBuildSurvives(23)

    @Test
    fun populatedCloneV29KeepsItsRecoveryState() = assertPopulatedCloneBuildSurvives(29)

    private fun assertPopulatedCloneBuildSurvives(from: Int) {
        val databaseName = "clone-build-$from.db"
        helper.createDatabase(databaseName, from).use { db ->
            db.execSQL(
                "INSERT INTO history_readings " +
                    "(id, timestamp, sensorSerial, value, rawValue, rate, source, firstStoredAt) " +
                    "VALUES (9, 1000, 'SENSOR', 120.0, 119.0, 0.5, 'clone_turn', 777)"
            )
            // One row already carries a recovery identity; the other must get one.
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, originSource, recoveryId, createdAt, updatedAt) " +
                    "VALUES (7, 1000, 'note', 'A', 'clone_turn', NULL, '$EXISTING_RECOVERY_ID', 900, 1100)"
            )
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                    "VALUES (8, 2000, 'note', 'B', 'clone_turn', 1900, 2100)"
            )
            db.execSQL(
                "INSERT INTO clone_journal_tombstones (entryId, deletedAt, recoveryId) " +
                    "VALUES (5, 700, '$TOMBSTONE_RECOVERY_ID')"
            )
            db.execSQL(
                "INSERT INTO clone_journal_recovery_tombstones (stableBaseId, recoveryId, deletedAt) " +
                    "VALUES ('base-1', '$RECOVERY_TOMBSTONE_ID', 700)"
            )
            db.execSQL("INSERT INTO clone_recovery_imports (jobId, sha256) VALUES ('job-1', 'abc')")
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            HISTORY_DATABASE_VERSION,
            true,
            *HistoryDatabase.ALL_MIGRATIONS
        )

        migrated.query(
            "SELECT source, firstStoredAt FROM history_readings WHERE id = 9"
        ).use { cursor ->
            assertTrue("the reading survived", cursor.moveToFirst())
            assertEquals("the Clone source is kept", "clone_turn", cursor.getString(0))
            assertEquals("firstStoredAt is not reset", 777L, cursor.getLong(1))
        }
        migrated.query(
            "SELECT title, source, originSource, recoveryId FROM journal_entries WHERE id = 7"
        ).use { cursor ->
            assertTrue("the identified row survived", cursor.moveToFirst())
            assertEquals("A", cursor.getString(0))
            assertEquals("clone_turn", cursor.getString(1))
            assertTrue("a Clone origin is not claimed as manual", cursor.isNull(2))
            assertEquals("an existing recoveryId is kept", EXISTING_RECOVERY_ID, cursor.getString(3))
        }
        migrated.query(
            "SELECT recoveryId FROM journal_entries WHERE id = 8"
        ).use { cursor ->
            assertTrue("the unidentified row survived", cursor.moveToFirst())
            assertTrue(
                "a null recoveryId is filled in",
                cursor.getString(0).matches(Regex("[0-9a-f]{32}")),
            )
        }
        assertEquals(
            "the two identities do not collide",
            2,
            distinctRecoveryIds(migrated),
        )
        assertEquals(
            "the local tombstone kept its identity",
            TOMBSTONE_RECOVERY_ID,
            singleString(migrated, "SELECT recoveryId FROM clone_journal_tombstones WHERE entryId = 5"),
        )
        assertEquals(
            "the recovered tombstone survived",
            RECOVERY_TOMBSTONE_ID,
            singleString(migrated, "SELECT recoveryId FROM clone_journal_recovery_tombstones WHERE stableBaseId = 'base-1'"),
        )
        assertEquals(
            "the import receipt survived",
            "abc",
            singleString(migrated, "SELECT sha256 FROM clone_recovery_imports WHERE jobId = 'job-1'"),
        )
        migrated.close()
    }

    @Test
    fun testBuildV30AndV31ArriveWithoutRecoveryTablesAndGetThem() {
        listOf(30, 31).forEach { from ->
            val databaseName = "test-build-$from.db"
            helper.createDatabase(databaseName, from).use { db ->
                db.execSQL(
                    "INSERT INTO history_readings " +
                        "(id, timestamp, sensorSerial, value, rawValue, source, firstStoredAt) " +
                        "VALUES (9, 1000, 'SENSOR', 120.0, 119.0, 'sensor', 0)"
                )
                db.execSQL(
                    "INSERT INTO journal_entries " +
                        "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                        "VALUES (7, 1000, 'note', 'local', 'manual', 900, 1100)"
                )
                db.execSQL(
                    "INSERT INTO journal_entries " +
                        "(id, timestamp, entryType, title, source, createdAt, updatedAt) " +
                        "VALUES (8, 2000, 'note', 'remote', 'nightscout', 1900, 2100)"
                )
            }

            helper.runMigrationsAndValidate(
                databaseName,
                HISTORY_DATABASE_VERSION,
                true,
                *HistoryDatabase.ALL_MIGRATIONS
            ).use { migrated ->
                migrated.query("SELECT source, firstStoredAt FROM history_readings WHERE id = 9").use { cursor ->
                    assertTrue("the reading survived from v$from", cursor.moveToFirst())
                    assertEquals("sensor", cursor.getString(0))
                    assertEquals("firstStoredAt is backfilled from the id", 9L, cursor.getLong(1))
                }
                migrated.query("SELECT originSource, recoveryId FROM journal_entries WHERE id = 7").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("a local source becomes its own origin", "manual", cursor.getString(0))
                    assertTrue("the identity is assigned", !cursor.isNull(1))
                }
                migrated.query("SELECT originSource, recoveryId FROM journal_entries WHERE id = 8").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue("a Nightscout source is not claimed as manual", cursor.isNull(0))
                    assertTrue("the identity is assigned", !cursor.isNull(1))
                }
                migrated.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                        "AND name IN ('clone_journal_tombstones', 'clone_journal_recovery_tombstones', " +
                        "'clone_recovery_imports')"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("the recovery tables were created at v32", 3, cursor.getInt(0))
                }
            }
        }
    }

    private fun distinctRecoveryIds(db: androidx.sqlite.db.SupportSQLiteDatabase): Int =
        db.query("SELECT COUNT(DISTINCT recoveryId) FROM journal_entries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun singleString(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue("expected one row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val EXISTING_RECOVERY_ID = "11111111111111111111111111111111"
        const val TOMBSTONE_RECOVERY_ID = "22222222222222222222222222222222"
        const val RECOVERY_TOMBSTONE_ID = "33333333333333333333333333333333"
    }
}
