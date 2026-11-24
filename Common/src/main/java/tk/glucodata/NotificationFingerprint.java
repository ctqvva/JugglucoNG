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

    /** Chart values closer than this are the same pixel: 0.02 mmol/L, a third of a mg/dL. */
    private static final float STEPS_PER_MMOL = 50f;
    private static final float STEPS_PER_MGDL = 3f;

    private long chartStep(float value, boolean isMmol) {
        if (!Float.isFinite(value)) return -1L;
        return Math.round(value * (isMmol ? STEPS_PER_MMOL : STEPS_PER_MGDL));
    }

    /**
     * The readings a chart draws, one per minute. A live reading and its stored row are the
     * same minute of the same line, whatever their seconds.
     */
    public NotificationFingerprint addPoints(java.util.List<GlucosePoint> points, boolean isMmol) {
        if (points == null) return add(-3L);
        for (int i = 0; i < points.size(); i++) {
            final GlucosePoint point = points.get(i);
            final long minute = point.timestamp / 60_000L;
            if (i + 1 < points.size() && points.get(i + 1).timestamp / 60_000L == minute) continue;
            add(minute).add(chartStep(point.value, isMmol)).add(chartStep(point.rawValue, isMmol))
                    .add(chartStep(point.sealedDisplayValue, isMmol)).add((long) point.sealedDisplayViewMode);
            if (point.uncertainty != null) {
                add(chartStep(point.uncertainty.getLower(), isMmol)).add(chartStep(point.uncertainty.getUpper(), isMmol));
            }
        }
        return add(-4L);
    }

    /** The resolved lines: calibrated values, main-sensor ownership and lanes. */
    public NotificationFingerprint addModel(tk.glucodata.chart.HistoryChartModel model, boolean isMmol) {
        if (model == null) return add(-6L);
        for (tk.glucodata.chart.ChartSeriesModel series : model.getSeries()) {
            add(series.getSensorId()).add(series.isPrimary()).add((long) series.getViewMode())
                    .add((long) series.getColorArgb());
            addRuns(series.getRuns(), isMmol);
            for (tk.glucodata.chart.ChartLane lane : series.getSecondaryLanes()) {
                add((long) lane.getKind().ordinal());
                addRuns(lane.getRuns(), isMmol);
            }
        }
        return this;
    }

    private void addRuns(java.util.List<tk.glucodata.chart.ChartRun> runs, boolean isMmol) {
        for (tk.glucodata.chart.ChartRun run : runs) {
            add((long) run.getLook().ordinal());
            final java.util.List<tk.glucodata.chart.ChartPointModel> points = run.getPoints();
            for (int i = 0; i < points.size(); i++) {
                final tk.glucodata.chart.ChartPointModel point = points.get(i);
                final long minute = point.getTimestamp() / 60_000L;
                if (i + 1 < points.size() && points.get(i + 1).getTimestamp() / 60_000L == minute) continue;
                add(minute).add(chartStep(point.getValue(), isMmol));
            }
        }
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
