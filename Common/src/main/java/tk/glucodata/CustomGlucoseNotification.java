package tk.glucodata;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

/** Phone content preserving the app's unitless value hierarchy and custom arrows. */
final class CustomGlucoseNotification {
    private CustomGlucoseNotification() { }

    static RemoteViews expandedValues(Context context, CharSequence primary,
            int primaryColor, int secondaryColor, int tertiaryColor,
            java.util.List<NotificationChartDrawer.ValueItem> peers, float rate, int arrowColor,
            boolean isMmol, float fontScale, int fontWeight, boolean systemFont,
            boolean showArrow, float arrowScale, CharSequence status, boolean night) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_phone_expanded);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float safeScale = Float.isFinite(fontScale) && fontScale >= 0.6f && fontScale <= 1.5f ? fontScale : 1f;
        float textPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                28f * safeScale, metrics);
        int weight = fontWeight == 300 || fontWeight == 500 ? fontWeight : 400;
        float safeArrowScale = Float.isFinite(arrowScale) && arrowScale >= 0.5f && arrowScale <= 1.5f ? arrowScale : 1f;
        Bitmap value = NotificationChartDrawer.drawMultiGlucoseText(context,
                primary == null ? "" : primary.toString(), primaryColor, secondaryColor, tertiaryColor,
                peers, textPixels / (22f * metrics.density), weight, systemFont,
                rate, isMmol, safeArrowScale, showArrow, arrowColor);
        // The painter renders at twice display density. Bound the view from those
        // actual font metrics too: System UI's bitmap transport can reset density.
        value.setDensity(Math.round(metrics.densityDpi * 2f));
        views.setInt(R.id.notification_glucose_image, "setMaxHeight", (value.getHeight() + 1) / 2);
        views.setImageViewBitmap(R.id.notification_glucose_image, value);
        views.setContentDescription(R.id.notification_glucose_image, valueDescription(primary, peers));
        // Keep the primary arrow in the strip too, so narrow hosts scale it with the value.
        views.setViewVisibility(R.id.notification_arrow, View.GONE);
        boolean hasStatus = status != null && status.length() > 0;
        views.setViewVisibility(R.id.notification_status, hasStatus ? View.VISIBLE : View.GONE);
        if (hasStatus) {
            int familyId = context.getResources().getIdentifier("config_bodyFontFamily", "string", "android");
            android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(status);
            if (familyId != 0) styled.setSpan(new android.text.style.TypefaceSpan(context.getResources().getString(familyId)),
                    0, styled.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            views.setTextViewText(R.id.notification_status, styled);
            views.setTextColor(R.id.notification_status, night ? 0xB3FFFFFF : 0x8A000000);
        }
        return views;
    }

    private static String valueDescription(CharSequence primary,
            java.util.List<NotificationChartDrawer.ValueItem> peers) {
        StringBuilder text = new StringBuilder(primary == null ? "" : primary.toString());
        if (peers != null) for (NotificationChartDrawer.ValueItem peer : peers) {
            if (peer != null && peer.text != null && !peer.text.isEmpty()) text.append(" · ").append(peer.text);
        }
        return text.toString();
    }

    static void apply(android.app.Notification.Builder builder, CharSequence primary,
            java.util.List<NotificationChartDrawer.ValueItem> peers, CharSequence status,
            RemoteViews expanded) {
        builder.setContentTitle(valueDescription(primary, peers))
                .setContentText(status == null ? "" : status)
                .setStyle(new android.app.Notification.DecoratedCustomViewStyle())
                // Null compact content delegates typography and layout to System UI.
                .setCustomContentView(null).setCustomBigContentView(expanded);
    }

    static void chart(RemoteViews views, Bitmap chart) {
        views.setViewVisibility(R.id.chart_container, chart == null ? View.GONE : View.VISIBLE);
        if (chart != null) views.setImageViewBitmap(R.id.notification_chart, chart);
    }
}
