package tk.glucodata;

import android.speech.tts.TextToSpeech;

/**
 * What {@link Talker} queues ahead of the words of one utterance, kept free of a live
 * TextToSpeech so it can be unit tested.
 *
 * <p>Reported: "5,8" heard as just "8", mostly in the daytime. An idle speaker amplifier or
 * Bluetooth link wakes up on the first audio it is handed and swallows the start of it, and
 * whatever was holding audio focus has not yet paused or ducked when the first word starts. So
 * the words go out behind a short lead-in of silent audio - real audio, unlike
 * playSilentUtterance(), which only waits and never opens the output - and, when the user asked
 * for it, a chime that tells them a value is coming.
 */
final class LeadIn {
    /**
     * Utterance id of the lead-in and the chime. The progress listener ignores it, so audio
     * focus follows the words alone.
     */
    static final String ID = "lead_in";
    static final String SILENCE_EARCON = "[lead_in]";
    static final String CHIME_EARCON = "[glucose_chime]";

    interface Engine {
        int playEarcon(String earcon, int queueMode, String utteranceId);

        int speak(String text, int queueMode, String utteranceId);
    }

    /**
     * Queue the lead-in, the chime if wanted, then the words. The first item to reach the engine
     * flushes what was queued before, as the words alone used to.
     *
     * @param wordsId utterance id of the words, which the progress listener tracks focus by.
     * @return the engine's result for the words. The lead-in and chime are best effort: if they
     *         are refused the words still go out, flushing in their place.
     */
    static int queue(Engine engine, String message, String wordsId, boolean chime) {
        int queueMode = TextToSpeech.QUEUE_FLUSH;
        if (engine.playEarcon(SILENCE_EARCON, queueMode, ID) == TextToSpeech.SUCCESS) {
            queueMode = TextToSpeech.QUEUE_ADD;
        }
        if (chime && engine.playEarcon(CHIME_EARCON, queueMode, ID) == TextToSpeech.SUCCESS) {
            queueMode = TextToSpeech.QUEUE_ADD;
        }
        return engine.speak(message, queueMode, wordsId);
    }

    static boolean isLeadIn(String utteranceId) {
        return ID.equals(utteranceId);
    }
}
