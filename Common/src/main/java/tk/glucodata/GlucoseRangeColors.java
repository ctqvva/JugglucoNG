package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared glucose range tones. The hue order follows AGP convention:
 * below range is red/dark red, in range is green, above range is amber/orange.
 *
 * <p>The five band colours are selectable at runtime. A {@link Palette} preset
 * supplies the base tones; optional per-band {@link Band} overrides are layered
 * on top of whichever preset is active. Resolution order for every getter is
 * <em>override &rarr; active preset &rarr; dark/light variant</em>. The getter
 * signatures are deliberately unchanged so existing call sites keep working; a
 * palette switch takes effect everywhere they are read.</p>
 */
public final class GlucoseRangeColors {
    // Muted preset (the historical default). Kept as the public constants so any
    // remaining direct reference still resolves to the original tones. Dark ==
    // light: the muted palette is intentionally identical in both themes.
    public static final int VERY_LOW = 0xFFC7655C;
    public static final int LOW = 0xFFC97970;
    public static final int IN_RANGE = 0xFF4E8A55;
    public static final int HIGH = 0xFFD0A23A;
    public static final int VERY_HIGH = 0xFFC56F33;

    public static final int VERY_LOW_DARK = VERY_LOW;
    public static final int LOW_DARK = LOW;
    public static final int IN_RANGE_DARK = IN_RANGE;
    public static final int HIGH_DARK = HIGH;
    public static final int VERY_HIGH_DARK = VERY_HIGH;

    /** Selectable colour presets. CUSTOM is a UI label for "base + overrides". */
    public enum Palette { MUTED, VIBRANT, AURORA, GDH_LIKE, CUSTOM }

    /** The five glucose bands, ordered below-range to above-range. */
    public enum Band { VERY_LOW, LOW, IN_RANGE, HIGH, VERY_HIGH }

    // Preset colour tables, indexed by Band.ordinal(). Each preset carries its
    // own dark variant; on dark surfaces saturated tones are lightened so they
    // do not glare. MUTED stays bit-identical to the light values by design.
    private static final int[] MUTED_LIGHT = { VERY_LOW, LOW, IN_RANGE, HIGH, VERY_HIGH };
    private static final int[] MUTED_DARK = MUTED_LIGHT;

    // Vibrant: a saturated traffic palette tuned for Material 3 surfaces (not the
    // raw RGB primaries GDH uses). Light leans on the 600-800 Material shades,
    // dark on the 200-400 shades so contrast holds on dark backgrounds.
    private static final int[] VIBRANT_LIGHT = {
            0xFFC62828, // VERY_LOW  – Red 800
            0xFFE53935, // LOW       – Red 600
            0xFF43A047, // IN_RANGE  – Green 600
            0xFFFFA000, // HIGH      – Amber 700
            0xFFEF6C00, // VERY_HIGH – Orange 800
    };
    private static final int[] VIBRANT_DARK = {
            0xFFEF5350, // VERY_LOW  – Red 400
            0xFFE57373, // LOW       – Red 300
            0xFF81C784, // IN_RANGE  – Green 300
            0xFFFFCA28, // HIGH      – Amber 400
            0xFFFFA726, // VERY_HIGH – Orange 400
    };

    // Aurora: cooler teal and magenta accents with warm gold/orange highs.
    private static final int[] AURORA_LIGHT = {
            0xFFAD1457, // VERY_LOW
            0xFFE05A6F, // LOW
            0xFF00897B, // IN_RANGE
            0xFFF9A825, // HIGH
            0xFFD84315, // VERY_HIGH
    };
    private static final int[] AURORA_DARK = {
            0xFFF06292, // VERY_LOW
            0xFFFF8A80, // LOW
            0xFF4DB6AC, // IN_RANGE
            0xFFFFD54F, // HIGH
            0xFFFF8A65, // VERY_HIGH
    };

    // GDH-like: GlucoDataHandler's three colour tiers mapped onto five bands
    // (in-range <- colorOK, high/low <- colorOutOfRange, very-high/very-low <-
    // colorAlarm). These are the raw primaries GDH renders on black widget
    // backgrounds, offered verbatim for switchers; dark == light on purpose.
    private static final int[] GDH_LIGHT = {
            0xFFFF0000, // VERY_LOW  – colorAlarm (red)
            0xFFFFDC00, // LOW       – colorOutOfRange (yellow)
            0xFF00FF00, // IN_RANGE  – colorOK (green)
            0xFFFFDC00, // HIGH      – colorOutOfRange (yellow)
            0xFFFF0000, // VERY_HIGH – colorAlarm (red)
    };
    private static final int[] GDH_DARK = GDH_LIGHT;

