package tk.glucodata.data.journal

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.data.HistoryDatabase
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Entries edited or deleted while their upload to Nightscout is on the way, run through the
 * real uploader against a stand-in for the server.
 *
 * [FakeNightscout.beforeAnswer] is the delayed response: it runs once the server has stored a
 * document and before the uploader hears so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = JournalUploadRaceTests.TestApplication::class,
    shadows = [UploadRaceNatives::class, UploadRaceNightPost::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
class JournalUploadRaceTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private lateinit var server: FakeNightscout
    private var previousApp: Applic? = null
    private val dao get() = database.journalDao()
    private val base = System.currentTimeMillis() - 60 * 60_000L

    @Before
    fun setUp() {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        database = Room.inMemoryDatabaseBuilder(Applic.app, HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        useDatabase(database)
        server = FakeNightscout()
        UploadRaceNightPost.server = server
        UploadRaceNatives.url = server.url
        UploadRaceNatives.wakes = 0
        resetBackoff()
    }

    @After
    fun tearDown() {
        server.close()
        useDatabase(null)
        database.close()
        Applic.app = previousApp
    }

    private fun useDatabase(value: HistoryDatabase?) {
        HistoryDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, value)
    }

    private fun resetBackoff() {
        val backoff = JournalTreatmentUploader::class.java.getDeclaredField("sendBackoff")
            .apply { isAccessible = true }
            .get(JournalTreatmentUploader) as JournalTreatmentUploader.SendBackoff
        backoff.reset()
    }

    private fun entry(id: Long = 1, amount: Float = 4f, type: JournalEntryType = JournalEntryType.INSULIN) =
        JournalEntryEntity(
            id = id,
            timestamp = base + id * 60_000L,
            sensorSerial = null,
            entryType = type.storageValue,
            title = "entry $id",
            note = null,
            amount = amount,
            glucoseValueMgDl = null,
            durationMinutes = null,
            intensity = null,
            insulinPresetId = null,
            source = JournalEntrySource.MANUAL.storageValue,
            sourceRecordId = null,
            createdAt = base + id,
            updatedAt = base + id,
        )

    private fun add(entry: JournalEntryEntity = entry()) = runBlocking { dao.upsertEntry(entry) }
    private fun row(id: Long = 1): JournalEntryEntity? = runBlocking { dao.getEntryById(id) }

    private fun edit(id: Long = 1, change: JournalEntryEntity.() -> JournalEntryEntity) = runBlocking {
        val current = dao.getEntryById(id)!!
        // Edits are stamped with the clock; a finished upload never shares their millisecond here.
        val now = maxOf(System.currentTimeMillis(), current.updatedAt + 1, (current.nsUploadedAt ?: 0L) + 1)
        dao.upsertEntry(current.change().copy(updatedAt = now))
        Unit
    }

    private fun delete(id: Long = 1) = runBlocking {
        database.withTransaction {
            dao.recordEntryDeletion(dao.getEntryById(id)!!, System.currentTimeMillis())
            dao.deleteEntryById(id)
        }
    }

    private fun upload(v3: Boolean): Boolean = JournalTreatmentUploader.uploadAll(v3)
    private fun isDue(id: Long = 1) = runBlocking { dao.getEntriesNeedingNightscoutUpload(0L).any { it.id == id } }
    private fun pendingDeletes() = runBlocking { dao.getPendingNightscoutDeletes().map { it.nsRemoteId }.toSet() }
    private fun onTheWay() = runBlocking { dao.getNightscoutUploads().map { it.remoteId }.toSet() }

    /** Every API the app can be talking to: v3, current v1, and v1 as it was until 15.0.6. */
    private fun onEveryServer(test: (v3: Boolean) -> Unit) {
        for ((v3, legacy) in listOf(true to false, false to false, false to true)) {
            runBlocking {
                dao.deleteAllEntries()
                dao.getPendingNightscoutDeletes().forEach { dao.clearPendingNightscoutDelete(it.nsRemoteId) }
                dao.getNightscoutUploads().forEach { dao.clearNightscoutUpload(it.remoteId) }
            }
            resetBackoff()
            server.reset(legacy)
            test(v3)
        }
    }

    private fun settled() {
        assertTrue("nothing left to delete: ${pendingDeletes()}", pendingDeletes().isEmpty())
        assertTrue("nothing left on the way: ${onTheWay()}", onTheWay().isEmpty())
    }

    // ── Edited during the upload ──

    @Test
    fun anAmountEditedDuringItsUploadIsSentNext() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = { edit { copy(amount = 2f) } }
        assertTrue(upload(v3))

        assertEquals("the server took the first revision", 4.0, server.only().getDouble("insulin"), 0.0)
        assertTrue("the correction is still due", isDue())
        assertTrue("and known to be on the server", row()!!.nsRemoteId != null && row()!!.nsUploadedAt != null)

        assertTrue(upload(v3))
        assertEquals(2.0, server.only().getDouble("insulin"), 0.0)
        assertFalse(isDue())
        settled()
    }

    @Test
    fun aTimeEditedDuringItsUploadReplacesTheAcceptedDocument() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = { edit { copy(timestamp = timestamp + 600_000L) } }
        assertTrue(upload(v3))
        assertTrue(isDue())

        assertTrue(upload(v3))
        assertEquals("one document, at the corrected time", entry().timestamp + 600_000L, server.only().getLong("date"))
        assertFalse(isDue())
        settled()
    }

    @Test
    fun aTypeEditedDuringItsUploadReplacesTheAcceptedDocument() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = { edit { copy(entryType = JournalEntryType.CARBS.storageValue, amount = 30f) } }
        assertTrue(upload(v3))
        assertTrue(isDue())

        assertTrue("v3 must not be left refusing it forever", upload(v3))
        assertEquals(30.0, server.only().getDouble("carbs"), 0.0)
        assertFalse("the insulin is gone", server.only().has("insulin"))
        assertFalse(isDue())
        settled()

        edit { copy(amount = 40f) }
        assertTrue("and a later edit is an ordinary one", upload(v3))
        assertEquals(40.0, server.only().getDouble("carbs"), 0.0)
        settled()
    }

    @Test
    fun aTimeMovedOnAndBackEndsAtOneDocument() = onEveryServer { v3 ->
        add()
        assertTrue(upload(v3))
        edit { copy(timestamp = timestamp + 600_000L) }
        server.beforeAnswer = { edit { copy(timestamp = timestamp - 600_000L) } }
        assertTrue(upload(v3))
        assertTrue(upload(v3))
        assertEquals(entry().timestamp, server.only().getLong("date"))
        assertFalse(isDue())
        settled()
    }

    @Test
    fun anEditInTheSameMillisecondStaysDue() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = { runBlocking { dao.upsertEntry(row()!!.copy(amount = 2f)) } }
        assertTrue(upload(v3))
        assertTrue(isDue())
        assertTrue(upload(v3))
        assertEquals(2.0, server.only().getDouble("insulin"), 0.0)
    }

    @Test
    fun anUnchangedEntryIsSentOnce() = onEveryServer { v3 ->
        add()
        assertTrue(upload(v3))
        assertFalse(isDue())
        assertTrue(upload(v3))
        assertEquals(1, server.writes)
        settled()
    }

    @Test
    fun anEditMadeBehindAnotherUploadGoesOutAsItIsNow() = onEveryServer { v3 ->
        add(entry(1))
        add(entry(2))
        server.beforeAnswer = { edit(2) { copy(amount = 2f) } }
        assertTrue(upload(v3))
        assertEquals(setOf(4.0, 2.0), server.documents.map { it.getDouble("insulin") }.toSet())
        assertFalse(isDue(2))
    }

    @Test
    fun theEditAsksForAnotherPass() {
        add()
        server.beforeAnswer = { edit { copy(amount = 2f) } }
        upload(v3 = true)
        assertEquals(1, UploadRaceNatives.wakes)
    }

    // ── Deleted during the upload ──

    @Test
    fun anEntryDeletedDuringItsFirstUploadIsDeletedThere() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = { delete() }
        assertTrue(upload(v3))
        assertNull(row())

        upload(v3)
        assertTrue("${server.documents}", server.documents.isEmpty())
        settled()
    }

    @Test
    fun anEntryDeletedDuringAnUpdateLeavesNeitherCopy() = onEveryServer { v3 ->
        add()
        assertTrue(upload(v3))
        edit { copy(amount = 2f, timestamp = timestamp + 600_000L) }
        server.beforeAnswer = { delete() }
        assertTrue(upload(v3))

        upload(v3)
        assertTrue("${server.documents}", server.documents.isEmpty())
        settled()
    }

    @Test
    fun aDeletedEntryBehindAnotherUploadIsNeverSent() = onEveryServer { v3 ->
        add(entry(1))
        add(entry(2))
        server.beforeAnswer = { delete(2) }
        assertTrue(upload(v3))
        assertEquals(1, server.writes)
        settled()
    }

    @Test
    fun clearingTheJournalDuringAnUploadLeavesNothingBehind() = onEveryServer { v3 ->
        (1L..3L).forEach { add(entry(it)) }
        var answered = 0
        server.beforeEveryAnswer = { if (++answered == 2) runBlocking { database.deleteAllJournalEntries() } }
        assertTrue(upload(v3))

        server.beforeEveryAnswer = {}
        upload(v3)
        assertTrue("${server.documents}", server.documents.isEmpty())
        settled()
    }

    @Test
    fun aRefusedCompensatingDeleteIsKeptForEachDocument() {
        add()
        assertTrue(upload(v3 = true))
        val first = row()!!.nsRemoteId!!
        server.refuseDeletes = true
        edit { copy(timestamp = timestamp + 600_000L) }
        server.beforeAnswer = { delete() }
        upload(v3 = true)
        assertEquals("both the old copy and the new document", 2, pendingDeletes().size)
        assertTrue(first in pendingDeletes())

        server.refuseDeletes = false
        upload(v3 = true)
        assertTrue(server.documents.isEmpty())
        settled()
    }

    @Test
    fun aRecreatedEntryIsNotTakenWithTheOneDeleted() = onEveryServer { v3 ->
        add()
        assertTrue(upload(v3))
        server.refuseDeletes = true
        delete()
        add(entry(amount = 6f).copy(createdAt = base + 99, updatedAt = base + 99))
        assertTrue(upload(v3))
        assertEquals(6.0, server.only().getDouble("insulin"), 0.0)

        server.refuseDeletes = false
        assertTrue(upload(v3))
        assertEquals("still there", 6.0, server.only().getDouble("insulin"), 0.0)
        settled()
    }

    @Test
    fun aDeletionQueuedForAnEntrySinceRestoredIsDropped() = onEveryServer { v3 ->
        add()
        assertTrue(upload(v3))
        val uploaded = row()!!
        server.refuseDeletes = true
        delete()
        upload(v3)
        add(uploaded)

        server.refuseDeletes = false
        upload(v3)
        assertEquals("the restored entry's document stays", 4.0, server.only().getDouble("insulin"), 0.0)
        settled()
    }

    @Test
    fun aDocumentReplacedInPlaceWithoutAnAnswerIsNotTakenForItsOldCopy() {
        server.reset(legacy = true)
        add()
        assertTrue(upload(v3 = false))
        val remoteId = row()!!.nsRemoteId
        edit { copy(amount = 2f) }
        server.refuseReads = true
        assertTrue(upload(v3 = false))
        assertEquals(2.0, server.only().getDouble("insulin"), 0.0)
        assertEquals(remoteId, row()!!.nsRemoteId)
    }

    @Test
    fun anEntryRecreatedDuringTheUploadIsNotAcknowledgedForTheDeletedOne() = onEveryServer { v3 ->
        add()
        server.beforeAnswer = {
            delete()
            add(entry(amount = 6f).copy(createdAt = base + 99, updatedAt = base + 99))
        }
        assertTrue(upload(v3))
        assertTrue("the new entry was not the one sent", isDue())
        assertNull(row()!!.nsRemoteId)

        assertTrue(upload(v3))
        assertEquals(6.0, server.only().getDouble("insulin"), 0.0)
        assertFalse(isDue())
        assertTrue(upload(v3))
        assertEquals(6.0, server.only().getDouble("insulin"), 0.0)
        settled()
    }

    // ── No answer at all ──

    @Test
    fun anEntryDeletedAfterAnUnansweredUploadIsDeletedThere() = onEveryServer { v3 ->
        add()
        server.loseAnswer = true
        assertFalse(upload(v3))
        assertEquals("stored all the same", 1, server.documents.size)
        assertEquals(1, onTheWay().size)

        delete()
        resetBackoff()
        upload(v3)
        assertTrue("${server.documents}", server.documents.isEmpty())
        settled()
    }

    @Test
    fun theProcessEndingDuringAnUploadForgetsNothing() = onEveryServer { v3 ->
        add()
        // What is left in the database is all the next process has.
        server.beforeAnswer = { throw AssertionError("process ends here") }
        assertFalse(upload(v3))
        assertNull(row()!!.nsRemoteId)
        assertEquals(1, server.documents.size)

        delete()
        resetBackoff()
        upload(v3)
        assertTrue("${server.documents}", server.documents.isEmpty())
        settled()
    }

    @Test
    fun aTimeCorrectedAfterAnUnansweredUploadLeavesOneDocument() = onEveryServer { v3 ->
        add()
        server.loseAnswer = true
        assertFalse(upload(v3))
        edit { copy(timestamp = timestamp + 600_000L) }
        resetBackoff()
        assertTrue(upload(v3))
        assertEquals(entry().timestamp + 600_000L, server.only().getLong("date"))
        settled()
    }

    @Test
    fun aTypeCorrectedAfterAnUnansweredUploadLeavesOneDocument() = onEveryServer { v3 ->
        add()
        server.loseAnswer = true
        assertFalse(upload(v3))
        edit { copy(entryType = JournalEntryType.CARBS.storageValue, amount = 30f) }
        resetBackoff()
        assertTrue(upload(v3))
        assertEquals(30.0, server.only().getDouble("carbs"), 0.0)
        settled()
    }

    @Test
    fun anUnansweredUploadIsSentAgain() = onEveryServer { v3 ->
        add()
        server.loseAnswer = true
        assertFalse(upload(v3))
        assertTrue(isDue())
        resetBackoff()
        assertTrue(upload(v3))
        assertEquals(4.0, server.only().getDouble("insulin"), 0.0)
        assertFalse(isDue())
        assertTrue(upload(v3))
        assertEquals(1, server.documents.size)
        settled()
    }

    @Test
    fun aRefusedFirstUploadLeavesNothingToDelete() = onEveryServer { v3 ->
        add()
        server.refuseWrites = true
        assertFalse(upload(v3))
        assertTrue(onTheWay().isEmpty())
        delete()
        assertTrue(pendingDeletes().isEmpty())
    }

    @Test
    fun aLookupThatFailsKeepsTheDeletionQueued() {
        add()
        server.reset(legacy = false)
        server.loseAnswer = true
        assertFalse(upload(v3 = false))
        delete()
        resetBackoff()
        server.refuseReads = true
        upload(v3 = false)
        assertEquals("not being able to look is not the document being gone", 1, pendingDeletes().size)

        server.refuseReads = false
        upload(v3 = false)
        assertTrue(server.documents.isEmpty())
        settled()
    }

    @Test
    fun aDocumentOnTheWayIsRecognisedWhenItIsReadBack() = onEveryServer { v3 ->
        add()
        server.loseAnswer = true
        assertFalse(upload(v3))
        assertTrue(JournalTreatmentTransfer.hasAnyRemoteIdentifier(server.only(), onTheWay()))
    }

    @Test
    fun anotherPhonesDocumentUnderTheSameV1NameIsLeftAlone() {
        add()
        server.loseAnswer = true
        assertFalse(upload(v3 = false))
        // Row ids start over on every install, so the v1 name alone does not say whose it is.
        val other = JSONObject(server.only().toString()).put("_id", "f".repeat(24))
            .put("date", base - 86_400_000L).put("created_at", Instant.ofEpochMilli(base - 86_400_000L).toString())
        server.documents += other
        delete()
        resetBackoff()
        upload(v3 = false)
        assertEquals("f".repeat(24), server.only().getString("_id"))
    }

    // ── LibreView ──

    @Test
    fun libreViewAcknowledgesOnlyTheRowsThatStillSayWhatWasSent() = runBlocking {
        (1L..3L).forEach { dao.upsertEntry(entry(it)) }
        val sent = dao.getEntriesNeedingLibreviewUpload(0L)
        edit(1) { copy(amount = 2f) }
        delete(2)

        database.acknowledgeLibreviewUpload(sent, System.currentTimeMillis())

        assertEquals(listOf(1L), dao.getEntriesNeedingLibreviewUpload(0L).map { it.id })
        assertNull(row(1)!!.lvUploadedAt)
        assertTrue(row(3)!!.lvUploadedAt != null)
    }

    @Test
    fun aNightscoutAcknowledgmentIsNotAnEditToLibreView() = runBlocking {
        dao.upsertEntry(entry())
        val sent = dao.getEntriesNeedingLibreviewUpload(0L)
        dao.markEntryUploadedToNightscout(1, "remote", System.currentTimeMillis())
        database.acknowledgeLibreviewUpload(sent, System.currentTimeMillis())
        assertTrue(dao.getEntriesNeedingLibreviewUpload(0L).isEmpty())
    }
}

