package tk.glucodata;

/** Pure timing rules for the phone's ongoing glucose notification. */
final class NotificationRefreshPolicy {
    private NotificationRefreshPolicy() {}

    static boolean isFresh(long readingTimeMs, long nowMs, long freshnessMs) {
        return readingTimeMs > 0L
                && freshnessMs > 0L
                && (nowMs <= readingTimeMs || nowMs - readingTimeMs <= freshnessMs);
    }

    static long deadlineDelayMs(long readingTimeMs, long nowMs, long freshnessMs) {
        if (readingTimeMs <= 0L || freshnessMs <= 0L) {
            return 0L;
        }
        final long deadline = readingTimeMs > Long.MAX_VALUE - freshnessMs
                ? Long.MAX_VALUE
                : readingTimeMs + freshnessMs;
        if (deadline < nowMs || deadline == Long.MAX_VALUE) {
            return deadline == Long.MAX_VALUE && nowMs < Long.MAX_VALUE
                    ? Long.MAX_VALUE - nowMs : 0L;
        }
        // DisplayDataState treats the exact threshold as fresh. Reconcile one
        // millisecond later so the deadline agrees with that shared contract.
        return deadline - nowMs == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - nowMs + 1L;
    }

    /**
     * A pending data refresh keeps its first deadline. Repeated requests coalesce
     * without moving the work farther into the future.
     */
    static long boundedDebounceAt(long pendingAtMs, long nowMs, long delayMs) {
        if (pendingAtMs != 0L) {
            return pendingAtMs;
        }
        final long safeDelay = Math.max(0L, delayMs);
        return nowMs > Long.MAX_VALUE - safeDelay ? Long.MAX_VALUE : nowMs + safeDelay;
    }

    static boolean matchesRetainedDisplay(String activeSerial, String snapshotSerial,
            boolean currentMmol, boolean snapshotMmol, int currentMode, int snapshotMode) {
        return activeSerial != null && !activeSerial.isEmpty()
                && activeSerial.equals(snapshotSerial)
                && currentMmol == snapshotMmol
                && currentMode == snapshotMode;
    }

    static boolean mayCancelForOwner(Object requestedOwner, Object currentOwner) {
        return requestedOwner == null || requestedOwner == currentOwner;
    }

    static boolean shouldPinOngoingGlucose(boolean wearable, boolean glucoseChannel,
            boolean onlyAlertOnce, boolean alertWatch) {
        return !wearable && glucoseChannel && (onlyAlertOnce || alertWatch);
    }

    static boolean shouldSchedulePublicationRetry(long retryGeneration, long generation) {
        return generation != 0L && retryGeneration != generation;
    }
}
