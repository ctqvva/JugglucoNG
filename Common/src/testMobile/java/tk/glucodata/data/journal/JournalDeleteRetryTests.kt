package tk.glucodata.data.journal

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetworkCapabilities
import tk.glucodata.Applic
import tk.glucodata.ConnectivityRecoveryDetector
import tk.glucodata.Natives
import tk.glucodata.NetworkHandoverPolicy
import tk.glucodata.NightPost
import tk.glucodata.data.HistoryDatabase
import java.net.InetSocketAddress

/**
 * Regression tests for Room-backed Nightscout treatment deletion retries stacked on PR #1023:
 * - >20 transient failures preserve tombstones in Room and recover via capped exponential backoff.
 * - Endpoint-wide outages probe once per backoff interval across all pending deletions.
 * - Document-specific failures do not block other pending deletions.
 * - Server-side permission changes recover automatically via slow backoff + v3 JWT drop, and
 *   client-side credential/config changes or manual retries recover immediately.
 * - Pending deletions and abandoned uploads never target a different Nightscout server.
 * - HTTP 404/410 absence is verified before clearing tombstones; route-missing 404s preserve them.
 * - Retry state, diagnostics, and server bindings survive process restarts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = JournalDeleteRetryTests.TestApplication::class,
    shadows = [DeleteRetryNativesShadow::class, DeleteRetryNightPostShadow::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
class JournalDeleteRetryTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private lateinit var dao: JournalDao
    private var previousApp: Applic? = null
    private var nowMillis = 1_700_000_000_000L
    private val scheduledDelays = mutableListOf<Long>()

    @Before
    fun setUp() {
        previousApp = Applic.app
        val app = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app = app
        app.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        database = Room.inMemoryDatabaseBuilder(app, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setHistoryDatabaseSingleton(database)
        dao = database.journalDao()

        DeleteRetryNativesShadow.reset()
        DeleteRetryNightPostShadow.reset()
        scheduledDelays.clear()
        nowMillis = 1_700_000_000_000L
        JournalTreatmentUploader.clockMillis = { nowMillis }
        JournalTreatmentUploader.deleteRetryScheduler = { scheduledDelays += it }
        JournalTreatmentUploader.resetRuntimeStateForTesting()
    }

    @After
    fun tearDown() {
        JournalTreatmentUploader.resetRuntimeStateForTesting()
        JournalTreatmentUploader.clockMillis = { System.currentTimeMillis() }
        JournalTreatmentUploader.deleteRetryScheduler = null
        setHistoryDatabaseSingleton(null)
        if (::database.isInitialized) database.close()
        Applic.app = previousApp
    }

    @Test
    fun backoffIsCappedExponentialAndOverflowSafe() {
        assertEquals(60_000L, NightscoutDeleteRetry.backoffMillis(1, DeleteFailureKind.TRANSIENT))
        assertEquals(120_000L, NightscoutDeleteRetry.backoffMillis(2, DeleteFailureKind.TRANSIENT))
        assertEquals(240_000L, NightscoutDeleteRetry.backoffMillis(3, DeleteFailureKind.TRANSIENT))
        assertEquals(
            NightscoutDeleteRetry.TRANSIENT_BACKOFF_MAX_MILLIS,
            NightscoutDeleteRetry.backoffMillis(25, DeleteFailureKind.TRANSIENT),
        )
        assertEquals(
            NightscoutDeleteRetry.TRANSIENT_BACKOFF_MAX_MILLIS,
            NightscoutDeleteRetry.backoffMillis(Int.MAX_VALUE, DeleteFailureKind.TRANSIENT),
        )
        assertEquals(30 * 60_000L, NightscoutDeleteRetry.backoffMillis(1, DeleteFailureKind.AUTH))
        assertEquals(60 * 60_000L, NightscoutDeleteRetry.backoffMillis(2, DeleteFailureKind.AUTH))
        assertEquals(
            NightscoutDeleteRetry.SLOW_BACKOFF_MAX_MILLIS,
            NightscoutDeleteRetry.backoffMillis(20, DeleteFailureKind.AUTH),
        )
    }

    @Test
    fun moreThanTwentyTransientFailuresPreserveTombstoneAndRecoverAfterOutage() = runBlocking {
        val remoteId = "jng-j-2a-18bcfe56800"
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 42L, nsRemoteId = remoteId, deletedAt = nowMillis - 5_000L)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 503 to """{"message":"Service Unavailable"}""" }

        for (attempt in 1..25) {
            val expectedDelay = NightscoutDeleteRetry.backoffMillis(attempt, DeleteFailureKind.TRANSIENT)
            val before = DeleteRetryNightPostShadow.deleteCalls.size
            assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
            assertEquals("attempt $attempt must execute when due", before + 1, DeleteRetryNightPostShadow.deleteCalls.size)

            // Inside backoff window: no duplicate DELETE.
            nowMillis += expectedDelay / 2
            assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
            assertEquals("must not retry during backoff", before + 1, DeleteRetryNightPostShadow.deleteCalls.size)

            val tomb = dao.getPendingNightscoutDelete(remoteId)!!
            assertEquals(attempt, tomb.attempts)
            assertEquals(DeleteFailureKind.TRANSIENT.storageValue, tomb.failureKind)
            nowMillis = tomb.nextRetryAt
        }

        // Server recovers after 25 failures; tombstone succeeds and clears cleanly.
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
        assertFalse(JournalSyncStatus.state().isFailing)
        assertEquals(nowMillis, JournalSyncStatus.state().lastSuccessAt)
    }

    @Test
    fun endpointWideOutageProbesOncePerBackoffWindowAcrossAllPendingDeletes() = runBlocking {
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 1L, nsRemoteId = "jng-j-1-a", deletedAt = nowMillis - 3_000L)
        )
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 2L, nsRemoteId = "jng-j-2-b", deletedAt = nowMillis - 2_000L)
        )
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 3L, nsRemoteId = "jng-j-3-c", deletedAt = nowMillis - 1_000L)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 503 to """{"message":"Down"}""" }

        // Pass 1: probes ONLY 1 tombstone, not all 3.
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(1, DeleteRetryNightPostShadow.deleteCalls.size)
        assertEquals(3, JournalSyncStatus.state().pendingDeleteCount)

        // Even if a 4th deletion is queued during the outage, the endpoint hold suppresses all retries.
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 4L, nsRemoteId = "jng-j-4-d", deletedAt = nowMillis + 5_000L)
        )
        nowMillis += 10_000L
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals("endpoint hold must prevent probing other tombstones", 1, DeleteRetryNightPostShadow.deleteCalls.size)

        // When 1st backoff expires and server is still down: exactly 1 probe, backoff doubles to 2m.
        nowMillis = JournalSyncStatus.state().pendingDeleteNextRetryAt
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(2, DeleteRetryNightPostShadow.deleteCalls.size)
        val diag2 = JournalTreatmentUploader.unresolvedDeleteDiagnostics()
        assertEquals(nowMillis + 120_000L, diag2.nextRetryAt)

        // Once server recovers when backoff expires, the single pass drains all 4 tombstones.
        nowMillis = diag2.nextRetryAt
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(6, DeleteRetryNightPostShadow.deleteCalls.size)
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
    }

    @Test
    fun documentSpecificFailureDoesNotBlockOtherPendingDeletes() = runBlocking {
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 10L, nsRemoteId = "jng-j-bad-1", deletedAt = nowMillis - 2_000L)
        )
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 11L, nsRemoteId = "jng-j-good-2", deletedAt = nowMillis - 1_000L)
        )
        DeleteRetryNightPostShadow.deleteHandler = { url, _ ->
            if (url.contains("jng-j-bad-1")) 409 to "Conflict" else 200 to "{}"
        }

        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(2, DeleteRetryNightPostShadow.deleteCalls.size)
        assertEquals(listOf("jng-j-bad-1"), dao.getPendingNightscoutDeletes().map { it.nsRemoteId })
    }

    @Test
    fun serverSidePermissionFixRecoversAutomaticallyWithFreshJwtAndImmediateClientReset() = runBlocking {
        val remoteId = "jng-j-auth-1"
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 20L, nsRemoteId = remoteId, deletedAt = nowMillis)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ ->
            403 to """{"status":403,"message":"Missing permission api:treatments:delete"}"""
        }

        // Initial refusal records slow AUTH backoff and exposes auth-blocked diagnostics.
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(1, DeleteRetryNightPostShadow.deleteCalls.size)
        assertTrue(JournalSyncStatus.state().pendingDeleteAuthBlocked)
        assertEquals("Missing permission api:treatments:delete", JournalSyncStatus.state().pendingDeleteLastError)

        // Connectivity recovery must NOT release AUTH backoff.
        JournalTreatmentUploader.onConnectivityRecovered()
        nowMillis += 60_000L
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(1, DeleteRetryNightPostShadow.deleteCalls.size)

        // Permissions are fixed server-side without changing phone settings: once slow backoff
        // expires, uploader drops cached JWT and succeeds automatically!
        nowMillis = dao.getPendingNightscoutDelete(remoteId)!!.nextRetryAt
        val dropsBefore = DeleteRetryNightPostShadow.tokenDrops
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals("v3 AUTH probe must drop cached JWT to pick up new server permissions", dropsBefore + 1, DeleteRetryNightPostShadow.tokenDrops)
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
        assertFalse(JournalSyncStatus.state().isFailing)

        // Changing client secret/URL (including base-path case) resumes immediately without waiting.
        DeleteRetryNativesShadow.url = "https://ns.example.com/nightscout"
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 21L, nsRemoteId = "jng-j-cfg-2", deletedAt = nowMillis)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 404 to "Cannot DELETE /nightscout/api/v3/treatments/jng-j-cfg-2" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertTrue(JournalSyncStatus.state().pendingDeleteConfigBlocked)

        // Fix base-path casing and simulate process restart: config-key change auto-releases backoff.
        DeleteRetryNativesShadow.url = "https://ns.example.com/Nightscout"
        JournalTreatmentUploader.resetRuntimeStateForTesting()
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 22L, nsRemoteId = "jng-j-cfg-3", deletedAt = nowMillis)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(listOf("jng-j-cfg-2"), dao.getPendingNightscoutDeletes().map { it.nsRemoteId })
    }

    @Test
    fun pendingDeletesAndAbandonedUploadsNeverTargetDifferentNightscoutServer() = runBlocking {
        DeleteRetryNativesShadow.url = "https://server-a.example.com"
        val entryId = dao.upsertEntry(
            JournalEntryEntity(
                timestamp = nowMillis - 60_000L,
                sensorSerial = null,
                entryType = JournalEntryType.CARBS.storageValue,
                title = "Meal",
                note = null,
                amount = 25f,
                glucoseValueMgDl = null,
                durationMinutes = null,
                intensity = null,
                insulinPresetId = null,
                source = JournalEntrySource.MANUAL.storageValue,
                sourceRecordId = null,
                createdAt = nowMillis - 60_000L,
                updatedAt = nowMillis - 60_000L,
                nsUploadedAt = nowMillis - 50_000L,
                nsRemoteId = "jng-j-server-a-doc",
            )
        )
        // Delete entry while pointed at server-a (records targetServer = server-a).
        database.withTransaction {
            dao.recordEntryDeletion(dao.getEntryById(entryId)!!, nowMillis)
            dao.deleteEntryById(entryId)
        }
        // Also record an abandoned in-flight upload on server-a whose entry was deleted.
        dao.recordNightscoutUpload(
            JournalNightscoutUploadEntity(
                remoteId = "jng-j-server-a-abandoned",
                entryId = 999L,
                entryCreatedAt = nowMillis - 100_000L,
                startedAt = nowMillis - 10_000L,
                targetServer = "https://server-a.example.com",
            )
        )

        // Switch phone settings to server-b before any delete runs.
        DeleteRetryNativesShadow.url = "https://server-b.example.com"
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals("server-a tombstones must never be sent to server-b", 0, DeleteRetryNightPostShadow.deleteCalls.size)
        assertEquals(2, dao.getPendingNightscoutDeletes().size)
        assertEquals(0, JournalSyncStatus.state().pendingDeleteCount)

        // Switching back to server-a executes both server-a deletions cleanly.
        DeleteRetryNativesShadow.url = "https://server-a.example.com"
        DeleteRetryNightPostShadow.deleteHandler = { url, _ ->
            assertTrue(url.startsWith("https://server-a.example.com/"))
            200 to "{}"
        }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(2, DeleteRetryNightPostShadow.deleteCalls.size)
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
    }

    @Test
    fun http404And410VerificationDistinguishesConfirmedAbsenceFromRouteErrors() = runBlocking {
        // Unambiguous v3 document absence clears without advancing lastSuccessAt.
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 30L, nsRemoteId = "jng-j-404-ok", deletedAt = nowMillis)
        )
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 31L, nsRemoteId = "jng-j-410-ok", deletedAt = nowMillis + 1)
        )
        DeleteRetryNightPostShadow.deleteHandler = { url, _ ->
            if (url.contains("404")) 404 to """{"status":404}""" else 410 to """{"status":410}"""
        }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
        assertEquals(0L, JournalSyncStatus.state().lastSuccessAt)

        // Route-missing v3 JSON ("Bad operation or collection") and proxy 404 where GET still
        // finds the document must BOTH preserve the tombstone!
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v3/treatments/jng-j-proxy-404") { ex ->
            val resp = """{"status":200,"result":{"identifier":"jng-j-proxy-404"}}""".toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            DeleteRetryNativesShadow.url = "http://127.0.0.1:${server.address.port}"
            dao.enqueuePendingNightscoutDelete(
                JournalPendingDeleteEntity(entryId = 32L, nsRemoteId = "jng-j-route-404", deletedAt = nowMillis)
            )
            DeleteRetryNightPostShadow.deleteHandler = { _, _ ->
                404 to """{"status":404,"message":"Bad operation or collection"}"""
            }
            assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
            assertEquals(1, dao.getPendingNightscoutDeletes().size)
            assertTrue(JournalSyncStatus.state().pendingDeleteConfigBlocked)
            dao.clearPendingNightscoutDelete("jng-j-route-404")

            dao.enqueuePendingNightscoutDelete(
                JournalPendingDeleteEntity(entryId = 33L, nsRemoteId = "jng-j-proxy-404", deletedAt = nowMillis)
            )
            DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 404 to """{"message":"Not Found"}""" }
            assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
            assertEquals("generic proxy 404 while GET sees doc must keep tombstone", 1, dao.getPendingNightscoutDeletes().size)
            assertTrue(JournalSyncStatus.state().pendingDeleteConfigBlocked)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun retryMetadataAndDiagnosticsSurviveProcessRestart() = runBlocking {
        val remoteId = "jng-j-restart-1"
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 40L, nsRemoteId = remoteId, deletedAt = nowMillis - 10_000L)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> NightPost.ERROR_NO_RESPONSE to "" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        val expectedRetryAt = dao.getPendingNightscoutDelete(remoteId)!!.nextRetryAt
        assertTrue(expectedRetryAt > nowMillis)

        // Simulate process restart: clear all in-memory caches while keeping Room & prefs.
        JournalTreatmentUploader.resetRuntimeStateForTesting()
        assertEquals(1, JournalSyncStatus.state().pendingDeleteCount)
        assertEquals(expectedRetryAt, JournalSyncStatus.state().pendingDeleteNextRetryAt)

        // Still inside persisted backoff after restart: must NOT retry early.
        nowMillis += 15_000L
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(1, DeleteRetryNightPostShadow.deleteCalls.size)

        // Connectivity recovery releases transient backoff immediately.
        JournalTreatmentUploader.onConnectivityRecovered()
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(2, DeleteRetryNightPostShadow.deleteCalls.size)
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
    }

    @Test
    fun midFlightConnectivityRecoveryPreventsStaleTransientBackoffAndCellularStartupPreservesBackoff() = runBlocking {
        dao.enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(entryId = 50L, nsRemoteId = "jng-j-race-50", deletedAt = nowMillis)
        )
        DeleteRetryNightPostShadow.deleteHandler = { _, _ ->
            JournalTreatmentUploader.onConnectivityRecovered()
            NightPost.ERROR_NO_RESPONSE to ""
        }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(0L, dao.getPendingNightscoutDelete("jng-j-race-50")!!.nextRetryAt)

        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())

        // Verify cellular active network seeds ConnectivityRecoveryDetector as online.
        val app = ApplicationProvider.getApplicationContext<TestApplication>()
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val activeNetwork = cm.activeNetwork!!
        val cellularCaps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(cellularCaps).apply {
            addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, cellularCaps)
        assertTrue(Applic.hasActiveInternetNetwork(cm))
        val detector = ConnectivityRecoveryDetector(startedOnline = Applic.hasActiveInternetNetwork(cm))
        assertFalse(detector.shouldNotifyRecovery(NetworkHandoverPolicy.Action.PRESENT))
    }

    @Test
    fun restoringTimestampDuringBackoffPatchesExistingDocumentAndCancelsPendingDelete() = runBlocking {
        val timeA = nowMillis - 600_000L
        val timeB = nowMillis - 300_000L
        val entryId = dao.upsertEntry(
            JournalEntryEntity(
                timestamp = timeA,
                sensorSerial = null,
                entryType = JournalEntryType.CARBS.storageValue,
                title = "Lunch",
                note = "initial",
                amount = 30f,
                glucoseValueMgDl = null,
                durationMinutes = null,
                intensity = null,
                insulinPresetId = null,
                source = JournalEntrySource.MANUAL.storageValue,
                sourceRecordId = null,
                createdAt = timeA,
                updatedAt = timeA,
            )
        )
        val idA = JournalTreatmentUploader.datedIdentifier(entryId, timeA)
        val idB = JournalTreatmentUploader.datedIdentifier(entryId, timeB)

        DeleteRetryNightPostShadow.uploadHandler = { _, _, _ -> 200 to "{}" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(idA, dao.getEntryById(entryId)?.nsRemoteId)

        nowMillis += 1_000L
        dao.upsertEntry(dao.getEntryById(entryId)!!.copy(timestamp = timeB, updatedAt = nowMillis))
        DeleteRetryNightPostShadow.deleteHandler = { _, _ -> 503 to """{"message":"Down"}""" }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(listOf(idA), dao.getPendingNightscoutDeletes().map { it.nsRemoteId })

        // Restore time A with updated amount before A's backoff ends.
        nowMillis += 5_000L
        dao.upsertEntry(dao.getEntryById(entryId)!!.copy(timestamp = timeA, amount = 45f, updatedAt = nowMillis))
        val patched = mutableListOf<String>()
        DeleteRetryNightPostShadow.uploadHandler = { url, body, _ ->
            if (url.endsWith("/api/v3/treatments/$idA")) {
                patched += String(body, Charsets.UTF_8)
                200 to "{}"
            } else {
                409 to """{"status":409,"message":"Already exists"}"""
            }
        }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertEquals(1, patched.size)
        assertTrue(patched.single().contains("45"))
        assertEquals(idA, dao.getEntryById(entryId)?.nsRemoteId)
        // A's tombstone was cancelled by ledger.accept(), while B is queued (skipped due to endpoint hold).
        assertEquals(listOf(idB), dao.getPendingNightscoutDeletes().map { it.nsRemoteId })

        // After backoff expires, only B is deleted; restored A is never deleted.
        nowMillis += 2 * 60 * 60_000L
        DeleteRetryNightPostShadow.deleteHandler = { url, _ ->
            assertTrue("must only delete B, never restored A: $url", url.contains(idB))
            200 to "{}"
        }
        assertTrue(JournalTreatmentUploader.uploadAll(useV3 = true))
        assertTrue(dao.getPendingNightscoutDeletes().isEmpty())
        assertEquals(idA, dao.getEntryById(entryId)?.nsRemoteId)
    }

    private fun setHistoryDatabaseSingleton(instance: HistoryDatabase?) {
        val field = HistoryDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, instance)
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class DeleteRetryNativesShadow {
    companion object {
        var url = "https://ns.example.com"
        var secret = "initial-secret"
        var useV3 = true
        var wakes = 0

        fun reset() {
            url = "https://ns.example.com"
            secret = "initial-secret"
            useV3 = true
            wakes = 0
        }

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getpostTreatments(): Boolean = true
        @JvmStatic @Implementation fun getuseuploader(): Boolean = true
        @JvmStatic @Implementation fun getnightscoutV3(): Boolean = useV3
        @JvmStatic @Implementation fun getnightuploadurl(): String = url
        @JvmStatic @Implementation fun getnightuploadsecret(): String = secret
        @JvmStatic @Implementation fun waketreatments() { wakes++ }
    }
}

@Implements(value = NightPost::class, callThroughByDefault = false)
class DeleteRetryNightPostShadow {
    companion object {
        val deleteCalls = mutableListOf<Pair<String, String?>>()
        var lastBody = ""
        var tokenDrops = 0
        var deleteHandler: (String, String?) -> Pair<Int, String> = { _, _ -> 200 to "{}" }
        var uploadHandler: (String, ByteArray, String?) -> Pair<Int, String> = { _, _, _ -> 200 to "{}" }

        fun reset() {
            deleteCalls.clear()
            lastBody = ""
            tokenDrops = 0
            deleteHandler = { _, _ -> 200 to "{}" }
            uploadHandler = { _, _, _ -> 200 to "{}" }
        }

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun dropToken() { tokenDrops++ }

        @JvmStatic @Implementation
        fun deleteUrlCode(urlstring: String, secret: String?): Int {
            deleteCalls += urlstring to secret
            val (code, body) = deleteHandler(urlstring, secret)
            lastBody = body
            return code
        }

        @JvmStatic @Implementation
        fun upload(httpurl: String, postdata: ByteArray, secret: String?, put: Boolean): Int {
            val (code, body) = uploadHandler(httpurl, postdata, secret)
            lastBody = body
            return code
        }

        @JvmStatic @Implementation
        fun uploadPatch(httpurl: String, postdata: ByteArray, secret: String?): Int {
            val (code, body) = uploadHandler(httpurl, postdata, secret)
            lastBody = body
            return code
        }

        @JvmStatic @Implementation fun getLastPrimaryResponseBody(): String = lastBody
        @JvmStatic @Implementation fun getV3AuthorizationHeaderForUrl(requestUrl: String?): String = "Bearer test-jwt"
    }
}
