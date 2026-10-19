package tk.glucodata;

/**
 * Which utterance the transient audio focus is being held for, kept free of AudioManager so it
 * can be unit tested.
 *
 * <p>{@link Talker} takes focus before it queues an utterance's lead-in, so focus is now held
 * while no words are playing yet. The focus itself is one global flag in {@link Notify}, and the
 * engine's progress callbacks arrive late and on any thread. Releasing on every callback would
 * let the previous utterance's onDone, or its onStop when the next one flushes it, drop the focus
 * the next one has just taken. So only the utterance that holds focus releases it.
 */
final class SpeechFocus {
    interface Audio {
        void request();

        void abandon();
    }

    private final Audio audio;
    private String owner;

    SpeechFocus(Audio audio) {
        this.audio = audio;
    }

    /** Hold focus for this utterance; whoever held it before no longer releases it. */
    synchronized void claim(String utteranceId) {
        owner = utteranceId;
        audio.request();
    }

    /**
     * The utterance finished, failed, was stopped, or was flushed before it played, or was never
     * queued at all. Gives focus back only if this utterance still holds it.
     */
    synchronized void release(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(owner)) {
            owner = null;
            audio.abandon();
        }
    }
}