    // SharedPreferences contract (shared with the app-start init and the UI).
    public static final String PREF_FILE = "tk.glucodata_preferences";
    public static final String PREF_PALETTE = "glucose_color_palette";
    public static final String PREF_TARGET_BACKGROUND = "glucose_color_target_background";
    public static final String[] PREF_OVERRIDE_KEYS = {
            "glucose_color_very_low",
            "glucose_color_low",
            "glucose_color_in_range",
            "glucose_color_high",
            "glucose_color_very_high",
    };

    private static volatile Palette activePalette = Palette.MUTED;
    // null entry = no override for that band. Overrides apply to both themes.
    private static final Integer[] overrides = new Integer[Band.values().length];
    // null = follow the effective IN_RANGE color.
    private static volatile Integer targetBackgroundOverride;
    // Notified after any state change so the Compose layer can recompose.
    private static volatile Runnable changeListener;

    private static int[] tableFor(Palette palette, boolean darkTheme) {
        switch (palette) {
            case VIBRANT:
                return darkTheme ? VIBRANT_DARK : VIBRANT_LIGHT;
            case AURORA:
                return darkTheme ? AURORA_DARK : AURORA_LIGHT;
            case GDH_LIKE:
                return darkTheme ? GDH_DARK : GDH_LIGHT;
            case MUTED:
            case CUSTOM: // custom layers overrides on the muted base
            default:
                return darkTheme ? MUTED_DARK : MUTED_LIGHT;
        }
    }

    private static int resolve(Band band, boolean darkTheme) {
        final Integer override = overrides[band.ordinal()];
        if (override != null) {
            return override;
        }
        return tableFor(activePalette, darkTheme)[band.ordinal()];
    }

    /** Preset tone for a band, ignoring any override (used for previews/reset). */
    public static int presetColor(Palette palette, Band band, boolean darkTheme) {
        return tableFor(palette, darkTheme)[band.ordinal()];
    }

    public static Palette getPalette() {
        return activePalette;
    }

    public static void setPalette(Palette palette) {
        if (palette == null) {
            palette = Palette.MUTED;
        }
        if (palette != activePalette) {
            activePalette = palette;
            notifyChanged();
        }
    }

    public static Integer getOverride(Band band) {
        return overrides[band.ordinal()];
    }

    public static Integer getTargetBackgroundOverride() {
        return targetBackgroundOverride;
    }

    public static int targetBackground(boolean darkTheme) {
        final Integer override = targetBackgroundOverride;
        return override != null ? override : inRange(darkTheme);
    }

    public static boolean hasAnyOverride() {
        if (targetBackgroundOverride != null) {
            return true;
        }
        for (Integer override : overrides) {
            if (override != null) {
                return true;
            }
        }
        return false;
    }

    /** Set (argb) or clear (null) the override for a single band. */
    public static void setOverride(Band band, Integer argb) {
        final int idx = band.ordinal();
        if (!java.util.Objects.equals(overrides[idx], argb)) {
            overrides[idx] = argb;
            notifyChanged();
        }
    }

    public static void clearOverride(Band band) {
        setOverride(band, null);
    }

    public static void setTargetBackgroundOverride(Integer argb) {
        if (!java.util.Objects.equals(targetBackgroundOverride, argb)) {
            targetBackgroundOverride = argb;
            notifyChanged();
        }
    }

    public static void clearOverrides() {
        boolean changed = false;
        for (int i = 0; i < overrides.length; i++) {
            if (overrides[i] != null) {
                overrides[i] = null;
                changed = true;
            }
        }
        if (targetBackgroundOverride != null) {
            targetBackgroundOverride = null;
            changed = true;
        }
        if (changed) {
            notifyChanged();
        }
    }

    /** Register a callback invoked after any palette/override change. */
    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void notifyChanged() {
        final Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }

