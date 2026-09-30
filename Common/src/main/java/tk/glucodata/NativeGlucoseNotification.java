package tk.glucodata;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Build;

/** Ordinary phone content. System UI owns typography, layout and transitions. */
final class NativeGlucoseNotification {
    private NativeGlucoseNotification() { }

    static int chartWidth(int pictureSide) { return Math.max(1, pictureSide * 9 / 10); }
    static int chartHeight(int pictureSide) { return Math.max(1, pictureSide * 3 / 10); }

    /** BigPicture uses CENTER_CROP, not FIT_CENTER. Keep all plotted content in
     * the center of a square image, safe for host slots with width/height 1..3.
     * The surrounding transparent pixels can be cropped without losing data.
     * Arbitrary OEM slot shapes remain a device-validation boundary. */
    static Bitmap frameChart(Bitmap chart, int pictureSide) {
        Bitmap picture = Bitmap.createBitmap(pictureSide, pictureSide, Bitmap.Config.ARGB_8888);
        picture.setDensity(chart.getDensity());
        android.graphics.Canvas canvas = new android.graphics.Canvas(picture);
        canvas.drawBitmap(chart, (pictureSide - chart.getWidth()) / 2f,
                (pictureSide - chart.getHeight()) / 2f, null);
        return picture;
    }

    static String trendSymbol(float rate) {
        if (!Float.isFinite(rate)) return "";
        if (rate > 2f) return "⇈";
        if (rate < -2f) return "⇊";
        float angle = TrendArrowAngle.rotationDegrees(rate);
        int direction = Math.round(Math.abs(angle) / 45f) * (angle < 0f ? -1 : 1);
        switch (direction) {
            case -2: return "↑";
            case -1: return "↗";
            case 1: return "↘";
            case 2: return "↓";
            default: return "→";
        }
    }

    static String title(CharSequence primary, float rate,
            java.util.List<NotificationChartDrawer.ValueItem> peers, boolean showArrow) {
        StringBuilder text = new StringBuilder(primary == null ? "" : primary.toString());
        appendTrend(text, rate, showArrow);
        if (peers != null) {
            for (NotificationChartDrawer.ValueItem peer : peers) {
                if (peer == null || peer.text == null || peer.text.isEmpty()) continue;
                text.append(" · ").append(peer.text);
                appendTrend(text, peer.rate, showArrow);
            }
        }
        return text.toString();
    }

    private static void appendTrend(StringBuilder text, float rate, boolean enabled) {
        if (!enabled) return;
        String symbol = trendSymbol(rate);
        if (!symbol.isEmpty()) text.append(' ').append(symbol);
    }

    static void apply(Notification.Builder builder, CharSequence primary, float rate,
            java.util.List<NotificationChartDrawer.ValueItem> peers, boolean showArrow,
            String units, CharSequence status, Bitmap chart, String chartDescription) {
        String title = title(primary, rate, peers, showArrow);
        String detail = status == null || status.length() == 0
                ? units : units + " · " + status;
        // Plain strings intentionally leave font, size and contrast to System UI.
        builder.setCustomContentView(null).setCustomBigContentView(null)
                .setCustomHeadsUpContentView(null)
                .setContentTitle(title).setContentText(detail);
        if (chart != null) {
            Notification.BigPictureStyle style = new Notification.BigPictureStyle()
                    .bigPicture(chart).setBigContentTitle(title).setSummaryText(detail);
            if (Build.VERSION.SDK_INT >= 31) {
                style.showBigPictureWhenCollapsed(false);
                style.setContentDescription(chartDescription);
            }
            builder.setStyle(style);
        } else {
            // Also clear a previously attached picture when the setting changes.
            builder.setStyle(new Notification.BigTextStyle().setBigContentTitle(title).bigText(detail));
        }
    }
}
