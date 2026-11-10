package tk.glucodata;

/**
 * Which utterance the transient audio focus is being held for, kept free of AudioManager so it
 * can be unit tested.
 *
 * <p>{@link Talker} takes focus before it queues an utterance's lead-in, so focus is now held
 * while no words are playing yet. Speech has its own focus request, separate from alarms, and the
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
    private final java.util.Set<Hold> pending = new java.util.HashSet<>();

    SpeechFocus(Audio audio) {
        this.audio = audio;
    }

    /** Hold focus for this utterance; whoever held it before no longer releases it. */
    synchronized void claim(String utteranceId) {
        claim(utteranceId, true);
    }

    /** Alarm speech records its owner now, but leaves the alarm's stronger request on top. */
    synchronized void claim(String utteranceId, boolean requestNow) {
        owner = utteranceId;
        if (requestNow) audio.request();
    }

    /** Reserve the gap before a closing announcement is queued; it is not an utterance owner. */
    synchronized Hold reserve() {
        Hold hold = new Hold();
        pending.add(hold);
        return hold;
    }

    synchronized void requestIfOwned() {
        if (owner != null || !pending.isEmpty()) audio.request();
    }

    synchronized Hold handoff(boolean closingAnnouncement, Runnable releaseAlarm) {
        Hold hold = closingAnnouncement ? reserve() : null;
        requestIfOwned();
        try {
            releaseAlarm.run();
            return hold;
        } catch (RuntimeException e) {
            if (hold != null) hold.close();
            throw e;
        }
    }

    final class Hold implements AutoCloseable {
        @Override public void close() {
            synchronized (SpeechFocus.this) {
                if (pending.remove(this) && pending.isEmpty() && owner == null) audio.abandon();
            }
        }
    }

    /**
     * The utterance finished, failed, was stopped, or was flushed before it played, or was never
     * queued at all. Gives focus back only if this utterance still holds it.
     */
    synchronized void release(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(owner)) {
            owner = null;
            if (pending.isEmpty()) audio.abandon();
        }
    }
}