    private static Palette parsePalette(String raw) {
        if (raw != null) {
            try {
                return Palette.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Palette.MUTED;
    }

    /**
     * Load the active preset and per-band overrides from SharedPreferences.
     * Called early at app start (before the notification chart draws) so the
     * non-Compose consumers pick up the user's choice. Missing keys keep the
     * historical MUTED / no-override behaviour.
     */
    public static void initFromPrefs(Context context) {
        try {
            final SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            activePalette = parsePalette(prefs.getString(PREF_PALETTE, Palette.MUTED.name()));
            for (int i = 0; i < overrides.length; i++) {
                overrides[i] = prefs.contains(PREF_OVERRIDE_KEYS[i])
                        ? prefs.getInt(PREF_OVERRIDE_KEYS[i], 0)
                        : null;
            }
            targetBackgroundOverride = prefs.contains(PREF_TARGET_BACKGROUND)
                    ? prefs.getInt(PREF_TARGET_BACKGROUND, 0)
                    : null;
        } catch (Throwable ignored) {
            // Any failure falls back to the compiled-in muted defaults.
        }
    }

    public static final float DEFAULT_LOW_MGDL = 70.0f;
    public static final float DEFAULT_HIGH_MGDL = 180.0f;
    public static final float DEFAULT_VERY_LOW_MGDL = 54.0f;
    public static final float DEFAULT_VERY_HIGH_MGDL = 250.0f;

    public static final float DEFAULT_LOW_MMOL = 3.9f;
    public static final float DEFAULT_HIGH_MMOL = 10.0f;
    public static final float DEFAULT_VERY_LOW_MMOL = 3.0f;
    public static final float DEFAULT_VERY_HIGH_MMOL = 13.9f;

    private GlucoseRangeColors() {
    }

    public static int veryLow(boolean darkTheme) {
        return resolve(Band.VERY_LOW, darkTheme);
    }

    public static int low(boolean darkTheme) {
        return resolve(Band.LOW, darkTheme);
    }

    public static int inRange(boolean darkTheme) {
        return resolve(Band.IN_RANGE, darkTheme);
    }

    public static int high(boolean darkTheme) {
        return resolve(Band.HIGH, darkTheme);
    }

    public static int veryHigh(boolean darkTheme) {
        return resolve(Band.VERY_HIGH, darkTheme);
    }

    public static float defaultLow(boolean isMmol) {
        return isMmol ? DEFAULT_LOW_MMOL : DEFAULT_LOW_MGDL;
    }

    public static float defaultHigh(boolean isMmol) {
        return isMmol ? DEFAULT_HIGH_MMOL : DEFAULT_HIGH_MGDL;
    }

    public static float defaultVeryLow(boolean isMmol) {
        return isMmol ? DEFAULT_VERY_LOW_MMOL : DEFAULT_VERY_LOW_MGDL;
    }

    public static float defaultVeryHigh(boolean isMmol) {
        return isMmol ? DEFAULT_VERY_HIGH_MMOL : DEFAULT_VERY_HIGH_MGDL;
    }

    // Traffic-light palette for colouring the current value (and trend arrow,
    // and the notification value): green inside the target range, yellow between
    // target and alarm bounds, red beyond the alarms. This is a 3-tier system,
    // distinct from the 5 AGP bands above, but it follows the active preset too
    // so switching to Vibrant / GDH-like visibly recolours the value and arrow.
    // The MUTED constants are the historical tones, kept bit-identical.
    public static final int VALUE_IN_RANGE = 0xFF2E7D32;
    public static final int VALUE_IN_RANGE_DARK = 0xFF81C784;
    public static final int VALUE_BORDERLINE = 0xFFF9A825;
    public static final int VALUE_BORDERLINE_DARK = 0xFFFFD54F;
    public static final int VALUE_OUT = 0xFFC62828;
    public static final int VALUE_OUT_DARK = 0xFFE57373;

    // Per-preset traffic tiers: [in-range, borderline, out].
    private static final int[] MUTED_TRAFFIC_LIGHT = { VALUE_IN_RANGE, VALUE_BORDERLINE, VALUE_OUT };
    private static final int[] MUTED_TRAFFIC_DARK = { VALUE_IN_RANGE_DARK, VALUE_BORDERLINE_DARK, VALUE_OUT_DARK };
    private static final int[] VIBRANT_TRAFFIC_LIGHT = { 0xFF00A651, 0xFFFFB300, 0xFFD50000 };
    private static final int[] VIBRANT_TRAFFIC_DARK = { 0xFF69F0AE, 0xFFFFD24D, 0xFFFF5252 };
    private static final int[] AURORA_TRAFFIC_LIGHT = { 0xFF00897B, 0xFFF9A825, 0xFFAD1457 };
    private static final int[] AURORA_TRAFFIC_DARK = { 0xFF4DB6AC, 0xFFFFD54F, 0xFFF06292 };
    private static final int[] GDH_TRAFFIC_LIGHT = { 0xFF00FF00, 0xFFFFDC00, 0xFFFF0000 };
    private static final int[] GDH_TRAFFIC_DARK = GDH_TRAFFIC_LIGHT;

    private static int[] trafficFor(Palette palette, boolean darkTheme) {
        switch (palette) {
            case VIBRANT:
                return darkTheme ? VIBRANT_TRAFFIC_DARK : VIBRANT_TRAFFIC_LIGHT;
            case AURORA:
                return darkTheme ? AURORA_TRAFFIC_DARK : AURORA_TRAFFIC_LIGHT;
            case GDH_LIKE:
                return darkTheme ? GDH_TRAFFIC_DARK : GDH_TRAFFIC_LIGHT;
            case MUTED:
            case CUSTOM:
            default:
                return darkTheme ? MUTED_TRAFFIC_DARK : MUTED_TRAFFIC_LIGHT;
        }
    }

    public static int valueInRange(boolean darkTheme) {
        // The in-range tier honours an explicit IN_RANGE override so the user's
        // chosen in-range colour applies to the value too, not just the bands.
        final Integer override = overrides[Band.IN_RANGE.ordinal()];
        return override != null ? override : trafficFor(activePalette, darkTheme)[0];
    }

    public static int valueBorderline(boolean darkTheme) {
        return trafficFor(activePalette, darkTheme)[1];
    }

    public static int valueOut(boolean darkTheme) {
        return trafficFor(activePalette, darkTheme)[2];
    }

    public static int trafficColorForValue(
            float value,
            float targetLow,
            float targetHigh,
            float alarmLow,
            float alarmHigh,
            boolean darkTheme,
            boolean isMmol,
            int fallbackColor) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return fallbackColor;
        }

        float low = Float.isFinite(targetLow) && targetLow > 0.0f ? targetLow : defaultLow(isMmol);
        float highCandidate = Float.isFinite(targetHigh) && targetHigh > low ? targetHigh : defaultHigh(isMmol);
        float high = Math.max(highCandidate, low + 0.1f);
        float redLow = Float.isFinite(alarmLow) && alarmLow > 0.0f ? alarmLow : defaultVeryLow(isMmol);
        redLow = Math.min(redLow, low - 0.1f);
        float redHigh = Float.isFinite(alarmHigh) && alarmHigh > 0.0f ? alarmHigh : defaultVeryHigh(isMmol);
        redHigh = Math.max(redHigh, high + 0.1f);

        if (value <= redLow || value >= redHigh) {
            return valueOut(darkTheme);
        }
        if (value < low || value > high) {
            return valueBorderline(darkTheme);
        }
        return valueInRange(darkTheme);
    }

