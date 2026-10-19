package tk.glucodata;

/** Pure ordering policy for callbacks that are allowed to drive realtime surfaces. */
final class RealtimeReadingPolicy {
    private static final long NATIVE_CURRENT_READING_SKEW_MS = 1_000L;

    private RealtimeReadingPolicy() {
    }

    static boolean shouldDispatch(long candidateTimeMs, long inMemoryHighWaterMs, long nativeHighWaterMs) {
        if (candidateTimeMs <= 0L) {
            return false;
        }
        if (inMemoryHighWaterMs > 0L && candidateTimeMs < inMemoryHighWaterMs) {
            return false;
        }
        if (nativeHighWaterMs <= 0L || candidateTimeMs >= nativeHighWaterMs) {
            return true;
        }

        // Native storage can round the same live reading just ahead of the BLE callback timestamp.
        return nativeHighWaterMs - candidateTimeMs < NATIVE_CURRENT_READING_SKEW_MS;
    }
}
