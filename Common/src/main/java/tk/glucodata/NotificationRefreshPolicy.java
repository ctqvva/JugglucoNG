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

    /**
     * A deferred refresh may skip its post only when its content is known (non-zero
     * fingerprint) and equals what the same owner last published. A new service, a
     * cancel or any unfingerprinted publication in between resets the record to 0.
     */
    static boolean isUnchangedPublication(long fingerprint, long publishedFingerprint,
            Object owner, Object publishedOwner) {
        return fingerprint != 0L && fingerprint == publishedFingerprint && owner == publishedOwner;
    }

    /**
     * The interactive follow-up only exists to catch the live reading's Room write. A
     * data-changed refresh already queued renders the same newest state moments later,
     * so the follow-up would be a second post of the same reading.
     */
    static boolean shouldRunInteractiveFollowUp(long pendingDataRefreshAtMs) {
        return pendingDataRefreshAtMs == 0L;
    }

    static boolean shouldSchedulePublicationRetry(long retryGeneration, long generation) {
        return generation != 0L && retryGeneration != generation;
    }
}
