package tk.glucodata

import android.app.UiModeManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * End-to-end Robolectric verification of [VoiceAudioTrigger] + [VoiceReadings] + [Talker]:
 * - Persists Bluetooth & car audio preferences cleanly
 * - Activates automatically when a selected BT audio device or Android Auto car mode connects
 * - Routes periodic speech over media audio and overrides the separation interval and schedule
 * - Allows one-tap session mute via [VoiceReadings.setOn] without mutating manual Speak glucose
 * - Tears down idle Talker and clears session mute on disconnect (never gets stuck ON)
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = SpeechTestApplication::class,
    shadows = [VoiceTriggerTestNatives::class, VoiceTriggerTestEngine::class],
)
class VoiceAudioTriggerTests {

    private lateinit var app: SpeechTestApplication
    private lateinit var audio: AudioManager
    private lateinit var uiMode: UiModeManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as SpeechTestApplication
        Applic.app = app
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        uiMode = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        VoiceTriggerTestNatives.reset()
        VoiceTriggerTestEngine.reset()
        FakeCarConnectionProvider.reset()
        SuperGattCallback.dotalk = false
        SuperGattCallback.talker?.destruct()
        SuperGattCallback.talker = null
        Talker.nexttime = 0L
        Talker.lastSpokenReadingTimeMs = 0L

        Notify.doTurnFocusoff()
        Notify::class.java.getDeclaredField("audioManager").apply { isAccessible = true }.set(null, audio)
        Notify.makenotification_audio()

