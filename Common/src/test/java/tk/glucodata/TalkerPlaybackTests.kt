package tk.glucodata

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech

/** Exercise the real Talker queue and Android focus requests; no native library or TTS service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeechTestApplication::class,
    shadows = [SpeechTestNatives::class, SpeechTestEngine::class])
class TalkerPlaybackTests {
    private lateinit var audio: AudioManager
    private lateinit var talker: Talker

    @Before fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as SpeechTestApplication
        audio = Applic.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        SpeechTestNatives.usage = AudioAttributes.USAGE_MEDIA
        // Notify caches the process service; Robolectric creates new application services.
        Notify.doTurnFocusoff()
        Notify::class.java.getDeclaredField("audioManager").apply { isAccessible = true }.set(null, audio)
        Notify.makenotification_audio()
        Talker.notifyfocus = false
        talker = Talker(Applic.app)
        talker.engineReady = true
    }

    @After fun tearDown() {
        Talker.notifyfocus = false
        talker.destruct()
        Notify.doTurnFocusoff()
    }

    @Test fun announcementsRespectMutedAndLowSystemVolumeOnEveryConfiguredStream() {
        for ((usage, stream) in listOf(
            AudioAttributes.USAGE_MEDIA to AudioManager.STREAM_MUSIC,
            AudioAttributes.USAGE_NOTIFICATION to AudioManager.STREAM_NOTIFICATION,
            AudioAttributes.USAGE_ALARM to AudioManager.STREAM_ALARM,
        )) {
            SpeechTestNatives.usage = usage
            Notify.makenotification_audio()
            for (volume in listOf(0, 1)) {
                audio.setStreamVolume(stream, volume, 0)
                val before = audio.getStreamVolume(stream)
                assertTrue(talker.speak("5.8", true))
                assertEquals("usage=$usage volume=$volume", before, audio.getStreamVolume(stream))
                assertEquals(usage, SpeechTestEngine.attributes!!.usage)
                assertEquals("5.8", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
            }
        }
    }

    @Test fun routineSpeechRequestsDuckingBeforeTheWordsStart() {
        assertTrue(talker.speak("5.8"))
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        talker.destruct()
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun trendPhraseIsQueuedTogetherAndOwnsFocusUntilTheWordsFinish() {
        val message = GlucoseSpeech.format(Applic.app, "6.6", 1f)
        assertTrue(talker.speak(message, true))
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals("6.6, increasing", engine.lastSpokenText)
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        engine.utteranceProgressListener.onDone(LeadIn.ID)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        engine.utteranceProgressListener.onDone(wordsId())
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun stoppingSpeechCannotAbandonAnAlarmThatStartedDuringIt() {
        assertTrue(talker.speak("5.8"))
        val speechFocus = shadowOf(audio).lastAudioFocusRequest
        val speechRequest = speechFocus.audioFocusRequest
        Notify.doTurnFocuson()
        val alarmFocus = shadowOf(audio).lastAudioFocusRequest
        val alarmRequest = alarmFocus.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, alarmRequest.focusGain)
        assertNotSame(speechFocus.listener, alarmFocus.listener)
        talker.destruct()
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        Notify.doTurnFocusoff()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun speechDuringAnAlarmDefersItsRequestUntilAlarmShutdown() {
        Notify.doTurnFocuson()
        val alarmRequest = shadowOf(audio).lastAudioFocusRequest
        Talker.notifyfocus = true
        assertTrue(talker.speak("5.8"))
        assertSame(alarmRequest, shadowOf(audio).lastAudioFocusRequest)
        val id = wordsId()
        assertNull(Talker.finishAlarmFocus(false))
        val speechRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, speechRequest.focusGain)
        assertSame(alarmRequest.audioFocusRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        listener().onDone(id)
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun refusedFocusIntentionallyStillQueuesSpeechWithoutClaimingDuckingSucceeded() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertTrue(talker.speak("5.8"))
        assertEquals("5.8", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        talker.destruct()
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun completionErrorAndStopReleaseSpeechFocusButLeadInDoesNot() {
        for (ending in listOf("done", "error", "stop")) {
            assertTrue(talker.speak("5.8"))
            val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
            val listener = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener
            val wordsId = Talker::class.java.getDeclaredField("lastWordsId").apply {
                isAccessible = true
            }.get(talker) as String
            val previouslyAbandoned = shadowOf(audio).lastAbandonedAudioFocusRequest
            listener.onDone(LeadIn.ID)
            assertSame(previouslyAbandoned, shadowOf(audio).lastAbandonedAudioFocusRequest)
            when (ending) {
                "done" -> listener.onDone(wordsId)
                "error" -> listener.onError(wordsId)
                "stop" -> listener.onStop(wordsId, true)
            }
            assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        }
    }

    @Test fun lateCallbackCannotUnduckANewerAnnouncement() {
        val focus = SpeechFocus(SpeechAudioFocus())
        focus.claim("5.8#1")
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        focus.claim("5.9#2")
        focus.release("5.8#1")
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        focus.release("5.9#2")
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun completionDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("done")

    @Test fun errorDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("error")

    @Test fun stopDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("stop")

    @Test fun lateStartAndStopCannotReplaceTheNewerQueuedSpeechOwner() {
        assertTrue(talker.speak("5.7"))
        val stale = wordsId()
        assertTrue(talker.speak("5.8"))
        val current = wordsId()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        listener().onStart(stale)
        listener().onStop(stale, true)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        listener().onDone(current)
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun previewIsClaimedBeforeQueuingAndLatePreviewStartCannotReplaceSpeech() {
        Talker::class.java.getDeclaredMethod("speakPreview").apply { isAccessible = true }.invoke(talker)
        val stale = wordsId()
        assertTrue(stale.startsWith("voice_preview#"))
        assertTrue(talker.speak("5.8"))
        val current = wordsId()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        listener().onStart(stale)
        listener().onStop(stale, true)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        listener().onDone(current)
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun closingDelayRemainsFocusedWhenTheOpeningSpeechEnds() {
        Notify.doTurnFocuson()
        Talker.notifyfocus = true
        assertTrue(talker.speak("5.7"))
        val opening = wordsId()
        val pending = Talker.finishAlarmFocus(true)!!
        val speechRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        val previouslyAbandoned = shadowOf(audio).lastAbandonedAudioFocusRequest
        try {
            listener().onDone(opening)
            assertSame(previouslyAbandoned, shadowOf(audio).lastAbandonedAudioFocusRequest)
            assertTrue(talker.speak("5.8"))
            val closing = wordsId()
            pending.close()
            assertSame(previouslyAbandoned, shadowOf(audio).lastAbandonedAudioFocusRequest)
            listener().onDone(closing)
            assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        } finally { pending.close() }
    }

    @Test fun missingClosingTalkerReleasesReservationWithoutAbandoningANewerAlarm() {
        Notify.doTurnFocuson()
        Talker.notifyfocus = true
        val pending = Talker.finishAlarmFocus(true)!!
        val speechRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        Notify.doTurnFocuson()
        Talker.notifyfocus = true
        val alarmRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        pending.close()
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        Notify.doTurnFocusoff()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    private fun wordsId() = Talker::class.java.getDeclaredField("lastWordsId").apply {
        isAccessible = true
    }.get(talker) as String

    private fun listener() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener

    private fun speechEndingDuringAlarm(ending: String) {
        val owner = Talker::class.java.getDeclaredField("lastWordsId").apply { isAccessible = true }
        assertTrue(talker.speak("5.7"))
        val earlierId = owner.get(talker) as String
        assertTrue(talker.speak("5.8"))
        val currentId = owner.get(talker) as String
        val speechRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        val listener = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener

        // A loss alarm (or an alarm without spoken values) starts while routine speech owns
        // ducking focus. No closing announcement will replace that speech owner later.
        Notify.doTurnFocuson()
        Talker.notifyfocus = true
        val alarmRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        listener.onStop(earlierId, true)
        listener.onDone(LeadIn.ID)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)

        when (ending) {
            "done" -> listener.onDone(currentId)
            "error" -> listener.onError(currentId)
            "stop" -> listener.onStop(currentId, true)
        }
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)

        Talker.notifyfocus = false
        Notify.doTurnFocusoff()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        // Speech is already released: shutdown must not be needed to restore media.
        talker.destruct()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }
}

@Implements(TextToSpeech::class)
class SpeechTestEngine : ShadowTextToSpeech() {
    @Implementation fun setAudioAttributes(value: AudioAttributes): Int {
        attributes = value
        return TextToSpeech.SUCCESS
    }

    companion object {
        var attributes: AudioAttributes? = null
    }
}

class SpeechTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(Natives::class)
class SpeechTestNatives {
    companion object {
        var usage = AudioAttributes.USAGE_MEDIA
        @JvmStatic @Implementation fun getSoundType(): Int = usage
    }
}
