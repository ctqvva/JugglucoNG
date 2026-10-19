package tk.glucodata;

/**
 * Testable policy for the truthful foreground-service startup subset (N1).
 *
 * <p>Pure Java with no Android dependencies so the rules can be exercised on the JVM:
 * the notification header must show the timestamp of the reading actually displayed
 * (never render time, never a fabricated value), and startup restoration classifies
 * the actual resolved timestamp without relabelling stale history as fresh.
 *
 * <p>Restore lifecycle (ticket epochs, atomic check-and-publish, stop invalidation)
 * lives in {@code Notify} so the real publication paths can be harnessed; this class
 * only classifies and selects timestamps. Shared resolver semantics, units,
 * calibration and alert delivery are untouched.
 */
public final class NotificationStartupPolicy {
    private NotificationStartupPolicy() {
    }

    /** Outcome of classifying the actual resolved reading timestamp at restore time. */
    public enum RestoreOutcome {
        FRESH_READING,
        STALE_READING,
        AWAITING_DATA,
        NO_SENSOR
    }

    /**
     * Classify the restore outcome from the actual resolved timestamps via the shared
     * {@link DisplayDataState} policy. Callers pass the timestamp of the reading that
     * will actually be rendered (or 0 when nothing renderable was resolved), never a
     * max over sources that are not displayed. All arguments are explicit so loading
     * this class never touches Android or native initializers.
     */
    public static RestoreOutcome classifyRestore(boolean sensorPresent, long currentMillis,
            long historyMillis, long nowMillis, long freshnessWindowMillis) {
        DisplayDataState.Status status = DisplayDataState.resolve(sensorPresent, currentMillis,
                historyMillis, freshnessWindowMillis, nowMillis);
        switch (status.getKind()) {
            case FRESH:
                return RestoreOutcome.FRESH_READING;
            case STALE:
                return RestoreOutcome.STALE_READING;
            case NO_SENSOR:
                return RestoreOutcome.NO_SENSOR;
            case AWAITING_DATA:
            default:
                return RestoreOutcome.AWAITING_DATA;
        }
    }

    /**
     * Header timestamp for reading-bearing ongoing content: the reading actually
     * displayed, including the fallback/multi-source primary resolution. Returns 0
     * when no valid reading is displayed; the caller must then hide the header
     * timestamp instead of fabricating one (not render time, not the blindly passed
     * incoming argument).
     */
    public static long resolveHeaderWhenMillis(long displayedReadingMillis) {
        return displayedReadingMillis > 0L ? displayedReadingMillis : 0L;
    }

    /** Whether a header timestamp may be shown for the displayed reading. */
    public static boolean showHeaderWhen(long displayedReadingMillis) {
        return displayedReadingMillis > 0L;
    }
}
