package tk.glucodata

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
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
        SuperGattCallback.dotalk = false
        SuperGattCallback.talker?.destruct()
        SuperGattCallback.talker = null
        Talker.nexttime = 0L

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
        VoiceAudioTrigger.resetForTests()
        SuperGattCallback.dotalk = false
        SuperGattCallback.talker?.destruct()
        SuperGattCallback.talker = null
        Notify.doTurnFocusoff()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fakeBtDevice(address: String, name: String): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
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

        // Mute again, then disconnect and reconnect -> next drive starts unmuted automatically.
        VoiceReadings.setOn(app, false)
        assertTrue(VoiceAudioTrigger.isSessionMuted())
        shadowOf(audio).setOutputDevices(emptyList())
        VoiceAudioTrigger.refreshState(app)
        assertFalse(VoiceAudioTrigger.isSessionMuted())

        shadowOf(audio).setOutputDevices(listOf(fakeBtDevice(carMac, "BMW 530i")))
        VoiceAudioTrigger.refreshState(app)
        assertTrue("New connection should automatically resume unmuted", VoiceReadings.isActive(app))
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
        return super.speak(text, queueMode, params, utteranceId)
    }

    companion object {
        var currentAttributes: AudioAttributes? = null
        var attributesAtSpeak: AudioAttributes? = null

        fun reset() {
            currentAttributes = null
            attributesAtSpeak = null
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