/**
 * The parts of Nightscout's treatments API the uploader depends on: v3 names documents by
 * identifier and refuses a changed date or eventType; v1 gives them an `_id`, replaces by
 * identifier on current servers, and by created_at and eventType, without answering which
 * document that was, up to 15.0.6.
 */
class FakeNightscout {
    val documents = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    var beforeAnswer: () -> Unit = {}
    var beforeEveryAnswer: () -> Unit = {}
    var loseAnswer = false
    var refuseWrites = false
    var refuseDeletes = false
    var refuseReads = false
    var writes = 0
    var lastBody = ""
    private var legacy = false
    private var nextId = 1

    private val socket = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { client.use { serveV1(it.getInputStream(), it.getOutputStream()) } }
            }
        }
    }

    fun close() = socket.close()

    fun reset(legacy: Boolean) {
        this.legacy = legacy
        documents.clear()
        beforeAnswer = {}
        beforeEveryAnswer = {}
        loseAnswer = false
        refuseWrites = false
        refuseDeletes = false
        refuseReads = false
        writes = 0
    }

    fun only(): JSONObject = documents.single()

    /** @return whether the answer reaches the client */
    private fun answering(): Boolean {
        writes++
        val once = beforeAnswer
        beforeAnswer = {}
        beforeEveryAnswer()
        once()
        return !loseAnswer.also { loseAnswer = false }
    }

    private fun refuse(code: Int, message: String): Int {
        lastBody = JSONObject().put("status", code).put("message", message).toString()
        return code
    }

    fun v3Write(url: String, body: ByteArray, patch: Boolean): Int {
        if (refuseWrites) return refuse(403, "Missing permission api:treatments:create")
        val document = JSONObject(String(body))
        val identifier = if (patch) url.substringAfterLast('/') else document.getString("identifier")
        val stored = documents.firstOrNull { it.optString("identifier") == identifier }
        if (patch && stored == null) return refuse(404, "Not found")
        for (field in listOf("date", "eventType", "app", "utcOffset", "isValid", "identifier")) {
            if (stored != null && document.has(field) && document.get(field).toString() != stored.get(field).toString()) {
                return refuse(400, "Field $field cannot be modified by the client")
            }
        }
        if (patch) {
            document.keys().forEach { stored!!.put(it, document.get(it)) }
        } else {
            if (stored != null) documents.remove(stored)
            documents += document
        }
        return if (answering()) 200 else NightPost.ERROR_NO_RESPONSE
    }

    fun delete(url: String): Int {
        val id = URLDecoder.decode(url.substringAfter("/treatments/").substringBefore('?'), "UTF-8")
        if (refuseDeletes) return refuse(403, "Missing permission api:treatments:delete")
        val v3 = "/api/v3/" in url
        val removed = documents.removeAll { (v3 && it.optString("identifier") == id) || it.optString("_id") == id }
        return if (removed || !v3) 200 else 404
    }

    private fun serveV1(input: InputStream, output: java.io.OutputStream) {
        fun line(): String = buildString {
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) append(c.toChar())
            }
        }
        val (method, path) = line().split(' ')
        var length = 0
        while (true) {
            val header = line().ifEmpty { break }
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
        val (code, answer) = if (method == "POST") v1Write(JSONObject(String(body))) else v1Read(path)
        val bytes = answer.toByteArray()
        output.write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
        output.flush()
    }

    private fun v1Write(document: JSONObject): Pair<Int, String> {
        if (refuseWrites) return 401 to """{"status":401,"message":"Unauthorized"}"""
        val stored = documents.firstOrNull {
            if (legacy) {
                it.optString("created_at") == document.optString("created_at") &&
                    it.optString("eventType") == document.optString("eventType")
            } else {
                it.optString("identifier") == document.optString("identifier")
            }
        }
        val answer = JSONObject(document.toString())
        document.put("_id", stored?.getString("_id") ?: (nextId++).toString(16).padStart(24, '0'))
        if (stored == null || !legacy) answer.put("_id", document.getString("_id"))
        if (stored != null) documents.remove(stored)
        documents += document
        // A lost answer is a gateway's here: the JDK would repeat a POST whose connection closed.
        return if (runCatching { answering() }.getOrDefault(false)) 200 to JSONArray().put(answer).toString() else 504 to ""
    }

    private fun v1Read(path: String): Pair<Int, String> {
        if (refuseReads) return 503 to ""
        val query = URLDecoder.decode(path.substringAfter('?', ""), "UTF-8").split('&')
        fun bound(op: String) = query.firstOrNull { it.startsWith("find[created_at][\$$op]=") }?.substringAfter('=')
        val from = bound("gte")
        val to = bound("lte")
        val found = documents
            .filter { (from == null || it.getString("created_at") >= from) && (to == null || it.getString("created_at") <= to) }
            .sortedByDescending { it.getString("created_at") }
        return 200 to JSONArray(found.map { JSONObject(it.toString()) }).toString()
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class UploadRaceNatives {
    companion object {
        var url = ""
        var wakes = 0

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getpostTreatments(): Boolean = true
        @JvmStatic @Implementation fun getuseuploader(): Boolean = true
        @JvmStatic @Implementation fun getnightuploadurl(): String = url
        @JvmStatic @Implementation fun getnightuploadsecret(): String = "secret"
        @JvmStatic @Implementation fun waketreatments() { wakes++ }
    }
}

@Implements(value = NightPost::class, callThroughByDefault = false)
class UploadRaceNightPost {
    companion object {
        lateinit var server: FakeNightscout

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation
        fun upload(httpurl: String, postdata: ByteArray, secret: String?, put: Boolean): Int =
            server.v3Write(httpurl, postdata, patch = false)
        @JvmStatic @Implementation
        fun uploadPatch(httpurl: String, postdata: ByteArray, secret: String?): Int =
            server.v3Write(httpurl, postdata, patch = true)
        @JvmStatic @Implementation fun deleteUrlCode(urlstring: String, secret: String?): Int = server.delete(urlstring)
        @JvmStatic @Implementation fun getLastPrimaryResponseBody(): String = server.lastBody
    }
}
