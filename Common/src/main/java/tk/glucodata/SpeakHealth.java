package tk.glucodata;

/**
 * Engine-health bookkeeping for {@link Talker}, kept free of Android types so it can be unit
 * tested.
 *
 * <p>{@code Talker.istalking()} only proves the static reference is non-null. A talker whose
 * TextToSpeech got unbound (com.google.android.tts updating in the background does that
 * permanently until the object is reconstructed) still passes it. This class turns the results
 * of real speak attempts into a "dead, recreate me" signal, and rate-limits how often that
 * signal may tear the engine down.
 */
final class SpeakHealth {
    /** Consecutive refused utterances on a bound engine before it counts as dead. */
    static final int REINIT_FAILURE_THRESHOLD = 2;
    /**
     * Minimum spacing between health-driven recreates. Without it a permanently unusable TTS
     * service (none installed, or one that never binds) would be reconstructed every couple of
     * readings forever. Explicit user actions and the talker==null path are not subject to it.
     */
    static final long RECREATE_FLOOR_MS = 10 * 60_000L;

    private volatile int consecutiveFailures = 0;

    /**
     * Record the outcome of one speak attempt.
     *
     * @param accepted    whether the engine queued the utterance
     * @param engineReady whether onInit had already succeeded on this engine. A refusal before
     *                    that is the normal "not bound yet" window of an engine that is still
     *                    starting up, not a health signal; counting it would make two quick
     *                    utterances at boot destroy the engine that was about to bind.
     */
    void record(boolean accepted, boolean engineReady) {
        if (accepted) {
            consecutiveFailures = 0;
        } else if (engineReady) {
            consecutiveFailures++;
        }
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** True once repeated refusals on a bound engine show it must be reconstructed. */
    boolean needsReinit() {
        return consecutiveFailures >= REINIT_FAILURE_THRESHOLD;
    }

    /**
     * Whether a health-driven recreate may happen now, given when the last one happened
     * ({@code 0} for never).
     */
    static boolean recreateAllowed(long lastRecreateMs, long nowMs) {
        return lastRecreateMs == 0L || nowMs - lastRecreateMs >= RECREATE_FLOOR_MS;
    }
}
