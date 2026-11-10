package tk.glucodata;

import android.graphics.Bitmap;

/**
 * Content hash of everything that goes into an ongoing glucose notification, so a
 * deferred refresh that would repost identical content can skip the post. Listener
 * apps receive every post of the same ID, even with setOnlyAlertOnce, and some
 * forward each one. 0 means "unknown" and never matches. Public only because the
 * startup-restore test harness compiles Notify code in its own class loader.
 */
public final class NotificationFingerprint {
    private long hash = 1125899906842597L;

    public NotificationFingerprint add(long value) {
        hash = 31L * hash + (value ^ (value >>> 32));
        return this;
    }

    public NotificationFingerprint add(boolean value) {
        return add(value ? 1L : 2L);
    }

    public NotificationFingerprint add(float value) {
        return add((long) Float.floatToIntBits(value));
    }

    public NotificationFingerprint add(CharSequence text) {
        if (text == null) return add(-1L);
        final String plain = text.toString();
        return add((long) plain.length()).add((long) plain.hashCode());
    }

    public NotificationFingerprint add(Bitmap bitmap) {
        if (bitmap == null) return add(-2L);
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        add((long) width).add((long) height);
        final int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            add((long) java.util.Arrays.hashCode(row));
        }
        return this;
    }

    /** Only the notification's own presentation preferences: other keys in the shared
     * file change for unrelated reasons and would defeat the comparison. */
    public NotificationFingerprint addPresentationPrefs(android.content.SharedPreferences prefs) {
        long sum = 0L;
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            final String key = entry.getKey();
            if (key == null || !(key.startsWith("notification_") || key.startsWith("glucose_")
                    || key.startsWith("delta_") || key.startsWith("periodic_glucose_"))) {
                continue;
            }
            // Order-independent: the map's iteration order is unspecified.
            sum += key.hashCode() * 31L + java.util.Objects.hashCode(entry.getValue());
        }
        return add(sum);
    }

    public long value() {
        return hash == 0L ? 1L : hash;
    }
}
