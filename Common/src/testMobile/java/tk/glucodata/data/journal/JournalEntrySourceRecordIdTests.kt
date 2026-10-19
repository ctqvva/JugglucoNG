package tk.glucodata.data.journal

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.HistoryDatabase

/**
 * What the database itself guarantees about a journal entry's `sourceRecordId` — the field a watch's
 * entry identity lands in (#511), and the field a repeated command has to be caught by.
 *
 * [JournalEntryMatchTests] pins the rule that decides which row an incoming entry is the same entry
 * as. This pins the two things underneath it, which is what was left when #511 said that no test
 * calls `upsertEntry`:
 *
 *  - `sourceRecordId` carries a **unique** index and the DAO inserts with `REPLACE`, so even if the
 *    match rule picked nothing, a second row with the same identity **replaces** the first rather
 *    than joining it. The failure mode of a broken rule is therefore a new row id on the same
 *    reading, not two rows for one reading.
 *  - a **null** identity is not a shared identity: SQLite's unique index treats every NULL as
 *    distinct, so entries from a watch too old to send an identity (or a phone-originated entry, which
 *    has none) still live side by side. That is the case that would silently lose entries if the
 *    index collapsed NULLs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Robolectric installs Conscrypt as the top JCA provider for the whole test JVM; leave the platform
// provider in place, same as HistoryMigrationTest, so this class cannot change what the crypto tests
// in the same JVM exercise.
@ConscryptMode(ConscryptMode.Mode.OFF)
class JournalEntrySourceRecordIdTests {

    private lateinit var database: HistoryDatabase
    private lateinit var dao: JournalDao

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.journalDao()
    }

    @After
    fun closeDatabase() = database.close()

    private fun entry(id: Long = 0L, identity: String?) = JournalEntryEntity(
        id = id,
        timestamp = 1_700_000_000_000L,
        sensorSerial = "SIBI:0123456789ABCDEF",
        entryType = JournalEntryType.INSULIN.storageValue,
        title = "3.5 U",
        note = null,
        amount = 3.5f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        source = JournalEntrySource.MANUAL.storageValue,
        sourceRecordId = identity,
        recoveryId = null,
        createdAt = 0L,
        updatedAt = 0L,
        nsRemoteId = null,
    )

    private fun countRowsFor(identity: String?): Int = rowsWhere(
        if (identity == null) "sourceRecordId IS NULL" else "sourceRecordId = ?",
        if (identity == null) emptyArray() else arrayOf<Any?>(identity),
    )

    private fun rowsWhere(where: String, args: Array<out Any?>): Int = runBlocking {
        database.openHelper.readableDatabase
            .query(SimpleSQLiteQuery("SELECT COUNT(*) FROM journal_entries WHERE $where", args))
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
    }

    @Test
    fun theSameIdentityIsOneRow() = runBlocking {
        dao.upsertEntry(entry(identity = "wear:abcd1234:1"))
        val first = dao.getEntryBySourceRecordId("wear:abcd1234:1")
        assertNotNull("the row is there to be found again", first)

        // The same reading arriving a second time, as a command without an id of its own.
        dao.upsertEntry(entry(identity = "wear:abcd1234:1"))

        assertEquals("a repeated identity is not two rows", 1, countRowsFor("wear:abcd1234:1"))
    }

    @Test
    fun differentIdentitiesAreDifferentRows() = runBlocking {
        dao.upsertEntry(entry(identity = "wear:abcd1234:1"))
        dao.upsertEntry(entry(identity = "wear:abcd1234:2"))
        assertEquals(1, countRowsFor("wear:abcd1234:1"))
        assertEquals(1, countRowsFor("wear:abcd1234:2"))
        assertEquals(2, totalRows())
    }

    @Test
    fun anEntryWithNoIdentityDoesNotCollideWithAnyOther() = runBlocking {
        // A phone-originated entry has no source record id, and a watch older than #511 sends none.
        // If the index treated those as one identity, one of them would vanish.
        dao.upsertEntry(entry(identity = null))
        dao.upsertEntry(entry(identity = null))
        dao.upsertEntry(entry(identity = "wear:abcd1234:1"))
        assertEquals(2, countRowsFor(null))
        assertEquals(3, totalRows())
    }

    @Test
    fun anIdentityThatIsNotThereIsNotFound() = runBlocking {
        assertNull(dao.getEntryBySourceRecordId("wear:abcd1234:99"))
    }

    private fun totalRows(): Int = rowsWhere("1 = 1", emptyArray())
}
