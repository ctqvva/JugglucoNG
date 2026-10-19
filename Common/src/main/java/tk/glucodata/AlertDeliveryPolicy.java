package tk.glucodata;

import java.util.Locale;

/**
 * Pure alert delivery rules shared by the Java notification bridge and tests.
 *
 * Alarm mode is a full-screen alarm-window contract. A notification is only a
 * fallback when neither the direct launch nor queued backup can be established.
 * Both mode always keeps the user's explicitly selected second surface.
 */
public final class AlertDeliveryPolicy {
    public static final String NOTIFICATION_ONLY = "NOTIFICATION_ONLY";
    public static final String SYSTEM_ALARM = "SYSTEM_ALARM";
    public static final String BOTH = "BOTH";

    private AlertDeliveryPolicy() {
    }

    public static String normalize(String deliveryMode) {
        if (deliveryMode == null) {
            return NOTIFICATION_ONLY;
        }
        final String mode = deliveryMode.toUpperCase(Locale.ROOT);
        if ("ALARM".equals(mode) || SYSTEM_ALARM.equals(mode)) {
            return SYSTEM_ALARM;
        }
        if (BOTH.equals(mode)) {
            return BOTH;
        }
        return NOTIFICATION_ONLY;
    }

    public static boolean shouldAttemptAlarmWindow(String normalizedMode) {
        return SYSTEM_ALARM.equals(normalizedMode) || BOTH.equals(normalizedMode);
    }

    public static boolean shouldAttachFullScreenIntent(String normalizedMode) {
        return shouldAttemptAlarmWindow(normalizedMode);
    }

    public static boolean shouldPostAlertNotification(String normalizedMode, boolean alarmWindowQueued) {
        if (BOTH.equals(normalizedMode) || NOTIFICATION_ONLY.equals(normalizedMode)) {
            return true;
        }
        return !alarmWindowQueued;
    }

    public static boolean shouldUseAlarmAudioStream(String normalizedMode, boolean disturb) {
        return disturb || shouldAttemptAlarmWindow(normalizedMode);
    }

    // --- Quiet window -------------------------------------------------------
    //
    // A quiet window is a temporary, self-expiring reduction of alarm *output*:
    // it may only take sound (and, in notification-only mode, vibration) away.
    // Notification and full-screen surfaces, thresholds, retries and episodes
    // are not its business. The safety rule lives here so it cannot be lost in
    // the UI: a silenced alarm that stays active, unacknowledged, past the
    // breakthrough time sounds as if there were no window. The scope setting
    // narrows that to the very high — but a hypo (LOW, VERY_LOW) always breaks
    // through, whatever the scope: the repo's hypo policy caps a delayed hypo
    // sound tightly (AlertConfig.maxSoundDelaySecondsFor), and a quiet window
    // may not turn that into a whole silent night.

    /** Sound off, vibration, notification and full-screen alarm stay. The default. */
    public static final String QUIET_VIBRATE_ONLY = "vibrate_only";
    /** Sound and vibration off; notification and full-screen alarm stay. */
    public static final String QUIET_NOTIFICATION_ONLY = "notification_only";

    private static final int LOW_KIND = 0; // AlertType.LOW.id
    private static final int VERY_LOW_KIND = 5; // AlertType.VERY_LOW.id
    private static final int VERY_HIGH_KIND = 6; // AlertType.VERY_HIGH.id

    /** A hypo kind. Custom alerts deliver as kind 0 too; a custom low is a low. */
    public static boolean isHypoKind(int kind) {
        return kind == LOW_KIND || kind == VERY_LOW_KIND;
    }

    public static String normalizeQuietMode(String quietMode) {
        if (quietMode == null) {
            return QUIET_VIBRATE_ONLY;
        }
        final String mode = quietMode.toLowerCase(Locale.ROOT);
        if (QUIET_NOTIFICATION_ONLY.equals(mode)) {
            return QUIET_NOTIFICATION_ONLY;
        }
        return QUIET_VIBRATE_ONLY;
    }

    /**
     * A silenced alarm that has been active since {@code silencedSinceMs} breaks
     * through once {@code breakthroughMs} has passed. Zero or negative inputs mean
     * "no silenced episode" or "no breakthrough" and never break through.
     */
    public static boolean quietWindowBreaksThrough(long silencedSinceMs, long nowMs, long breakthroughMs) {
        if (silencedSinceMs <= 0L || breakthroughMs <= 0L) {
            return false;
        }
        return nowMs - silencedSinceMs >= breakthroughMs;
    }

    /** The breakthrough applies to every silenced alarm. The default. */
    public static final String BREAKTHROUGH_ALL = "all";
    /**
     * The breakthrough applies to the very high only, besides the hypos that
     * always have it. Every other silenced alarm stays quiet for the whole window.
     */
    public static final String BREAKTHROUGH_VERY_ONLY = "very_only";

    public static String normalizeBreakthroughScope(String scope) {
        if (scope == null) {
            return BREAKTHROUGH_ALL;
        }
        return BREAKTHROUGH_VERY_ONLY.equals(scope.toLowerCase(Locale.ROOT)) ? BREAKTHROUGH_VERY_ONLY
                : BREAKTHROUGH_ALL;
    }

    /**
     * Whether a silenced alarm of this kind may break through at all. A hypo
     * always may. Under {@link #BREAKTHROUGH_VERY_ONLY} the very high does too and
     * everything else stays quiet for the whole window.
     */
    public static boolean quietWindowBreakthroughAppliesTo(int kind, String scope) {
        if (isHypoKind(kind)) {
            return true;
        }
        if (BREAKTHROUGH_VERY_ONLY.equals(normalizeBreakthroughScope(scope))) {
            return kind == VERY_HIGH_KIND;
        }
        return true;
    }

    /** Kind-awareness lives in {@link #quietWindowBreakthroughAppliesTo}; this is the outcome. */
    public static boolean shouldSilenceSound(boolean windowActive, boolean breakThrough) {
        return windowActive && !breakThrough;
    }

    public static boolean shouldSuppressVibration(boolean windowActive, String quietMode, boolean breakThrough) {
        return shouldSilenceSound(windowActive, breakThrough)
                && QUIET_NOTIFICATION_ONLY.equals(normalizeQuietMode(quietMode));
    }
}
