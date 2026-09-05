package tk.glucodata

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloneRecoveryImportLedgerTests {
    private val manifest = CloneRecoveryManifest(
        protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
        jobId = "0123456789abcdef0123456789abcdef",
        direction = CloneRecoveryDirection.RECOVER_FROM_RECEIVER,
        mode = CloneRecoveryMode.FULL_HISTORY,
        categories = CloneRecoveryCategories.GLUCOSE,
        compressedBytes = 20, uncompressedBytes = 0,
        recordCounts = mapOf("glucose" to 0L), sha256 = "a".repeat(64),
    )

    private fun database(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
            db.exec("CREATE TABLE readings(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            db.exec("CREATE TABLE unrelated(note TEXT)")
            db.exec("INSERT INTO readings VALUES(1,100)")
            db.exec("INSERT INTO unrelated VALUES('keep')")
            db.exec("CREATE TABLE receipts(jobId TEXT PRIMARY KEY, sha256 TEXT NOT NULL)")
        }
    }

    private suspend fun import(db: Connection, candidate: CloneRecoveryManifest = manifest,
        failReceipt: Boolean = false, records: suspend () -> Unit) = CloneRecoveryImportLedger.importOnce(
        manifest = candidate,
        transaction = { operation ->
            db.autoCommit = false
            try { operation(); db.commit() } catch (error: Throwable) { db.rollback(); throw error }
            finally { db.autoCommit = true }
        },
        readDigest = {
            db.prepareStatement("SELECT sha256 FROM receipts WHERE jobId=?").use { query ->
                query.setString(1, candidate.jobId)
                query.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
        },
        writeDigest = {
            if (failReceipt) error("receipt failure")
            db.prepareStatement("INSERT INTO receipts(jobId,sha256) VALUES(?,?)").use { insert ->
                insert.setString(1, candidate.jobId); insert.setString(2, candidate.sha256); insert.executeUpdate()
            }
        },
        importRecords = records,
    )

    @Test fun fullReplacementAndReceiptCommitTogether() = runBlocking {
        database().use { db ->
            import(db) { db.exec("DELETE FROM readings"); db.exec("INSERT INTO readings VALUES(2,120)") }
            assertEquals("2:120", db.value("SELECT id || ':' || value FROM readings"))
            assertEquals(manifest.sha256, db.value("SELECT sha256 FROM receipts"))
            assertEquals("keep", db.value("SELECT note FROM unrelated"))
        }
    }

    @Test fun failureAfterClearRollsBackHistoryAndLeavesNoReceipt() = runBlocking {
        database().use { db ->
            try { import(db) { db.exec("DELETE FROM readings"); db.exec("INSERT INTO readings VALUES(2,120)"); error("injected import failure") }; fail("Import succeeded") }
            catch (error: IllegalStateException) { assertEquals("injected import failure", error.message) }
            assertEquals("1:100", db.value("SELECT id || ':' || value FROM readings"))
            assertEquals("0", db.value("SELECT COUNT(*) FROM receipts"))
            assertEquals("keep", db.value("SELECT note FROM unrelated"))
        }
    }

    @Test fun failedReceiptRollsBackImportedRowsToo() = runBlocking {
        database().use { db ->
            try { import(db, failReceipt = true) { db.exec("DELETE FROM readings") }; fail("Import succeeded") }
            catch (error: IllegalStateException) { assertEquals("receipt failure", error.message) }
            assertEquals("100", db.value("SELECT value FROM readings"))
            assertEquals("0", db.value("SELECT COUNT(*) FROM receipts"))
        }
    }

    @Test fun replayAfterCommitKeepsNewLiveReadings() = runBlocking {
        database().use { db ->
            import(db) { db.exec("DELETE FROM readings"); db.exec("INSERT INTO readings VALUES(2,120)") }
            // Simulates losing only the file-based completion status after the database commit.
            db.exec("INSERT INTO readings VALUES(3,130)")
            import(db) { fail("Committed Full History must not clear records again") }
            assertEquals("2", db.value("SELECT COUNT(*) FROM readings"))
            assertEquals("130", db.value("SELECT value FROM readings WHERE id=3"))
            assertEquals("1", db.value("SELECT COUNT(*) FROM receipts"))
        }
    }

    @Test fun sameJobWithDifferentDigestIsRejected() = runBlocking {
        database().use { db ->
            import(db) { }
            try { import(db, manifest.copy(sha256 = "b".repeat(64))) { fail("Changed package imported") }; fail("Replay accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals("100", db.value("SELECT value FROM readings"))
        }
    }

    private fun Connection.exec(sql: String) { createStatement().use { it.execute(sql) } }
    private fun Connection.value(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> assertTrue(result.next()); result.getString(1) }
    }
}
