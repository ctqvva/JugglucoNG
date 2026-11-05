package tk.glucodata.share.lumiflex

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudSettingsQueueTests {

    @Test
    fun settingsRoundTripAndStatics() {
        val context = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
        context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LumiflexCloudSettings(context)
        settings.account = "a@b.c"
        settings.token = "tok"
        settings.userId = "u1"
        settings.lastSyncMs = 42L
        val again = LumiflexCloudSettings(context)
        assertEquals("a@b.c", again.account)
        assertEquals("tok", again.token)
        assertEquals("u1", again.userId)
        assertEquals(42L, again.lastSyncMs)
        assertTrue(LumiflexCloudSettings.isSetUp(context))
        assertEquals(false, LumiflexCloudSettings.isEnabled(context))
        again.clear()
        assertEquals("", LumiflexCloudSettings(context).account)
    }

    @Test
    fun queueDedupesAndPersistsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        val record = CgmRecord("SN1", "SN1", 0, 10, 1_700_000_000_000L, 100, 0)
        queue.enqueue(record)
        queue.enqueue(record)
        assertEquals(1, queue.size())
        assertEquals(1, LumiflexCloudQueue(context).size())
        queue.remove(listOf(record))
        assertEquals(0, LumiflexCloudQueue(context).size())
    }

    @Test
    fun enqueueingForANewAccountDropsThePreviousAccountRecords() {
        val context = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, 1_700_000_000_000L, 100, 0), owner = "a0")
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 2, 1_700_000_120_000L, 101, 0), owner = "a1")
        assertEquals(1, queue.size())
        assertEquals("a1", queue.owner())
    }

    @Test
    fun logoutAndReplacementFenceLateLoginValidationAndSyncReplies() {
        val settings = LumiflexCloudSettings(RuntimeEnvironment.getApplication())
        settings.clear()
        val beforeLogin = settings.session()
        settings.endSession()
        assertFalse(settings.startSessionIfCurrent(beforeLogin, "a0", "old", "u0"))
        settings.startSession("a1", "same-token", "u1")
        val oldSession = settings.session()
        settings.startSession("a1", "same-token", "u1")
        assertFalse(settings.invalidateSession(oldSession))
        settings.recordSync(oldSession, LumiflexCloudSettings.SyncState.ERROR, 42, "stale", uploaded = 2)
        assertTrue(settings.isSignedIn())
        assertEquals(LumiflexCloudSettings.SyncState.IDLE, settings.syncStatus().state)
        assertEquals(0, settings.syncStatus().pending)
        assertEquals(0L, settings.lastSyncMs)
    }

    @Test
    fun expiredSessionIsDifferentFromExplicitSignOutAndKeepsPendingReadings() {
        val settings = LumiflexCloudSettings(RuntimeEnvironment.getApplication())
        settings.clear()
        settings.startSession("a1", "token", "u1")
        settings.passwordSetupRequired = true
        val session = settings.session()
        settings.recordSync(session, LumiflexCloudSettings.SyncState.QUEUED, 3)
        assertTrue(settings.invalidateSession(session))
        assertTrue(settings.sessionExpired)
        assertFalse(settings.isSignedIn())
        assertEquals(3, settings.syncStatus().pending)
        assertEquals(LumiflexCloudSettings.SyncState.SIGN_IN_REQUIRED, settings.syncStatus().state)
        settings.endSession()
        assertFalse(settings.sessionExpired)
    }

    @Test
    fun passwordSetupStepSurvivesReopeningAfterAccountCreation() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.clear()
        settings.startSession("79991234567", "sms-token", "new-user")
        settings.passwordSetupRequired = true
        val reopened = LumiflexCloudSettings(context)
        assertTrue(reopened.passwordSetupRequired)
        assertTrue(reopened.isSignedIn())
        assertEquals("sms-token", reopened.token)
    }
}

class LumiflexCloudTestApplication : Applic() {
    override fun onCreate() {}
}
