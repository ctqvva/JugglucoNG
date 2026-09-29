package tk.glucodata;

import android.graphics.Bitmap;
import android.content.Context;
import android.view.View;
import android.util.TypedValue;
import android.widget.RemoteViews;

/**
 * Binds native glucose value rows in the phone ongoing notification.
 *
 * <p>The rasterized value bitmaps ({@code drawGlucoseText}/{@code drawMultiGlucoseText})
 * are kept for Wear and alarm surfaces. On the phone the numeric value (primary plus
 * secondary/tertiary spans, and one row per peer) is bound as native text so it scales
 * with system font settings and is announced to TalkBack. Trend arrows and the chart
 * stay bounded bitmap assets.
 *
 * <p>RemoteViews compatibility: only view methods available since API 26 are used
 * ({@code setTextViewText}, {@code setTextColor}, {@code setTextViewTextSize},
 * {@code setViewVisibility}, {@code setImageViewBitmap}, {@code setContentDescription},
 * {@code addView}/{@code removeAllViews}). In particular
 * {@code TextView.setFontVariationSettings} is <em>not</em> remotely callable below API 35,
 * so IBM Plex weight is selected at inflation time by choosing one of the
 * {@code notification_value_row_{light,regular,medium}} layouts (300/400/500, the exact
 * range the settings UI offers). Static font instances preserve these weights on API 26 too. System-font weight is carried by a
 * {@link android.text.style.TypefaceSpan} ({@code sans-serif-light}/{@code sans-serif}/
 * {@code sans-serif-medium}), which survives RemoteViews parceling; no custom spans are
 * used. Numeric text is never reformatted here: callers pass the resolved formatter
 * output (including {@code ForegroundColorSpan}/{@code RelativeSizeSpan} for the
 * secondary/tertiary parts) and peer {@code ValueItem.text} verbatim.
 */
final class NotificationValueViews {
    private NotificationValueViews() {
    }

    /** Settings UI offers 300/400/500; anything else falls back to regular. */
    static int sanitizeFontWeight(int fontWeight) {
        if (fontWeight == 300 || fontWeight == 500) {
            return fontWeight;
        }
        return 400;
    }

    /** Settings slider range is 0.6..1.5; garbage falls back to 1.0. */
    static float sanitizeFontScale(float fontScale) {
        if (!Float.isFinite(fontScale)) {
            return 1.0f;
        }
        if (fontScale < 0.6f || fontScale > 1.5f) {
            return 1.0f;
        }
        return fontScale;
    }

    /** Inflation-time row layout for the sanitized IBM Plex weight. */
    static int rowLayoutForWeight(int fontWeight) {
        switch (sanitizeFontWeight(fontWeight)) {
            case 300:
                return R.layout.notification_value_row_light;
            case 500:
                return R.layout.notification_value_row_medium;
            case 400:
            default:
                return R.layout.notification_value_row_regular;
        }
    }

    /** System-font family backing a sanitized weight; applied as a TypefaceSpan. */
    static String systemFamilyForWeight(int fontWeight) {
        switch (sanitizeFontWeight(fontWeight)) {
            case 300:
                return "sans-serif-light";
            case 500:
                return "sans-serif-medium";
            case 400:
            default:
                return "sans-serif";
        }
    }

    /**
     * Styles resolved numeric text for the value row. The IBM path returns the text
     * untouched (the row layout's {@code fontFamily} applies); the system path wraps it
     * with the weight-mapped {@code TypefaceSpan}. Secondary/tertiary
     * {@code ForegroundColorSpan}/{@code RelativeSizeSpan} ranges are preserved.
     */
    static CharSequence styledValueText(CharSequence valueText, boolean useSystemFont, int fontWeight) {
        final CharSequence safe = valueText == null ? "" : valueText;
        if (!useSystemFont || safe.length() == 0) {
            return safe;
        }
        final android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(safe);
        styled.setSpan(new android.text.style.TypefaceSpan(systemFamilyForWeight(fontWeight)),
                0, styled.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return styled;
    }

    /** Peer text is passed through verbatim: no reformatting, no unit assumptions. */
    static String peerText(String peerText) {
        return peerText == null ? "" : peerText;
    }

    static float primaryTextSizeSp(boolean expanded, float fontScale) {
        final float base = expanded ? 28.0f : 24.0f;
        return base * sanitizeFontScale(fontScale);
    }

    static float peerTextSizeSp(boolean expanded, float fontScale) {
        return primaryTextSizeSp(expanded, fontScale) * 0.72f;
    }

    static void bindValueRow(RemoteViews row, CharSequence text, int color, float textSizeSp) {
        row.setTextViewText(R.id.notification_value_text, text == null ? "" : text);
        row.setTextColor(R.id.notification_value_text, color);
        row.setTextViewTextSize(R.id.notification_value_text, TypedValue.COMPLEX_UNIT_SP, textSizeSp);
    }

    static void bindRowArrow(RemoteViews row, Bitmap arrowBitmap) {
        if (arrowBitmap != null) {
            row.setViewVisibility(R.id.notification_value_arrow, android.view.View.VISIBLE);
            row.setImageViewBitmap(R.id.notification_value_arrow, arrowBitmap);
        } else {
            row.setViewVisibility(R.id.notification_value_arrow, android.view.View.GONE);
        }
    }

    static void clearValueRows(RemoteViews parent, int containerId) {
        parent.removeAllViews(containerId);
    }

    static void addValueRow(RemoteViews parent, int containerId, RemoteViews row) {
        parent.addView(containerId, row);
    }
    /** Compact custom content may have only 48dp of height. Expanded text keeps
     * the full preference/accessibility scale; compact prioritizes an intact value. */
    static float renderedTextSizeSp(Context context, boolean expanded, float fontScale) {
        float requested = primaryTextSizeSp(expanded, fontScale);
        if (expanded) return requested;
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, requested, metrics);
        return pixels > 32f * metrics.density ? requested * 32f * metrics.density / pixels : requested;
    }