    public static int blend(int startColor, int endColor, float fraction) {
        float safeFraction = Math.max(0.0f, Math.min(1.0f, fraction));
        float inverse = 1.0f - safeFraction;
        int alpha = Math.round((ColorPart.alpha(startColor) * inverse) + (ColorPart.alpha(endColor) * safeFraction));
        int red = Math.round((ColorPart.red(startColor) * inverse) + (ColorPart.red(endColor) * safeFraction));
        int green = Math.round((ColorPart.green(startColor) * inverse) + (ColorPart.green(endColor) * safeFraction));
        int blue = Math.round((ColorPart.blue(startColor) * inverse) + (ColorPart.blue(endColor) * safeFraction));
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    public static int colorForValue(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            int inRangeColor,
            boolean darkTheme,
            boolean isMmol) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return inRangeColor;
        }

        float low = Float.isFinite(targetLow) && targetLow > 0.0f ? targetLow : defaultLow(isMmol);
        float highCandidate = Float.isFinite(targetHigh) && targetHigh > low ? targetHigh : defaultHigh(isMmol);
        float high = Math.max(highCandidate, low + 0.1f);
        float veryLow = Float.isFinite(veryLowThreshold) && veryLowThreshold > 0.0f
                ? veryLowThreshold
                : defaultVeryLow(isMmol);
        veryLow = Math.min(veryLow, low - 0.1f);
        float veryHigh = Float.isFinite(veryHighThreshold) && veryHighThreshold > 0.0f
                ? veryHighThreshold
                : defaultVeryHigh(isMmol);
        veryHigh = Math.max(veryHigh, high + 0.1f);

        if (value <= veryLow) {
            return veryLow(darkTheme);
        }
        if (value < low) {
            float severity = (low - value) / Math.max(0.1f, low - veryLow);
            return blend(low(darkTheme), veryLow(darkTheme), severity);
        }
        if (value >= veryHigh) {
            return veryHigh(darkTheme);
        }
        if (value > high) {
            float severity = (value - high) / Math.max(0.1f, veryHigh - high);
            return blend(high(darkTheme), veryHigh(darkTheme), severity);
        }
        return inRangeColor;
    }

    private static final class ColorPart {
        static int alpha(int color) {
            return (color >>> 24) & 0xFF;
        }

        static int red(int color) {
            return (color >>> 16) & 0xFF;
        }

        static int green(int color) {
            return (color >>> 8) & 0xFF;
        }

        static int blue(int color) {
            return color & 0xFF;
        }
    }
}
