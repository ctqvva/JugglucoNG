package tk.glucodata;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Build;

/** Ordinary phone content. System UI owns typography, layout and transitions. */
final class NativeGlucoseNotification {
    private NativeGlucoseNotification() { }

    static String trendSymbol(float rate) {
        if (!Float.isFinite(rate)) return "";
        if (rate > 2f) return "⇈";
        if (rate < -2f) return "⇊";
        int direction = Math.round(TrendArrowAngle.rotationDegrees(rate) / 45f);
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