        app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        VoiceAudioTrigger.resetForTests()
        shadowOf(audio).setOutputDevices(emptyList())
        uiMode.disableCarMode(0)
    }

    @After
    fun tearDown() {
        FakeCarConnectionProvider.reset()
        VoiceAudioTrigger.resetForTests()
        SuperGattCallback.dotalk = false
        SuperGattCallback.talker?.destruct()
        SuperGattCallback.talker = null
        Notify.doTurnFocusoff()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fakeBtDevice(
        address: String,
        name: String,
        type: Int = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    ): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder()
            .setType(type)
            .build()
        val portField = AudioDeviceInfo::class.java.getDeclaredField("mPort").apply { isAccessible = true }
        val port = portField.get(info)!!
        port.javaClass.getDeclaredField("mAddress").apply { isAccessible = true }.set(port, address)
        var cls: Class<*>? = port.javaClass
        while (cls != null) {
            try {
                cls.getDeclaredField("mName").apply { isAccessible = true }.set(port, name)
                break
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return info
    }

    @Test
    fun selectedBluetoothDevice_activatesSpeechOnMediaStreamWithCustomIntervalAndBypassesSchedule() {
        // Manual voice is OFF, notification stream is NOTIFICATION, and SpeakSchedule is closed.
        VoiceTriggerTestNatives.voiceActiveVal = false
        VoiceTriggerTestNatives.soundTypeVal = AudioAttributes.USAGE_NOTIFICATION
        Notify.makenotification_audio()
        SpeakSchedule.setEnabled(app, true)
        SpeakSchedule.setStartMinutes(app, 0)
        SpeakSchedule.setEndMinutes(app, 1) // almost always outside schedule

        val carMac = "AA:BB:CC:DD:EE:FF"
        VoiceAudioTrigger.toggleSavedDevice(
            app,
            VoiceAudioPolicy.SavedDevice(
                id = "mac:$carMac",
                address = carMac,
                name = "Mazda Connect",
                isCar = true,
            ),
        )
        VoiceAudioTrigger.setIntervalSeconds(app, 300)
        VoiceAudioTrigger.setUseMediaStream(app, true)
        VoiceAudioTrigger.setSpeakAlarms(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        assertFalse(VoiceAudioTrigger.isAlarmSpeechEnabled(app))
        assertNull(SuperGattCallback.talker)

        // Car stereo connects over Bluetooth A2DP.
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "Mazda Connect")))
        VoiceAudioTrigger.refreshState(app)

        assertTrue(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        assertTrue(VoiceAudioTrigger.isAlarmSpeechEnabled(app))
        assertTrue(VoiceAudioTrigger.useMediaStreamNow(app))
        assertEquals(300, VoiceAudioTrigger.effectiveIntervalSeconds(app, 60))
        assertNotNull("Talker should warm up automatically on connect", SuperGattCallback.talker)

        val status = VoiceReadings.status(app)
        assertTrue(status.on)
        assertTrue(status.autoAudioActive)
        assertEquals("Mazda Connect", status.autoAudioDeviceName)
        assertTrue(status.autoAudioViaCar)
        assertEquals(300, status.intervalSeconds)
        assertNull("Schedule hold must not apply while car audio triggers voice", status.resumesAtMinutes)

        // Periodic speech routes over USAGE_MEDIA even though default stream is USAGE_NOTIFICATION,
        // and restores USAGE_NOTIFICATION afterwards.
        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        Talker.nexttime = 0L
        talker.selspeak("5.8, stable")
        assertEquals(AudioAttributes.USAGE_MEDIA, VoiceTriggerTestEngine.attributesAtSpeak!!.usage)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, VoiceTriggerTestEngine.currentAttributes!!.usage)
        assertEquals("5.8, stable", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)

        // Leaving the car immediately stops auto speech and cleans up idle Talker.
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)

        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        assertFalse(VoiceAudioTrigger.isAlarmSpeechEnabled(app))
        assertFalse(VoiceReadings.isActive(app))
        assertNull("Idle Talker should shut down when car disconnects", SuperGattCallback.talker)
    }

    @Test
    fun dashboardOrNotificationTurnOff_mutesOnlyCurrentSessionWithoutChangingManualOrSavedSetting() {
        val carMac = "11:22:33:44:55:66"
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "BMW 530i")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue(VoiceReadings.isActive(app))

        // User taps "Turn off voice" on dashboard pill or notification.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionMuted())
        assertFalse(VoiceReadings.isActive(app))
        assertFalse("Manual Speak glucose must stay off", VoiceReadings.isOn())
        assertTrue("Bluetooth auto-speak preference must stay enabled", VoiceAudioTrigger.isEnabled(app))

        // Tapping "Turn on voice" resumes the session without enabling manual Speak glucose permanently.
        VoiceReadings.setOn(app, true)
        assertFalse(VoiceAudioTrigger.isSessionMuted())
        assertTrue(VoiceReadings.isActive(app))
        assertFalse(VoiceReadings.isOn())

        // Mute again, then disconnect and reconnect after the dropout cooldown -> next drive starts unmuted.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionMuted())
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)
        assertFalse(VoiceAudioTrigger.isSessionMuted())
        VoiceAudioTrigger.lastPausedDisconnectedAtMs = System.currentTimeMillis() - VoiceAudioPolicy.CONNECT_ANNOUNCE_COOLDOWN_MS - 1_000L

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "BMW 530i")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue("New connection after cooldown should automatically resume unmuted", VoiceReadings.isActive(app))
    }

    @Test
    fun androidAutoCarMode_triggersEvenWithoutMatchingBluetoothDevice() {
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.SELECTED_DEVICES)
        VoiceAudioTrigger.setIncludeCarMode(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))

        uiMode.enableCarMode(0)
        assertEquals(Configuration.UI_MODE_TYPE_CAR, uiMode.currentModeType)
        VoiceAudioTrigger.refreshState(app)

        assertTrue(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        val match = VoiceAudioTrigger.currentMatch(app)
        assertTrue(match.viaCarMode)
        assertEquals("car_mode", match.triggerKey)

        uiMode.disableCarMode(0)
        VoiceAudioTrigger.refreshState(app)
        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
    }

    @Test
    fun disconnectingLongCarInterval_clampsNextTimeBackToDefaultInterval() {
        VoiceTriggerTestNatives.voiceActiveVal = true
        VoiceTriggerTestNatives.voiceSeparationVal = 60
        Talker.getvalues()
        SuperGattCallback.dotalk = true

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setIntervalSeconds(app, 1800) // 30 min in car
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("AA:BB:CC:DD:EE:FF", "My Car")))
        VoiceAudioTrigger.refreshState(app)

        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        Talker.nexttime = 0L
        val beforeSpeak = System.currentTimeMillis()
        talker.selspeak("6.2, stable")
        assertTrue("Car interval should schedule ~30 min ahead", Talker.nexttime >= beforeSpeak + 1_700_000L)

        // Leave car -> nexttime must clamp back to default 60s instead of silencing manual speech for 30 min.
        shadowOf(audio).setOutputDevices(emptyList())
        val beforeDisconnect = System.currentTimeMillis()
        VoiceAudioTrigger.refreshState(app)
        assertTrue(
            "After leaving car, nexttime should clamp to <= now + default interval",
            Talker.nexttime <= beforeDisconnect + 70_000L,
        )
    }

    @Test
    fun pausingDuringCarSession_preservesManualSpeakGlucoseAcrossResumeAndDisconnect() {
        VoiceTriggerTestNatives.voiceActiveVal = true
        SuperGattCallback.dotalk = true
        val carMac = "AA:BB:CC:DD:EE:11"
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "Family Car")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue(VoiceReadings.isActive(app))

        // Pause during drive -> readings stop, but manual Speak glucose stays persisted as true.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionPaused(app))
        assertFalse(VoiceReadings.isActive(app))
        assertTrue("Manual Speak glucose must not be overwritten on session pause", VoiceReadings.isOn())

        val talker = SuperGattCallback.talker ?: Talker(app).also { SuperGattCallback.talker = it }
        talker.engineReady = true
        Talker.nexttime = 0L
        talker.selspeak("6.4, stable")
        assertNull("Paused session must not speak readings", VoiceTriggerTestEngine.attributesAtSpeak)

        // Resume -> active again.
        VoiceReadings.setOn(app, true)
        assertFalse(VoiceAudioTrigger.isSessionPaused(app))
        assertTrue(VoiceReadings.isActive(app))

        // Pause again, then disconnect -> manual Speak glucose resumes automatically outside the car.
        VoiceReadings.setOn(app, false)
        assertFalse(VoiceReadings.isActive(app))
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)
        assertFalse(VoiceAudioTrigger.isSessionPaused(app))
        assertTrue("Manual Speak glucose must remain on after leaving the car", VoiceReadings.isOn())
        assertTrue(VoiceReadings.isActive(app))
    }

    @Test
    fun speakConnectAnnouncement_doesNotSpeakWhenSessionWasPausedOrDisconnected() {
        val carMac = "AA:BB:CC:DD:EE:22"
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "Work Van")))
        VoiceAudioTrigger.refreshState(app)
        val talker = SuperGattCallback.talker!!
        talker.engineReady = true

        VoiceAudioTrigger.setSessionMuted(app, true)
        assertFalse(
            "Paused session must not emit a connect announcement",
            talker.speakConnectAnnouncement("5.9, stable", true, false),
        )
        assertNull(VoiceTriggerTestEngine.attributesAtSpeak)
    }

    @Test
    fun switchingFromPausedHeadphonesToSelectedCar_clearsSessionPause() {
        val budsMac = "11:22:33:44:55:01"
        val carMac = "AA:BB:CC:DD:EE:99"
        VoiceAudioTrigger.toggleSavedDevice(
            app,
            VoiceAudioPolicy.SavedDevice("mac:$budsMac", budsMac, "Pixel Buds", isCar = false),
        )
        VoiceAudioTrigger.toggleSavedDevice(
            app,
            VoiceAudioPolicy.SavedDevice("mac:$carMac", carMac, "Mazda Connect", isCar = true),
        )
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        // Connect earbuds and pause speech.
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(budsMac, "Pixel Buds")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue(VoiceReadings.isActive(app))
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionPaused(app))
        assertFalse(VoiceReadings.isActive(app))

        // Switch to car (while earbuds briefly overlap, then disconnect): car must NOT inherit earbuds' pause.
        shadowOf(audio).setOutputDevices(
            listOf(fakeBtDevice(budsMac, "Pixel Buds"), fakeBtDevice(carMac, "Mazda Connect"))
        )
        VoiceAudioTrigger.refreshState(app)
        assertFalse("Switching to a different matched device must clear prior session pause", VoiceAudioTrigger.isSessionPaused(app))
        assertTrue(VoiceReadings.isActive(app))
        assertEquals("Mazda Connect", VoiceAudioTrigger.currentMatch(app).displayLabel)
    }

    @Test
    fun androidAutoProjectionProvider_updatesAsyncAndNeverQueriesOnReadingPath() {
        FakeCarConnectionProvider.queryCount.set(0)
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_PROJECTION
        Robolectric.buildContentProvider(FakeCarConnectionProvider::class.java)
            .create(VoiceAudioTrigger.CAR_CONNECTION_AUTHORITY)

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.SELECTED_DEVICES)
        VoiceAudioTrigger.setIncludeCarMode(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        // Confirm UiModeManager is NOT in car mode (Android 12+ behavior).
        assertFalse(uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR)

        // Wait for startup's async projection refresh to settle, then verify activation.
        assertTrue(VoiceAudioTrigger.awaitCarProjectionIdleForTests())
        VoiceAudioTrigger.refreshCarProjectionNow(app)
        assertTrue("Android Auto CarConnection projection must activate voice", VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        assertEquals("car_mode", VoiceAudioTrigger.currentMatch(app).triggerKey)

        // Critical: the live glucose reading path (off the main thread) must NEVER perform a
        // synchronous contentResolver.query() against the external Android Auto provider.
        val queriesBeforeReading = FakeCarConnectionProvider.queryCount.get()
        var activeOnWorker = false
        val worker = Thread { activeOnWorker = VoiceAudioTrigger.refreshAndCheckReadingSpeech(app) }
        worker.start()
        worker.join(5_000L)
        assertTrue(activeOnWorker)
        assertEquals(
            "Glucose reading path must read cached projection state without synchronous IPC",
            queriesBeforeReading,
            FakeCarConnectionProvider.queryCount.get(),
        )

        // Disconnect projection via async projection refresh -> deactivates cleanly.
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED
        VoiceAudioTrigger.refreshCarProjectionNow(app)
        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))
    }

    @Test
    fun everyReadingCarInterval_doesNotSkipFastConsecutiveReadings() {
        VoiceTriggerTestNatives.voiceSeparationVal = 300
        Talker.getvalues()

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setIntervalSeconds(app, VoiceAudioPolicy.INTERVAL_EVERY_READING)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("AA:BB:CC:DD:EE:44", "Fast Car")))
        VoiceAudioTrigger.refreshState(app)
        assertEquals(0L, VoiceAudioTrigger.effectiveIntervalMs(app, 300_000L))

        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        Talker.nexttime = 0L
        VoiceTriggerTestEngine.speakCount = 0

        talker.selspeak("6.1, stable", 1_000L)
        val afterFirst = System.currentTimeMillis()
        assertEquals(1, VoiceTriggerTestEngine.speakCount)
        assertTrue(
            "Every reading preset must not advance nexttime ahead into the future",
            Talker.nexttime <= afterFirst,
        )
        // Duplicate delivery of the exact same reading timestamp must NOT speak twice.
        Thread.sleep(5L)
        talker.selspeak("6.1, stable", 1_000L)
        assertEquals("Same reading timestamp must be deduplicated in Every reading mode", 1, VoiceTriggerTestEngine.speakCount)

        // Newer reading arriving right after must speak immediately (no 60s throttle).
        Thread.sleep(5L)
        talker.selspeak("6.2, stable", 2_000L)
        assertEquals("Every reading preset must speak consecutive readings without 60s skipping", 2, VoiceTriggerTestEngine.speakCount)
    }

    @Test
    fun scoOnlyWatchOrHandsfreeDevice_neverTriggersSpeech() {
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        // Galaxy Watch connected over SCO (call audio only, no A2DP media route).
        shadowOf(audio).setOutputDevices(
            listOf(fakeBtDevice("AA:BB:CC:DD:EE:55", "Galaxy Watch6", AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        )
        VoiceAudioTrigger.refreshState(app)
        assertFalse("SCO-only smartwatch must NOT activate voice readings", VoiceAudioTrigger.isAutoReadingSpeechActive(app))
        assertFalse("SCO-only smartwatch must NOT trigger media routing", VoiceAudioTrigger.useMediaStreamNow(app))
    }

    @Test
    fun briefBluetoothDropout_preservesUserSessionPause() {
        val carMac = "AA:BB:CC:DD:EE:77"
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "My Car")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue(VoiceReadings.isActive(app))

        // User pauses voice for this drive.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionPaused(app))

        // Brief 50ms dropout and reconnect of the exact same device.
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "My Car")))
        VoiceAudioTrigger.refreshState(app)

        assertTrue("Short dropout of the same device must keep user's pause intact", VoiceAudioTrigger.isSessionPaused(app))
        assertFalse(VoiceReadings.isActive(app))
    }

    @Test
    fun staleAndroidAutoProjection_recoversViaThrottledAsyncRefreshOnReadingPath() {
        FakeCarConnectionProvider.queryCount.set(0)
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_PROJECTION
        Robolectric.buildContentProvider(FakeCarConnectionProvider::class.java)
            .create(VoiceAudioTrigger.CAR_CONNECTION_AUTHORITY)

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.SELECTED_DEVICES)
        VoiceAudioTrigger.setIncludeCarMode(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        val latch1 = java.util.concurrent.CountDownLatch(1)
        Applic.scheduler.execute { latch1.countDown() }
        assertTrue(latch1.await(5L, java.util.concurrent.TimeUnit.SECONDS))
        VoiceAudioTrigger.refreshCarProjectionNow(app)
        assertTrue(VoiceAudioTrigger.isAutoReadingSpeechActive(app))

        // Android Auto crashes without sending CAR_CONNECTION_UPDATED; provider now reports disconnected.
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED
        // Pretend >30s elapsed since last projection check so the reading path queues an async recheck.
        VoiceAudioTrigger.lastCarProjectionCheckMs = System.currentTimeMillis() - 60_000L
        VoiceAudioTrigger.refreshAndCheckReadingSpeech(app)

        val latch2 = java.util.concurrent.CountDownLatch(1)
        Applic.scheduler.execute { latch2.countDown() }
        assertTrue(latch2.await(5L, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(
            "Throttled async projection refresh must recover when Android Auto disconnect broadcast was missed",
            VoiceAudioTrigger.isAutoReadingSpeechActive(app),
        )
    }

    @Test
    fun nonCarHeadphones_respectSpeakScheduleWhileCarBypassesIt() {
        // Set a schedule that is definitely closed right now (1-minute window 12 hours away).
        val nowCal = java.util.Calendar.getInstance()
        val nowMins = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
        val farStart = (nowMins + 720) % 1440
        val farEnd = (farStart + 1) % 1440
        SpeakSchedule.setStartMinutes(app, farStart)
        SpeakSchedule.setEndMinutes(app, farEnd)
        SpeakSchedule.setEnabled(app, true)
        assertFalse(SpeakSchedule.isWithinSchedule(app))

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        // Ordinary headphones (Audio-Technica — must NOT be misclassified as a car!) respect schedule.
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("11:22:33:44:55:99", "Audio-Technica")))
        VoiceAudioTrigger.refreshState(app)
        assertFalse("Headphones must not bypass Speak schedule", VoiceAudioTrigger.isAutoReadingSpeechActive(app))

        // Actual car ("Audi MMI") bypasses Speak schedule while driving.
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("AA:BB:CC:DD:EE:88", "Audi MMI")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue("Car audio should bypass Speak schedule while driving", VoiceAudioTrigger.isAutoReadingSpeechActive(app))
    }

    @Test
    fun speakTwice_queuesTwoReadingPassesOverCarAudio() {
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)
        Talker.setSpeakTwice(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("AA:BB:CC:DD:EE:66", "My Car")))
        VoiceAudioTrigger.refreshState(app)

        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        Talker.nexttime = 0L
        VoiceTriggerTestEngine.speakCount = 0

        talker.selspeak("6.4, stable", 5_000L)
        assertEquals("Speak twice must queue two spoken passes for one reading", 2, VoiceTriggerTestEngine.speakCount)
    }

    @Test
    fun dualModeBudsConnectedViaA2dpAndBleHeadset_listOnlyOnce() {
        shadowOf(audio).setOutputDevices(
            listOf(
                fakeBtDevice("AA:BB:CC:11:22:01", "Steven's Buds", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                fakeBtDevice("44:55:66:77:88:02", "Steven's Buds", AudioDeviceInfo.TYPE_BLE_HEADSET),
            )
        )
        val result = VoiceAudioTrigger.listSelectableDevices(app, includeAllPaired = false)
        assertEquals("Dual-mode Classic + LE Audio Buds must appear once in Connected now", 1, result.connected.size)
        assertEquals("Steven's Buds", result.connected[0].name)
        assertEquals(listOf("44:55:66:77:88:02"), result.connected[0].alternateAddresses)
    }

    @Test
    fun disconnectedProjectionCache_recoversViaThrottledAsyncRefreshOnReadingPath() {
        FakeCarConnectionProvider.queryCount.set(0)
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED
        Robolectric.buildContentProvider(FakeCarConnectionProvider::class.java)
            .create(VoiceAudioTrigger.CAR_CONNECTION_AUTHORITY)

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.SELECTED_DEVICES)
        VoiceAudioTrigger.setIncludeCarMode(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        assertTrue(VoiceAudioTrigger.awaitCarProjectionIdleForTests())
        assertFalse(VoiceAudioTrigger.isAutoReadingSpeechActive(app))

        // Provider now reports active Android Auto (e.g. startup query had transiently failed).
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_PROJECTION
        VoiceAudioTrigger.lastCarProjectionCheckMs = System.currentTimeMillis() - 60_000L
        VoiceAudioTrigger.refreshAndCheckReadingSpeech(app)

        assertTrue(VoiceAudioTrigger.awaitCarProjectionIdleForTests())
        assertTrue(
            "Throttled async refresh while disconnected must recover an active Android Auto connection",
            VoiceAudioTrigger.isAutoReadingSpeechActive(app),
        )
    }

    @Test
    fun carProjectionRefresh_runsOffSharedSchedulerAndCoalescesOverlappingRequests() {
        FakeCarConnectionProvider.reset()
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_PROJECTION
        Robolectric.buildContentProvider(FakeCarConnectionProvider::class.java)
            .create(VoiceAudioTrigger.CAR_CONNECTION_AUTHORITY)

        val enteredFirstQuery = java.util.concurrent.CountDownLatch(1)
        val releaseFirstQuery = java.util.concurrent.CountDownLatch(1)
        FakeCarConnectionProvider.onQuery = { _ ->
            if (FakeCarConnectionProvider.queryCount.get() == 1) {
                enteredFirstQuery.countDown()
                releaseFirstQuery.await(5L, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        VoiceAudioTrigger.refreshCarProjectionAsync(app)
        assertTrue("First provider query should start on dedicated executor", enteredFirstQuery.await(5L, java.util.concurrent.TimeUnit.SECONDS))

        // Applic.scheduler must stay completely unblocked while the external provider query stalls.
        val schedulerRan = java.util.concurrent.CountDownLatch(1)
        Applic.scheduler.execute { schedulerRan.countDown() }
        assertTrue("Shared Applic.scheduler must not be blocked by CarConnection query", schedulerRan.await(1L, java.util.concurrent.TimeUnit.SECONDS))

        // Burst of 5 refresh requests during an in-flight query must coalesce into at most 1 follow-up.
        repeat(5) {
            VoiceAudioTrigger.refreshCarProjectionAsync(app)
        }
        releaseFirstQuery.countDown()
        assertTrue(VoiceAudioTrigger.awaitCarProjectionIdleForTests())

        assertEquals("Dedicated worker must never overlap provider queries", 1, FakeCarConnectionProvider.maxConcurrentQueries.get())
        assertEquals("Burst of overlapping refreshes must coalesce to 1 in-flight + 1 follow-up", 2, FakeCarConnectionProvider.queryCount.get())
        assertEquals("VoiceCarProjection", FakeCarConnectionProvider.lastQueryThreadName)
    }

    @Test
    fun carProjectionQuery_timesOutViaCancellationSignal() {
        FakeCarConnectionProvider.reset()
        FakeCarConnectionProvider.state = VoiceAudioTrigger.CONNECTION_TYPE_PROJECTION
        Robolectric.buildContentProvider(FakeCarConnectionProvider::class.java)
            .create(VoiceAudioTrigger.CAR_CONNECTION_AUTHORITY)

        val canceled = java.util.concurrent.CountDownLatch(1)
        FakeCarConnectionProvider.onQuery = { signal ->
            signal?.setOnCancelListener { canceled.countDown() }
            if (canceled.await(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                throw OperationCanceledException()
            }
        }
        VoiceAudioTrigger.carProjectionQueryTimeoutMs = 50L

        val result = VoiceAudioTrigger.queryCarProjectionProvider(app)
        assertEquals(VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED, result)
        assertEquals(0L, canceled.count)
    }

    @Test
    fun closingAlarmSpeech_revalidatesEligibilityBeforePlaybackAndReleasesFocus() {
        // Keep the TTS engine alive via touchTalk so pause/disconnect does not tear down talker.
        VoiceTriggerTestNatives.touchTalkVal = true
        VoiceTriggerTestNatives.speakAlarmsVal = false

        val carMac = "AA:BB:CC:DD:EE:88"
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setSpeakAlarms(app, true)
        VoiceAudioTrigger.setUseMediaStream(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "Mazda Connect")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue(VoiceAudioTrigger.isAlarmSpeechEnabled(app))

        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        val values = tk.glucodata.ui.DisplayValueResolver.resolve(
            autoValue = 4.1f,
            rawValue = 4.1f,
            calibratedValue = 4.1f,
            viewMode = 0,
            isMmol = true,
        )
        val snapshot = CurrentDisplaySource.Snapshot(
            timeMillis = 1_700_000_000_000L,
            rate = -1f,
            sensorId = "alarm-test",
            sensorGen = 0,
            index = 0,
            viewMode = 0,
            source = "test",
            autoValue = values.primaryValue,
            rawValue = values.primaryValue,
            sharedDisplayValue = values.primaryValue,
            sharedMgdl = 74,
            isMmol = true,
            displayValues = values,
        )

        var focusAbandoned = false
        val focus = SpeechFocus(object : SpeechFocus.Audio {
            override fun request() {}
            override fun abandon() {
                focusAbandoned = true
            }
        })

        // 1) Session paused during the 300 ms delay -> must NOT speak, must still release focus.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionPaused(app))
        assertNotNull(SuperGattCallback.talker)
        focusAbandoned = false
        VoiceTriggerTestEngine.speakCount = 0
        Notify.speakClosingAlarmIfEligible(app, snapshot, false, focus.reserve())
        assertEquals("Paused session must block delayed closing alarm speech", 0, VoiceTriggerTestEngine.speakCount)
        assertTrue("Focus hold must still be released when closing alarm speech is skipped", focusAbandoned)

        // 2) Resume, then disconnect BT during the 300 ms delay -> must NOT speak, must still release focus.
        VoiceReadings.setOn(app, true)
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)
        assertNotNull(SuperGattCallback.talker)
        focusAbandoned = false
        VoiceTriggerTestEngine.speakCount = 0
        Notify.speakClosingAlarmIfEligible(app, snapshot, false, focus.reserve())
        assertEquals("Disconnected BT device must block delayed closing alarm speech", 0, VoiceTriggerTestEngine.speakCount)
        assertTrue("Focus hold must still be released after disconnect", focusAbandoned)

        // 3) Reconnected and active -> closing alarm speech plays over media stream and releases focus.
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "Mazda Connect")))
        VoiceAudioTrigger.refreshState(app)
        focusAbandoned = false
        VoiceTriggerTestEngine.speakCount = 0
        Notify.speakClosingAlarmIfEligible(app, snapshot, false, focus.reserve())
        assertEquals("Active connected BT device must speak closing alarm", 1, VoiceTriggerTestEngine.speakCount)
        assertEquals(AudioAttributes.USAGE_MEDIA, VoiceTriggerTestEngine.attributesAtSpeak?.usage)
        assertTrue("Focus hold must be released after queuing closing alarm", focusAbandoned)
    }

    @Test
    fun customConnectAnnounceDelay_persistsAndDrivesSchedulingDelay() {
        assertEquals(4, VoiceAudioTrigger.loadConfig(app).connectAnnounceDelaySeconds)
        assertEquals(4_000L, VoiceAudioTrigger.connectAnnouncementDelayMs(app, isRetry = false))
        assertEquals(VoiceAudioPolicy.CONNECT_TTS_RETRY_DELAY_MS, VoiceAudioTrigger.connectAnnouncementDelayMs(app, isRetry = true))

        VoiceAudioTrigger.setAnnounceConnectDelaySeconds(app, 15)
        assertEquals(15, VoiceAudioTrigger.loadConfig(app).connectAnnounceDelaySeconds)
        assertEquals(15_000L, VoiceAudioTrigger.connectAnnouncementDelayMs(app, isRetry = false))
        assertEquals(VoiceAudioPolicy.CONNECT_TTS_RETRY_DELAY_MS, VoiceAudioTrigger.connectAnnouncementDelayMs(app, isRetry = true))

        VoiceAudioTrigger.setAnnounceConnectDelaySeconds(app, 0)
        assertEquals(4, VoiceAudioTrigger.loadConfig(app).connectAnnounceDelaySeconds)
    }

    @Test
    fun resolveTestAudioAttributes_respectsMediaSwitchWhenNotificationAudibleAndFallsBackWhenMuted() {
        // Media OFF, Override silent OFF, Notification audible -> uses notification stream.
        VoiceTriggerTestNatives.soundTypeVal = AudioAttributes.USAGE_NOTIFICATION
        Notify.makenotification_audio()
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, Talker.resolveTestAudioAttributes(app).usage)

        // Notification muted (0) -> falls back to media so Test stays audible.
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        assertEquals(AudioAttributes.USAGE_MEDIA, Talker.resolveTestAudioAttributes(app).usage)

        // Global Media ON -> uses media stream.
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0)
        VoiceTriggerTestNatives.soundTypeVal = AudioAttributes.USAGE_MEDIA
        Notify.makenotification_audio()
        assertEquals(AudioAttributes.USAGE_MEDIA, Talker.resolveTestAudioAttributes(app).usage)

        // Global Media OFF, but BT car connected with Route through media audio ON -> uses media stream.
        VoiceTriggerTestNatives.soundTypeVal = AudioAttributes.USAGE_NOTIFICATION
        Notify.makenotification_audio()
        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setUseMediaStream(app, true)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)
        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("AA:BB:CC:DD:EE:90", "Mazda Connect")))
        VoiceAudioTrigger.refreshState(app)
        assertEquals(AudioAttributes.USAGE_MEDIA, Talker.resolveTestAudioAttributes(app).usage)
    }

    @Test
    fun softerVoiceVolume_passesReducedVolumeBundleToTts() {
        VoiceAudioBoost.setLevel(app, VoiceAudioBoost.LEVEL_SOFTER)
        assertEquals(VoiceAudioBoost.LEVEL_SOFTER, VoiceAudioBoost.getLevel(app))

        VoiceTriggerTestNatives.voiceActiveVal = true
        SuperGattCallback.dotalk = true
        SuperGattCallback.newtalker(app)
        val talker = SuperGattCallback.talker!!
        talker.engineReady = true
        Talker.nexttime = 0L
        VoiceTriggerTestEngine.reset()

        talker.selspeak("6.2, stable", 10_000L)
        assertEquals(1, VoiceTriggerTestEngine.speakCount)
        assertEquals(0.55f, VoiceTriggerTestEngine.lastParams?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME) ?: -1f, 0.01f)

        // Restoring Normal passes null params (direct untouched TTS path).
        VoiceAudioBoost.setLevel(app, VoiceAudioBoost.LEVEL_NORMAL)
        Talker.nexttime = 0L
        VoiceTriggerTestEngine.reset()
        talker.selspeak("6.3, stable", 20_000L)
        assertEquals(1, VoiceTriggerTestEngine.speakCount)
        assertNull(VoiceTriggerTestEngine.lastParams)
    }

    @Test
    fun scheduledHeadphonesOutsideSchedule_isActiveMatchesDashboardSoNotificationCanPause() {
        val nowCal = java.util.Calendar.getInstance()
        val nowMins = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
        val farStart = (nowMins + 720) % 1440
        val farEnd = (farStart + 1) % 1440
        SpeakSchedule.setStartMinutes(app, farStart)
        SpeakSchedule.setEndMinutes(app, farEnd)
        SpeakSchedule.setEnabled(app, true)
        assertFalse(SpeakSchedule.isWithinSchedule(app))

        VoiceAudioTrigger.setScope(app, VoiceAudioPolicy.Scope.ANY_BLUETOOTH_AUDIO)
        VoiceAudioTrigger.setAnnounceOnConnect(app, false)
        VoiceAudioTrigger.setEnabled(app, true)

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice("11:22:33:44:55:99", "Audio-Technica")))
        VoiceAudioTrigger.refreshState(app)

        // Dashboard status is ON (waiting for schedule), so notification isActive() must also be true
        // allowing the user to pause the upcoming scheduled speech from the notification.
        assertTrue(VoiceReadings.status(app).on)
        assertTrue(VoiceReadings.isActive(app))
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionPaused(app))
        assertFalse(VoiceReadings.isActive(app))
        assertFalse(VoiceReadings.status(app).on)
    }
}

class FakeCarConnectionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        val active = activeQueries.incrementAndGet()
        maxConcurrentQueries.accumulateAndGet(active, ::maxOf)
        lastQueryThreadName = Thread.currentThread().name
        queryCount.incrementAndGet()
        try {
            onQuery?.invoke(cancellationSignal)
            cancellationSignal?.throwIfCanceled()
            val cursor = MatrixCursor(arrayOf(VoiceAudioTrigger.CAR_CONNECTION_COLUMN))
            cursor.addRow(arrayOf(state))
            return cursor
        } finally {
            activeQueries.decrementAndGet()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        @Volatile
        var state: Int = VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED
        val queryCount = java.util.concurrent.atomic.AtomicInteger(0)
        val activeQueries = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrentQueries = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile
        var lastQueryThreadName: String? = null
        @Volatile
        var onQuery: ((CancellationSignal?) -> Unit)? = null

        fun reset() {
            state = VoiceAudioTrigger.CONNECTION_TYPE_NOT_CONNECTED
            queryCount.set(0)
            activeQueries.set(0)
            maxConcurrentQueries.set(0)
            lastQueryThreadName = null
            onQuery = null
        }
    }
}

@Implements(TextToSpeech::class)
class VoiceTriggerTestEngine : ShadowTextToSpeech() {
    @Implementation
    fun setAudioAttributes(value: AudioAttributes): Int {
        currentAttributes = value
        return TextToSpeech.SUCCESS
    }

