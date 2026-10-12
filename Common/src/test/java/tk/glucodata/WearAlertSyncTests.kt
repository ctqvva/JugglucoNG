package tk.glucodata

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WearAlertSyncTests.TestApplication::class,
    shadows = [WearAlertSyncTests.AlarmSink::class, WearAlertSyncTests.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WearAlertSyncTests {
    class TestApplication : Applic() { override fun onCreate() {} }

    // The real action handler, tracker, snooze preferences and message receiver run.
    // Only native audio/notification delivery is replaced by a recording sink.
    @Implements(Notify::class)
    class AlarmSink {
        companion object {
            val calls = mutableListOf<String>()
            var currentKind = AlertType.HIGH.id
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun cancelRetrySession(kind: Int, reason: String) { calls += "retry:$kind" }
            @JvmStatic @Implementation fun cancelQueuedAlarmActivityLaunch(kind: Int, custom: String?, reason: String) {}
            @JvmStatic @Implementation fun stopalarm() { calls += "stop" }
            @JvmStatic @Implementation fun stopalarmnotsend(send: Boolean) { calls += "remote-stop:$send" }
            @JvmStatic @Implementation fun cancelAlertNotification() { calls += "notification" }
            @JvmStatic @Implementation fun isPresentedStandardAlert(kind: Int) = kind == currentKind
            @JvmStatic @Implementation fun isCurrentStandardAlert(kind: Int) = kind == currentKind
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    @Before fun setup() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app.getSharedPreferences("wear_alert_ack", 0).edit().clear().commit()
        Applic.app.getSharedPreferences("tk.glucodata.snooze", 0).edit().clear().commit()
        AlertType.entries.forEach(AlertStateTracker::resetState)
        AlarmSink.calls.clear()
        AlarmSink.currentKind = AlertType.HIGH.id
        Log.doLog = false
    }

    private fun receive(action: WearAlertAcknowledgement) {
        WearAlertSync.onMessage(action.encode())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun remoteDismissCancelsTheMatchingAlarmAndSurvivesEpisodeReset() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        assertTrue(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertEquals(listOf("retry:1", "remote-stop:false", "notification"), AlarmSink.calls)
        AlertStateTracker.resetState(AlertType.HIGH)
        assertTrue(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertFalse(WearAlertSync.isSuppressed(AlertType.VERY_HIGH.id))
        assertFalse(WearAlertSync.isSuppressed(AlertType.LOW.id))
    }

    @Test fun remoteSnoozeUsesAbsoluteExpiryAndDuplicatesHaveNoSideEffects() {
        val now = System.currentTimeMillis()
        val action = WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 60_000, now + 240_000)
        receive(action)
        assertEquals(action.untilMs, SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis)
        val calls = AlarmSink.calls.toList()
        receive(action)
        receive(action.copy(actedAtMs = action.actedAtMs - 1000, untilMs = now + 600_000))
        assertEquals(calls, AlarmSink.calls)
        assertEquals(action.untilMs, SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis)
    }

    @Test fun expiredAcknowledgementDoesNotCancelANewAlarm() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 600_000, now - 300_000))
        assertFalse(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertTrue(AlarmSink.calls.isEmpty())
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 700_000, now + 300_000))
        assertTrue(AlarmSink.calls.isEmpty())
    }

    @Test fun acknowledgementOfHighDoesNotStopTheActiveLowAlarm() {
        AlarmSink.currentKind = AlertType.LOW.id
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        assertEquals(listOf("retry:1"), AlarmSink.calls)
        assertFalse(AlertStateTracker.isDismissed(AlertType.LOW))
    }

    @Test fun localSnoozeCancelsRetryBeforeStoppingAndRecordsTheQuietDeadline() {
        AlertActions.handle(AlertType.HIGH.id, null, 15)
        assertTrue(AlarmSink.calls.indexOf("retry:1") < AlarmSink.calls.indexOf("stop"))
        assertTrue(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertTrue(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    @Test fun localDismissRestartsCooldownAtTheInteraction() {
        AlertStateTracker.onAlertTriggered(AlertType.HIGH)
        val cooldowns = AlertStateTracker::class.java.getDeclaredField("cooldownUntilTime")
            .apply { isAccessible = true }.get(AlertStateTracker) as MutableMap<AlertType, Long>
        cooldowns[AlertType.HIGH] = System.currentTimeMillis() - 1
        AlertActions.handle(AlertType.HIGH.id, null)
        assertTrue(AlertStateTracker.isDismissed(AlertType.HIGH))
        AlertStateTracker.resetState(AlertType.HIGH)
        assertTrue(AlertStateTracker.isWaitingForRearmCooldown(AlertType.HIGH))
        assertTrue(AlarmSink.calls.indexOf("retry:1") < AlarmSink.calls.indexOf("stop"))
    }

    @Test fun localInteractionSupersedesAReceivedActionDespiteSmallClockSkew() {
        val now = System.currentTimeMillis()
        val remote = WearAlertAcknowledgement(AlertType.HIGH.id, true, now + 10_000, now + 310_000)
        receive(remote)
        AlertActions.handle(AlertType.HIGH.id, null, 15)
        val recordedAt = Applic.app.getSharedPreferences("wear_alert_ack", 0).getLong("at_1", 0)
        assertTrue(recordedAt > remote.actedAtMs)
    }

    @Test fun manualTestAcknowledgementNeverSnoozesOrSyncsTheProductionAlert() {
        val config = tk.glucodata.alerts.AlertConfig(type = AlertType.HIGH)
        AlertStateTracker.allowNextTriggerForTest(AlertType.HIGH)
        assertTrue(AlertStateTracker.shouldTrigger(AlertType.HIGH, config))
        AlertActions.handle(AlertType.HIGH.id, null, 15)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertFalse(AlarmSink.calls.contains("retry:1"))
    }

    @Test fun clearingASnoozeAllowsRearmingAndADuplicateCannotRestoreIt() {
        val now = System.currentTimeMillis()
        val action = WearAlertAcknowledgement(AlertType.HIGH.id, true, now, now + 300_000)
        receive(action)
        SnoozeManager.clearSnooze(AlertType.HIGH)
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        receive(action)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
    }
}
