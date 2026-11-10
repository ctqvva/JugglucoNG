package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talker takes focus before an utterance's lead-in and the engine's callbacks arrive late, so
 * focus must follow the utterance that holds it, not whichever callback comes next.
 */
class SpeechFocusTests {

    /** Models Notify's single focus flag: a request while held, or an abandon while free, is a no-op. */
    private class FakeAudio : SpeechFocus.Audio {
        var held = false
        var abandons = 0

        override fun request() {
            held = true
        }

        override fun abandon() {
            if (held) abandons++
            held = false
        }
    }

    private val audio = FakeAudio()
    private val focus = SpeechFocus(audio)

    @Test
    fun wordsGiveFocusBackWhenDone() {
        focus.claim("5.8#1")
        assertTrue(audio.held)
        focus.release("5.8#1")
        assertFalse(audio.held)
    }

    @Test
    fun stoppedBeforeTheFirstWordStillGivesFocusBack() {
        // stop() during the lead-in: the words never start, and onStop is their only callback.
        focus.claim("5.8#1")
        focus.release("5.8#1")
        assertFalse(audio.held)
        assertEquals(1, audio.abandons)
    }

    @Test
    fun theEarlierUtterancesLateCallbackKeepsTheNextOnesFocus() {
        // A is flushed by B, or finishes just as B is queued; its callback arrives during B's
        // lead-in and must not drop the focus B is waiting on.
        focus.claim("5.8#1")
        focus.claim("5.9#2")
        focus.release("5.8#1")
        assertTrue(audio.held)
        focus.release("5.9#2")
        assertFalse(audio.held)
    }

    @Test
    fun sameValueTwiceIsTwoOwners() {
        // Two announcements of the same value: only the ids, not the text, tell them apart.
        focus.claim("5.8#1")
        focus.claim("5.8#2")
        focus.release("5.8#1")
        assertTrue(audio.held)
    }

    @Test
    fun aRepeatedOrStaleReleaseDoesNothing() {
        focus.claim("5.8#1")
        focus.release("5.8#1")
        focus.claim("5.9#2")
        focus.release("5.8#1")
        assertTrue(audio.held)
        focus.release(null)
        assertTrue(audio.held)
    }
    @Test fun deferredAlarmSpeechTakesFocusBeforeAlarmRelease() {
        focus.claim("alarm#1", false)
        assertFalse(audio.held)
        val pending = focus.handoff(false) { assertTrue(audio.held) }
        assertEquals(null, pending)
        focus.release("alarm#1")
        assertFalse(audio.held)
    }

    @Test fun closingReservationBridgesTheDelayAndOldCompletion() {
        focus.claim("opening#1", false)
        val pending = focus.handoff(true) { assertTrue(audio.held) }!!
        focus.release("opening#1")
        assertTrue(audio.held)
        focus.claim("closing#2")
        pending.close()
        pending.close()
        assertTrue(audio.held)
        focus.release("closing#2")
        assertFalse(audio.held)
        assertEquals(1, audio.abandons)
    }

    @Test fun missingOrRejectedClosingSpeechReleasesOnlyItsReservation() {
        val pending = focus.handoff(true) { assertTrue(audio.held) }!!
        pending.close()
        assertFalse(audio.held)
        val stale = focus.handoff(true) {}!!
        focus.claim("newer#1")
        stale.close()
        assertTrue(audio.held)
        focus.release("newer#1")
        assertFalse(audio.held)
    }

    @Test fun noSpeechAndNoClosingAnnouncementDoNotTakeSpeechFocus() {
        focus.handoff(false) { assertFalse(audio.held) }
        assertFalse(audio.held)
    }

}
