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
    class TestApplication : Applic() {
        val launchedAlarms = mutableListOf<android.content.Intent>()
        override fun onCreate() {}
        override fun startActivity(intent: android.content.Intent) { launchedAlarms += intent }
    }

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
            @JvmStatic @Implementation fun resolveAlertKind(fallback: Int) = fallback
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    @Before fun setup() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app.getSharedPreferences("wear_alert_ack", 0).edit().clear().commit()
        Applic.app.getSharedPreferences("tk.glucodata.snooze", 0).edit().clear().commit()
        AlertType.entries.forEach {
            AlertStateTracker.resetState(it)
            AlertStateTracker.consumeManualTestAction(it)
        }
        // resetState intentionally retains the production cooldown; tests start
        // with no prior interactions, so clear this state between test cases.
        (AlertStateTracker::class.java.getDeclaredField("cooldownUntilTime")
            .apply { isAccessible = true }.get(AlertStateTracker) as MutableMap<*, *>).clear()
        AlarmSink.calls.clear()
        AlarmSink.currentKind = AlertType.HIGH.id
        Log.doLog = false
        tk.glucodata.ui.AlarmActivityAccess.register { android.content.Intent("test.alarm") }
    }

    private fun receive(action: WearAlertAcknowledgement) {
        WearAlertSync.onMessage(action.encode())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun remoteDismissCancelsTheMatchingAlarmAndSurvivesEpisodeReset() {
        val now = System.currentTimeMillis()
        AlertStateTracker.onAlertTriggered(AlertType.HIGH)
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

    @Test fun remoteDismissBeforeAnyLocalEpisodeDoesNotLatchTheNextHigh() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        assertFalse(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertFalse(AlertStateTracker.isEpisodeActive(AlertType.HIGH))
        // The alert code uses the host wall clock. Expire only the persisted deadline;
        // leave episode state untouched, as normal readings outside HIGH do here.
        Applic.app.getSharedPreferences("wear_alert_ack", 0).edit().putLong("until_1", now - 1).commit()
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertTrue(AlertStateTracker.shouldTrigger(AlertType.HIGH,
            tk.glucodata.alerts.AlertConfig(type = AlertType.HIGH, enabled = true)))
    }

    @Test fun expiredNewerDismissReplacesTheOldSharedSnoozeWithoutStoppingAFreshAlarm() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 600_000, now + 3_000_000))
        AlarmSink.calls.clear()
        AlertStateTracker.onAlertTriggered(AlertType.HIGH)
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 300_000, now - 1))
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertFalse(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertTrue(AlertStateTracker.isEpisodeActive(AlertType.HIGH))
        assertTrue(AlarmSink.calls.isEmpty())
    }

    @Test fun expiredRecordDoesNotClearANewerPreemptiveSnooze() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 600_000, now + 3_000_000))
        SnoozeManager.snooze(AlertType.HIGH, 120, preemptive = true)
        val newerUntil = SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 300_000, now - 1))
        assertEquals(newerUntil, SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis)
    }

    @Test fun expiredSnoozeReconciliationDoesNotInventANewAcknowledgement() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 600_000, now + 3_000_000))
        AlarmSink.calls.clear()
        val expired = WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 300_000, now - 1)
        receive(expired)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertEquals(expired, WearAlertSync.snapshot().first { it.kind == AlertType.HIGH.id })
        assertFalse(WearAlertSync.shouldCloseAlarm(AlertType.HIGH.id, now - 10_000))
        assertTrue(AlarmSink.calls.isEmpty())
    }

    @Test fun dismissingAfterASnoozePublishesOnlyTheNewDismissDeadline() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 1000, now + 300_000))
        AlertStateTracker.onAlertTriggered(AlertType.HIGH)
        AlertActions.handle(AlertType.HIGH.id, null)
        val dismiss = WearAlertSync.snapshot().first { it.kind == AlertType.HIGH.id }
        assertFalse(dismiss.snoozed)
        assertTrue(dismiss.untilMs > dismiss.actedAtMs)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    @Test fun anOldAlarmIntentRemainsAcknowledgedAfterItsQuietDeadlineExpires() {
        val now = System.currentTimeMillis()
        val intent = android.content.Intent().putExtra("EXTRA_ALERT_TYPE_ID", AlertType.HIGH.id)
            .putExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, now - 600_000)
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 300_000, now - 1))
        assertTrue(tk.glucodata.ui.AlarmActivityAccess.isAcknowledged(intent))
        intent.putExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, now)
        assertFalse(tk.glucodata.ui.AlarmActivityAccess.isAcknowledged(intent))
    }

    @Test fun returningToTheStoppedAlarmActivityClosesAnAcknowledgedScreen() {
        val now = System.currentTimeMillis()
        Notify.unitlabel = "mg/dL"
        val keyguard = Applic.app.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        shadowOf(keyguard).setKeyguardLocked(true)
        val intent = android.content.Intent().putExtra("EXTRA_ALERT_TYPE_ID", AlertType.HIGH.id)
            .putExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, now - 1000)
            .putExtra("EXTRA_GLUCOSE_VAL", "200")
            .putExtra("EXTRA_RATE", 0f)
        // This executes the real flavour activity: phone in the phone task, watch
        // in the Wear task. No new intent is delivered when the task returns.
        val controller = org.robolectric.Robolectric.buildActivity(tk.glucodata.ui.AlarmActivity::class.java, intent)
            .create().start().resume().pause().stop()
        try {
            receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
            assertFalse(controller.get().isFinishing)
            controller.restart().start()
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.stop().destroy()
        }
    }

    @Test fun theSharedLaunchCheckLetsManualTestsThroughTheQuietPeriod() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        val intent = android.content.Intent().putExtra("EXTRA_ALERT_TYPE_ID", AlertType.HIGH.id)
        assertTrue(tk.glucodata.ui.AlarmActivityAccess.isAcknowledged(intent))
        val app = Applic.app as TestApplication
        tk.glucodata.receivers.AlarmLaunchReceiver().onReceive(app, intent)
        assertTrue(app.launchedAlarms.isEmpty())
        AlertStateTracker.allowNextTriggerForTest(AlertType.HIGH)
        AlertStateTracker.shouldTrigger(AlertType.HIGH, tk.glucodata.alerts.AlertConfig(type = AlertType.HIGH))
        assertFalse(tk.glucodata.ui.AlarmActivityAccess.isAcknowledged(intent))
        tk.glucodata.receivers.AlarmLaunchReceiver().onReceive(app, intent)
        assertEquals(1, app.launchedAlarms.size)
    }

    @Test fun futureClockCancellationClearsTheSharedSnoozeWithoutAcknowledgingAFreshAlarm() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, true, now - 60_000, now + 3_540_000))
        AlertStateTracker.onAlertTriggered(AlertType.HIGH)
        AlarmSink.calls.clear()
        val cancellation = WearAlertAcknowledgement(AlertType.HIGH.id, false, now + 10_000, now + 10_000)
        receive(cancellation)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertFalse(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertTrue(AlertStateTracker.isEpisodeActive(AlertType.HIGH))
        assertTrue(AlarmSink.calls.isEmpty())
        assertFalse(WearAlertSync.isSuppressed(AlertType.HIGH.id))
        assertFalse(WearAlertSync.shouldCloseAlarm(AlertType.HIGH.id, now - 1000))
        val intent = android.content.Intent().putExtra("EXTRA_ALERT_TYPE_ID", AlertType.HIGH.id)
            .putExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, now - 1000)
        assertFalse(tk.glucodata.ui.AlarmActivityAccess.isAcknowledged(intent))
        assertEquals(cancellation, WearAlertSync.snapshot().first { it.kind == AlertType.HIGH.id })
    }

    @Test fun reconnectSnapshotRetainsExpiredActionsAndSnoozeCancellationTombstones() {
        val now = System.currentTimeMillis()
        val expired = WearAlertAcknowledgement(AlertType.HIGH.id, false, now - 300_000, now - 1)
        receive(expired)
        assertTrue(WearAlertSync.snapshot().contains(expired))
        receive(WearAlertAcknowledgement(AlertType.LOW.id, true, now, now + 300_000))
        SnoozeManager.clearSnooze(AlertType.LOW)
        val terminal = WearAlertSync.snapshot().first { it.kind == AlertType.LOW.id }
        assertFalse(terminal.snoozed)
        assertEquals(terminal.actedAtMs, terminal.untilMs)
        assertEquals(terminal, WearAlertAcknowledgement.decode(terminal.encode(), now + 1))
    }

    @Test fun localAndRemoteLossActionsStopThePresentedLossAlarm() {
        AlarmSink.currentKind = AlertType.LOSS.id
        AlertActions.handle(AlertType.LOSS.id, null, 15)
        assertTrue(AlarmSink.calls.contains("stop"))
        AlarmSink.calls.clear()
        val now = WearAlertSync.nextActionTimeMillis(AlertType.LOSS.id)
        receive(WearAlertAcknowledgement(AlertType.LOSS.id, false, now, now + 300_000))
        assertTrue(AlarmSink.calls.contains("remote-stop:false"))
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
