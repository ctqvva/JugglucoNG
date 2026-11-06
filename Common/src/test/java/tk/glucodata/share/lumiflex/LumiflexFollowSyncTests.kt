package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexFollowSyncTests {
    private val settings get() = LumiflexCloudSettings(RuntimeEnvironment.getApplication())
    private val provider = FollowerInfo(dataProviderId = "patient", dataReaderId = "reader", providerUserName = "patient-account")
    private val timestamp = System.currentTimeMillis() - 60_000L
    private fun row(user: String = "patient", glucose: Double = 111.0) = JSONObject()
        .put("userId", user).put("glucose", glucose).put("glucoseIsValid", 1)
        .put("appTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))).put("appTimeZone", "UTC")
    private inner class Transport : LumiflexCloudClient.Transport {
        var beforeHistory: () -> Unit = {}
        var allowed = true
        var historyCode = 200
        var historyRequests = 0
        val pageSizes = mutableListOf<Int>()
        var history = listOf(row(), row("unrelated"), row().put("glucoseIsValid", 0))
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult =
            error("A follower must never POST uploads, binding or invitations")
        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            assertEquals("token", headers["x-token"])
            val body = if (url.contains("findUserAuthorizationList")) {
                assertTrue(url.contains("type=1"))
                if (allowed) """{"code":200,"data":[{"dataProviderId":"patient","dataReaderId":"reader"}]}"""
                else """{"code":200,"data":[]}"""
            } else {
                assertTrue(url.contains("userId=patient")); assertFalse(url.contains("userId=reader"))
                assertTrue(url.contains("pageNum=1"))
                pageSizes += url.substringAfter("pageSize=").toInt()
                historyRequests++
                beforeHistory()
                JSONObject().put("code", historyCode).put("data", org.json.JSONArray(history)).toString()
            }
            return LumiflexCloudClient.HttpResult(200, body)
        }
    }
    @Before fun reset() {
        settings.clear(); settings.startSession("reader-account", "token", "reader")
        settings.enabled = true; settings.selectMode(true, provider)
    }
    private fun sync(transport: Transport, sink: (List<LumiflexFollowReading>) -> Unit) =
        LumiflexFollowSync(LumiflexCloudClient(transport, encrypt = { it }, decrypt = { it }), settings, sink).sync()

    @Test fun authorizedProviderHistoryReachesTheSinkInMgdlAndNeverUploads() {
        val received = mutableListOf<LumiflexFollowReading>()
        assertTrue(sync(Transport()) { received += it })
        assertEquals(1, received.size); assertEquals(111f, received.single().mgdl, 0f)
        assertEquals(timestamp / 1000, received.single().timestampMs / 1000)
        assertEquals(LumiflexCloudSettings.SyncState.SYNCED, settings.syncStatus().state)
        assertTrue(settings.lastSyncMs > 0)
    }
    @Test fun revokedProviderIsNeverReadOrPublished() {
        val transport = Transport().apply { allowed = false }
        assertFalse(sync(transport) { fail("revoked data") })
        assertEquals(0, transport.historyRequests)
    }
    @Test fun currentReceiverUsesSmallOverlappingPollsAndProviderChangeResetsTheWatermark() {
        val transport = Transport()
        assertTrue(sync(transport) {})
        assertTrue(settings.lastReceivedMs > 0)
        assertTrue(sync(transport) {})
        assertEquals(listOf(500, 20), transport.pageSizes)
        settings.selectMode(true, provider.copy(dataProviderId = "other"))
        assertEquals(0, settings.lastReceivedMs)
    }
    @Test fun providerSelectionRequestsOneMainSensorHandoffAndStaleAcknowledgementCannotConsumeIt() {
        assertTrue(settings.selectionPending)
        val first = settings.session()
        settings.selectionApplied(first)
        assertFalse(settings.selectionPending)
        settings.selectMode(true, provider.copy(dataProviderId = "other"))
        settings.selectionApplied(first)
        assertTrue(settings.selectionPending)
        settings.selectionApplied(settings.session())
        assertFalse(settings.selectionPending)
        settings.startSession("reader-account", "new-token", "reader")
        assertFalse(settings.selectionPending)
    }
    @Test fun sessionReplacementDuringReadDiscardsTheReply() {
        val transport = Transport().apply { beforeHistory = { settings.startSession("other", "other-token", "other-id") } }
        assertFalse(sync(transport) { fail("old account data") })
        assertEquals(0, settings.lastSyncMs)
    }
    @Test fun roleOrProviderChangeOrDisableDuringReadDiscardsTheReply() {
        for (change in listOf<() -> Unit>({ settings.selectMode(false) },
            { settings.selectMode(true, provider.copy(dataProviderId = "other")) }, { settings.enabled = false })) {
            reset()
            assertFalse(sync(Transport().apply { beforeHistory = change }) { fail("retired selection") })
        }
    }
    @Test fun expiredSessionStopsReceiving() {
        assertFalse(sync(Transport().apply { historyCode = 800 }) { fail("expired session") })
        assertFalse(settings.isSignedIn()); assertTrue(settings.sessionExpired)
    }
    @Test fun uploadModeNeverReadsProviderHistory() {
        settings.selectMode(false)
        val transport = Transport()
        assertFalse(sync(transport) { fail("not in follow mode") })
        assertEquals(0, transport.historyRequests)
    }
    @Test fun existingReaderWithoutLocalSensorMigratesButExplicitUploadChoiceIsPreserved() {
        settings.clear(); settings.startSession("reader-account", "token", "reader")
        LumiflexCloudRole.discover(settings, listOf(provider), false)
        assertTrue(settings.followMode); assertEquals("patient", settings.providerId)
        settings.selectMode(false)
        LumiflexCloudRole.discover(settings, listOf(provider), false)
        assertFalse(settings.followMode)
    }
    @Test fun patientWithLocalSensorKeepsUploadingAndMultipleProvidersRequireSelection() {
        settings.clear(); settings.startSession("reader-account", "token", "reader")
        LumiflexCloudRole.discover(settings, listOf(provider), true)
        assertFalse(settings.followMode)
        settings.clear(); settings.startSession("reader-account", "token", "reader")
        LumiflexCloudRole.discover(settings, listOf(provider, provider.copy(dataProviderId = "other")), false)
        assertTrue(settings.followMode); assertTrue(settings.providerId.isBlank())
    }
    @Test fun timestampsUseTheRecordZoneRatherThanThePhonesZoneAndInvalidRowsAreRejected() {
        val json = row().put("appTime", "2026-11-06 16:41:13").put("appTimeZone", "Asia/Yekaterinburg")
        val parsed = LumiflexFollowReading.fromJson(json, "patient")!!
        val utc = row().put("appTime", "2026-11-06 11:41:13")
        assertEquals(LumiflexFollowReading.fromJson(utc, "patient")!!.timestampMs, parsed.timestampMs)
        assertNull(LumiflexFollowReading.fromJson(json.put("appTimeZone", "invalid"), "patient"))
        assertNull(LumiflexFollowReading.fromJson(row().put("glucoseIsValid", 0), "patient"))
        assertNull(LumiflexFollowReading.fromJson(row(glucose = -1.0), "patient"))
        assertNull(LumiflexFollowReading.fromJson(row("other"), "patient"))
    }
}
