package tk.glucodata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

/** Native phone readouts inside the established custom notification hierarchy. */
final class CustomGlucoseNotification {
    private CustomGlucoseNotification() { }

    static RemoteViews values(Context context, boolean expanded, CharSequence primary,
            int primaryColor, int secondaryColor, int tertiaryColor,
            java.util.List<NotificationChartDrawer.ValueItem> peers, float rate, int arrowColor,
            boolean isMmol, float fontScale, int fontWeight, boolean systemFont, boolean largeArrow,
            boolean showArrow, float arrowScale, CharSequence status, boolean night) {
        RemoteViews views = new RemoteViews(context.getPackageName(), expanded
                ? R.layout.notification_phone_expanded : R.layout.notification_phone_compact);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float safeScale = Float.isFinite(fontScale) && fontScale >= 0.6f && fontScale <= 1.5f ? fontScale : 1f;
        float textSize = (expanded ? 28f : 24f) * safeScale;
        int textUnit = TypedValue.COMPLEX_UNIT_SP;
        float textPixels = TypedValue.applyDimension(textUnit, textSize, metrics);
        if (!expanded && textPixels > 32f * metrics.density) {
            // Compact content has a fixed host budget; expanded text retains SP
            // so the host can apply accessibility scaling without a new reading.
            textUnit = TypedValue.COMPLEX_UNIT_PX;
            textSize = textPixels = 32f * metrics.density;
        }
        int weight = fontWeight == 300 || fontWeight == 500 ? fontWeight : 400;
        float safeArrowScale = Float.isFinite(arrowScale) && arrowScale >= 0.5f && arrowScale <= 1.5f ? arrowScale : 1f;
        bindValue(context, views, R.id.notification_glucose, R.id.notification_glucose_image,
                styledValue(primary, secondaryColor, tertiaryColor), primaryColor, textSize, textUnit,
                textPixels, weight, systemFont);
        // Preserve the old strip's optical spacing: 2.5 units beside arrows,
        // 8 between sensors, relative to its 22-unit primary text. Fixed dp
        // margins otherwise dominate the compact/small-font readout.
        int arrowGap = Math.max(1, Math.round(textPixels * 2.5f / 22f));
        int sensorGap = Math.max(1, Math.round(textPixels * 8f / 22f));
        bindArrow(context, views, R.id.notification_arrow, rate, arrowColor,
                textPixels * 0.8f * safeArrowScale, arrowGap, showArrow, largeArrow);
        // Clear children before replacing peers so reapplication cannot retain an old sensor.
        views.removeAllViews(R.id.notification_peers);
        if (peers != null) for (NotificationChartDrawer.ValueItem peer : peers) {
            if (peer == null || peer.text.isEmpty()) continue;
            int color = SensorVisuals.blendArgb(night ? Color.WHITE : Color.BLACK,
                    peer.color, SensorVisuals.PEER_TEXT_BLEND);
            RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.notification_phone_peer);
            startPadding(context, item, R.id.notification_peer_group, sensorGap);
            float peerPixels = textPixels * 0.78f;
            bindValue(context, item, R.id.notification_peer_value, R.id.notification_peer_image,
                    styledValue(peer.text, secondaryColor, tertiaryColor), color, textSize * 0.78f, textUnit,
                    peerPixels, weight, systemFont);
            bindArrow(context, item, R.id.notification_peer_arrow, peer.rate, color,
                    peerPixels * 0.8f * safeArrowScale, arrowGap, showArrow, largeArrow);
            views.addView(R.id.notification_peers, item);
        }
        boolean hasStatus = status != null && status.length() > 0;
        if (!expanded && textPixels + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
                > 40f * metrics.density) hasStatus = false;
        views.setViewVisibility(R.id.notification_status, hasStatus ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.notification_status_image, View.GONE);
        if (hasStatus) {
            int statusColor = night ? 0xA6FFFFFF : 0xA6000000;
            float statusPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics);
            if (systemFont) {
                SpannableStringBuilder styled = new SpannableStringBuilder(status);
                styled.setSpan(new TypefaceSpan(systemBodyFamily(context)), 0, styled.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                views.setTextViewText(R.id.notification_status, styled);
                views.setTextColor(R.id.notification_status, statusColor);
            } else {
                // System UI cannot load our bundled font. Use the same painter as the
                // values, at body size/regular weight, with a single ellipsized line.
                int widthDp = Math.max(160, context.getResources().getConfiguration().screenWidthDp - 96);
                int width = Math.round(widthDp * metrics.density);
                Bitmap bitmap = NotificationValueBitmap.drawStatus(context, status, statusColor, statusPixels, width);
                views.setViewVisibility(R.id.notification_status, View.GONE);
                views.setViewVisibility(R.id.notification_status_image, View.VISIBLE);
                views.setInt(R.id.notification_status_image, "setMaxHeight", (bitmap.getHeight() + 1) / 2);
                views.setImageViewBitmap(R.id.notification_status_image, bitmap);
                views.setContentDescription(R.id.notification_status_image, status);
            }
        }
        if (!hasStatus || systemFont) views.setImageViewBitmap(R.id.notification_status_image, null);
        return views;
    }

    private static void bindValue(Context context, RemoteViews views, int textId, int imageId,
            CharSequence text, int color, float size, int unit, float pixels, int weight, boolean systemFont) {
        // Named system fonts cross the process boundary. Bundled Typeface objects
        // do not; paint IBM Plex in-app rather than silently substituting Roboto.
        // Light weight also uses the painter because RemoteViews cannot set a
        // numeric TextView font weight and a sans-serif-light span loses the OEM family.
        boolean nativeText = systemFont && weight != 300;
        views.setViewVisibility(textId, nativeText ? View.VISIBLE : View.GONE);
        views.setViewVisibility(imageId, nativeText ? View.GONE : View.VISIBLE);
        if (nativeText) {
            SpannableStringBuilder styled = new SpannableStringBuilder(text);
            if (styled.length() > 0) styled.setSpan(new TypefaceSpan(systemFamily(context, weight)),
                    0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            views.setTextViewText(textId, styled);
            views.setTextViewTextSize(textId, unit, size);
            views.setTextColor(textId, color);
            views.setImageViewBitmap(imageId, null);
        } else {
            Bitmap bitmap = NotificationValueBitmap.draw(context, text, color, pixels, weight, systemFont);
            // Bound transported raster height even when System UI resets bitmap density.
            views.setInt(imageId, "setMaxHeight", (bitmap.getHeight() + 1) / 2);
            views.setImageViewBitmap(imageId, bitmap);
            views.setContentDescription(imageId, text);
        }
    }

    static String systemFamily(Context context, int weight) {
        String resource = weight == 500 ? "config_headlineFontFamilyMedium" : "config_headlineFontFamily";
        int id = context.getResources().getIdentifier(resource, "string", "android");
        if (id != 0) return context.getResources().getString(id);
        return weight == 500 ? "sans-serif-medium" : "sans-serif";
    }

    static String systemBodyFamily(Context context) {
        int id = context.getResources().getIdentifier("config_bodyFontFamily", "string", "android");
        return id == 0 ? "sans-serif" : context.getResources().getString(id);
    }

    private static CharSequence styledValue(CharSequence value, int secondary, int tertiary) {
        SpannableStringBuilder text = new SpannableStringBuilder(value == null ? "" : value);
        String plain = text.toString();
        int second = plain.indexOf(" · ");
        if (second < 0) return text;
        int third = plain.indexOf(" · ", second + 3);
        int secondEnd = third < 0 ? text.length() : third;
        text.setSpan(new RelativeSizeSpan(0.7f), second, secondEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(secondary), second, secondEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (third >= 0) {
            text.setSpan(new RelativeSizeSpan(0.6f), third, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(tertiary), third, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private static void bindArrow(Context context, RemoteViews views, int id, float rate, int color,
            float pixels, int gap, boolean enabled, boolean large) {
        boolean visible = enabled && Float.isFinite(rate);
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        boolean doubled = Math.abs(rate) > 2f;
        int drawable = large ? (doubled ? R.drawable.notification_trend_long_double : R.drawable.notification_trend_long_single)
                : (doubled ? R.drawable.notification_trend_double : R.drawable.notification_trend_single);
        views.setImageViewResource(id, drawable);
        float angle = TrendArrowAngle.rotationDegrees(rate);
        // Level controls rotation; select head-count resources independently of that level.
        views.setInt(id, "setImageLevel", Math.round((angle + 90f) * 10000f / 180f));
        views.setInt(id, "setColorFilter", color);
        int size = Math.max(1, Math.round(pixels));
        startPadding(context, views, id, gap);
        // Max width includes the gap so padding does not shrink the vector itself.
        views.setInt(id, "setMaxWidth", size + gap);
        views.setInt(id, "setMaxHeight", size);
        int description = angle == 0f ? R.string.notification_trend_steady
                : rate > 0 ? (doubled ? R.string.notification_trend_rising_fast : R.string.notification_trend_rising)
                : (doubled ? R.string.notification_trend_falling_fast : R.string.notification_trend_falling);
        views.setContentDescription(id, context.getString(description));
    }

    private static void startPadding(Context context, RemoteViews views, int id, int pixels) {
        boolean rtl = context.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        views.setViewPadding(id, rtl ? 0 : pixels, 0, rtl ? pixels : 0, 0);
    }

    /** Standard title/text only when the user keeps them on: watch companion apps
     * forward any notification that has them, so each per-reading update reached the
     * watch (#645). Null leaves the field unset, not empty. The custom views expose the
     * readout to TalkBack either way, as native text or content descriptions. */
    static void apply(android.app.Notification.Builder builder, CharSequence title, CharSequence text,
            RemoteViews compact, RemoteViews expanded) {
        if (title != null && title.length() > 0) builder.setContentTitle(title);
        if (text != null && text.length() > 0) builder.setContentText(text);
        builder.setStyle(new android.app.Notification.DecoratedCustomViewStyle())
                .setCustomContentView(compact).setCustomBigContentView(expanded);
    }

    static void chart(RemoteViews views, Bitmap chart) {
        views.setViewVisibility(R.id.chart_container, chart == null ? View.GONE : View.VISIBLE);
        if (chart != null) views.setImageViewBitmap(R.id.notification_chart, chart);
    }
}
