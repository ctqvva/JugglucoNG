package tk.glucodata;

import android.graphics.Bitmap;
import android.content.Context;
import android.view.View;
import android.util.TypedValue;
import android.widget.RemoteViews;

/** Phone value strip. System text inherits the platform notification appearance.
 * Bundled IBM Plex is rasterized only for the value glyphs: RemoteViews hosts use
 * a restricted context, where TextView intentionally cannot load app font resources.
 * Chart/status/layout remain independent, and every value retains accessible text. */
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

    /** Inflation-time system text weight; preserve the OEM notification font family. */
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

    static String systemFontFamily(Context context) {
        // This is the same overridable platform resource used by DeviceDefault's
        // notification title style (Google Sans on Pixel). Material's public
        // notification title style alone still explicitly selects sans-serif-medium.
        android.content.res.Resources resources = context.getResources();
        int id = resources.getIdentifier("config_headlineFontFamily", "string", "android");
        return id == 0 ? null : resources.getString(id);
    }

    static CharSequence styledValueText(Context context, CharSequence valueText, boolean useSystemFont, int fontWeight) {
        final CharSequence safe = valueText == null ? "" : valueText;
        if (!useSystemFont || safe.length() == 0) return safe;
        return styledSystemText(safe, systemFontFamily(context), fontWeight);
    }

    static CharSequence styledSystemText(CharSequence text, String family, int fontWeight) {
        final android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(text);
        if (family != null && !family.isEmpty()) {
            // Named system families survive process boundaries; Typeface objects do not.
            styled.setSpan(new android.text.style.TypefaceSpan(family), 0, styled.length(),
                    android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL,
                        sanitizeFontWeight(fontWeight) - 400), 0, styled.length(),
                        android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
        }
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

    static void bindPreferredValueRow(Context context, RemoteViews row, CharSequence text,
            int color, float textSizeSp, boolean useSystemFont, int fontWeight) {
        if (useSystemFont) {
            row.setViewVisibility(R.id.notification_value_text, View.VISIBLE);
            row.setViewVisibility(R.id.notification_value_bitmap, View.GONE);
            bindValueRow(row, styledValueText(context, text, true, fontWeight), color, textSizeSp);
            return;
        }
        final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final float textPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, metrics);
        int secondary = color;
        int tertiary = color;
        if (text instanceof android.text.Spanned) {
            android.text.Spanned styled = (android.text.Spanned) text;
            android.text.style.ForegroundColorSpan[] colors = styled.getSpans(
                    0, styled.length(), android.text.style.ForegroundColorSpan.class);
            java.util.Arrays.sort(colors, java.util.Comparator.comparingInt(styled::getSpanStart));
            if (colors.length > 0) secondary = colors[0].getForegroundColor();
            if (colors.length > 1) tertiary = colors[1].getForegroundColor();
        }
        Bitmap value = NotificationChartDrawer.drawGlucoseText(context, text.toString(), color,
                textPixels / (22f * metrics.density), sanitizeFontWeight(fontWeight), false, secondary, tertiary);
        // The painter renders at 2x density; report it so ImageView retains the SP size.
        value.setDensity(Math.round(metrics.densityDpi * 2f));
        row.setImageViewBitmap(R.id.notification_value_bitmap, value);
        row.setContentDescription(R.id.notification_value_bitmap, text);
        row.setViewVisibility(R.id.notification_value_text, View.GONE);
        row.setViewVisibility(R.id.notification_value_bitmap, View.VISIBLE);
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
        bindPreferredValueRow(context, primaryRow, valueText, primaryColor, primarySp, useSystemFont, weight);
        bindRowArrow(primaryRow,
                showArrow ? NotificationChartDrawer.drawArrow(context, rate, isMmol, arrowColor, arrowSize)
                        : null);
        addValueRow(parent, R.id.notification_value_container, primaryRow);
        if (peerItems != null) {
            for (NotificationChartDrawer.ValueItem item : peerItems) {
                if (item == null || item.text == null || item.text.isEmpty()) {
                    continue;
                }
                final int peerColor = SensorVisuals.blendArgb(
                        baseTextColor, item.color, SensorVisuals.PEER_TEXT_BLEND);
                final RemoteViews peerRow = new RemoteViews(packageName, rowLayout);
                bindPreferredValueRow(context, peerRow, peerText(item.text), peerColor, peerSp,
                        useSystemFont, weight);
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
