package tk.glucodata

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LeadIn decides what reaches the TTS queue ahead of the words, so the first word of "5,8" is
 * not lost to a waking speaker or to audio that has not ducked yet.
 */
class LeadInTests {

    private data class Queued(val item: String, val queueMode: Int, val utteranceId: String)

    /** Records what was queued; earcons named in [refused] are rejected by the engine. */
    private class FakeEngine(
        private val refused: Set<String> = emptySet(),
        private val wordsResult: Int = TextToSpeech.SUCCESS,
    ) : LeadIn.Engine {
        val queued = mutableListOf<Queued>()

        override fun playEarcon(earcon: String, queueMode: Int, utteranceId: String): Int {
            if (earcon in refused) return TextToSpeech.ERROR
            queued += Queued(earcon, queueMode, utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
            if (wordsResult == TextToSpeech.SUCCESS) queued += Queued(text, queueMode, utteranceId)
            return wordsResult
        }
    }

    @Test
    fun wordsFollowTheLeadInWhichFlushesTheQueue() {
        val engine = FakeEngine()
        assertEquals(TextToSpeech.SUCCESS, LeadIn.queue(engine, "5.8", "5.8#1", false))
        assertEquals(
            listOf(
                Queued(LeadIn.SILENCE_EARCON, TextToSpeech.QUEUE_FLUSH, LeadIn.ID),
                Queued("5.8", TextToSpeech.QUEUE_ADD, "5.8#1"),
            ),
            engine.queued,
        )
    }

    @Test
    fun chimeSitsBetweenTheLeadInAndTheWords() {
        val engine = FakeEngine()
        LeadIn.queue(engine, "5.8", "5.8#1", true)
        assertEquals(
            listOf(
                Queued(LeadIn.SILENCE_EARCON, TextToSpeech.QUEUE_FLUSH, LeadIn.ID),
                Queued(LeadIn.CHIME_EARCON, TextToSpeech.QUEUE_ADD, LeadIn.ID),
                Queued("5.8", TextToSpeech.QUEUE_ADD, "5.8#1"),
            ),
            engine.queued,
        )
    }

    @Test
    fun refusedLeadInStillLetsTheWordsFlushTheOldQueue() {
        // QUEUE_ADD here would leave a stale announcement playing ahead of the new value.
        val engine = FakeEngine(refused = setOf(LeadIn.SILENCE_EARCON))
        assertEquals(TextToSpeech.SUCCESS, LeadIn.queue(engine, "5.8", "5.8#1", false))
        assertEquals(listOf(Queued("5.8", TextToSpeech.QUEUE_FLUSH, "5.8#1")), engine.queued)
    }

    @Test
    fun chimeTakesOverTheFlushWhenTheLeadInIsRefused() {
        val engine = FakeEngine(refused = setOf(LeadIn.SILENCE_EARCON))
        LeadIn.queue(engine, "5.8", "5.8#1", true)
        assertEquals(
            listOf(
                Queued(LeadIn.CHIME_EARCON, TextToSpeech.QUEUE_FLUSH, LeadIn.ID),
                Queued("5.8", TextToSpeech.QUEUE_ADD, "5.8#1"),
            ),
            engine.queued,
        )
    }

    @Test
    fun resultIsTheWordsNotTheLeadIn() {
        // Talker charges the separation interval and engine health on this result; a lead-in
        // that queued fine must not make refused words look spoken.
        val engine = FakeEngine(wordsResult = TextToSpeech.ERROR)
        assertEquals(TextToSpeech.ERROR, LeadIn.queue(engine, "5.8", "5.8#1", true))
    }

    @Test
    fun onlyTheLeadInIdIsIgnoredByTheProgressListener() {
        assertTrue(LeadIn.isLeadIn(LeadIn.ID))
        assertFalse(LeadIn.isLeadIn("5.8#1"))
        assertFalse(LeadIn.isLeadIn("voice_preview"))
        assertFalse(LeadIn.isLeadIn(null))
    }
}