    @Implementation
    override fun speak(
        text: CharSequence?,
        queueMode: Int,
        params: Bundle?,
        utteranceId: String?,
    ): Int {
        attributesAtSpeak = currentAttributes
        lastParams = params
        speakCount++
        return super.speak(text, queueMode, params, utteranceId)
    }

    companion object {
        var currentAttributes: AudioAttributes? = null
        var attributesAtSpeak: AudioAttributes? = null
        var lastParams: Bundle? = null
        var speakCount: Int = 0

        fun reset() {
            currentAttributes = null
            attributesAtSpeak = null
            lastParams = null
            speakCount = 0
        }
    }
}

@Implements(Natives::class)
class VoiceTriggerTestNatives {
    companion object {
        var soundTypeVal: Int = AudioAttributes.USAGE_NOTIFICATION
        var voiceActiveVal: Boolean = false
        var speakAlarmsVal: Boolean = false
        var touchTalkVal: Boolean = false
        var speakMessagesVal: Boolean = false
        var voiceSeparationVal: Int = 60

        fun reset() {
            soundTypeVal = AudioAttributes.USAGE_NOTIFICATION
            voiceActiveVal = false
            speakAlarmsVal = false
            touchTalkVal = false
            speakMessagesVal = false
            voiceSeparationVal = 60
        }

        @JvmStatic @Implementation fun getSoundType(): Int = soundTypeVal
        @JvmStatic @Implementation fun getVoiceActive(): Boolean = voiceActiveVal
        @JvmStatic @Implementation fun speakalarms(): Boolean = speakAlarmsVal
        @JvmStatic @Implementation fun gettouchtalk(): Boolean = touchTalkVal
        @JvmStatic @Implementation fun speakmessages(): Boolean = speakMessagesVal
        @JvmStatic @Implementation fun getVoiceSpeed(): Float = 1.0f
        @JvmStatic @Implementation fun getVoicePitch(): Float = 1.0f
        @JvmStatic @Implementation fun getVoiceSeparation(): Int = voiceSeparationVal
        @JvmStatic @Implementation fun getVoiceTalker(): Int = 0
        @JvmStatic @Implementation fun saveVoice(speed: Float, pitch: Float, sep: Int, talker: Int, active: Boolean) {
            voiceSeparationVal = sep
            voiceActiveVal = active
        }
    }
}