    static CharSequence withLaneColors(CharSequence text, int secondary, int tertiary) {
        android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(text);
        android.text.style.ForegroundColorSpan[] spans = styled.getSpans(
                0, styled.length(), android.text.style.ForegroundColorSpan.class);
        java.util.Arrays.sort(spans, java.util.Comparator.comparingInt(styled::getSpanStart));
        for (int index = 0; index < spans.length; index++) {
            int start = styled.getSpanStart(spans[index]);
            int end = styled.getSpanEnd(spans[index]);
            int flags = styled.getSpanFlags(spans[index]);
            styled.removeSpan(spans[index]);
            styled.setSpan(new android.text.style.ForegroundColorSpan(index == 0 ? secondary : tertiary),
                    start, end, flags);
        }
        return styled;
    }

    static void bindStatus(Context context, RemoteViews views, boolean expanded, boolean wearable,
            CharSequence status, int color, float fontScale) {
        int id = wearable ? R.id.notification_status : R.id.notification_native_status;
        boolean visible = status != null && status.length() > 0;
        if (!wearable && !expanded) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float valuePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    renderedTextSizeSp(context, false, fontScale), metrics);
            float statusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics);
            // Leave space for font ascent/descent; expanded always retains this line.
            visible &= valuePx + statusPx <= 40f * metrics.density;
        }
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        if (visible) {
            views.setTextViewText(id, status);
            views.setTextColor(id, color);
        }
    }

    /**
     * Binds the phone native value rows: one row for the primary value (with its own
     * arrow) plus one smaller row per peer (with that peer's arrow), all as native
     * text from the resolved formatter output. The legacy raster views are hidden on
     * the phone only. Chart and arrow bitmaps are untouched by this method.
     */
    static void bindNativeValueRows(Context context, RemoteViews parent, boolean expanded, CharSequence valueText,
            int primaryColor, java.util.List<NotificationChartDrawer.ValueItem> peerItems, float rate,
            int arrowColor, boolean isMmol, float arrowSize, float fontSize, int fontWeight,
            boolean useSystemFont, boolean showArrow, boolean shadeNight) {
        final int weight = sanitizeFontWeight(fontWeight);
        final int rowLayout = rowLayoutForWeight(weight);
        final float primarySp = renderedTextSizeSp(context, expanded, fontSize);
        final float peerSp = primarySp * 0.72f;
        final String packageName = context.getPackageName();
        final int baseTextColor = shadeNight ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        clearValueRows(parent, R.id.notification_value_container);
        final RemoteViews primaryRow = new RemoteViews(packageName, rowLayout);
        bindValueRow(primaryRow,
                styledValueText(valueText, useSystemFont, weight),
                primaryColor, primarySp);
        bindRowArrow(primaryRow,
                showArrow ? NotificationChartDrawer.drawArrow(context, rate, isMmol, arrowColor, arrowSize)
                        : null);
        addValueRow(parent, R.id.notification_value_container, primaryRow);
        if (expanded && peerItems != null) {
            for (NotificationChartDrawer.ValueItem item : peerItems) {
                if (item == null || item.text == null || item.text.isEmpty()) {
                    continue;
                }
                final int peerColor = SensorVisuals.blendArgb(
                        baseTextColor, item.color, SensorVisuals.PEER_TEXT_BLEND);
                final RemoteViews peerRow = new RemoteViews(packageName, rowLayout);
                bindValueRow(peerRow,
                        styledValueText(
                                peerText(item.text), useSystemFont, weight),
                        peerColor, peerSp);
                bindRowArrow(peerRow,
                        (showArrow && !Float.isNaN(item.rate))
                                ? NotificationChartDrawer.drawArrow(context, item.rate, isMmol,
                                        peerColor, arrowSize)
                                : null);
                addValueRow(parent, R.id.notification_value_container, peerRow);
            }
        }
        parent.setViewVisibility(R.id.notification_value_container, View.VISIBLE);
        parent.setViewVisibility(R.id.notification_legacy_value_row, View.GONE);
    }

}
