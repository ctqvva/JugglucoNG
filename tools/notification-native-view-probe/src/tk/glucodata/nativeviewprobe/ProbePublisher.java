package tk.glucodata.nativeviewprobe;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.util.Log;
import android.widget.*;

final class ProbePublisher {
    static final String TAG = "NativeViewProbe";
    static RemoteViews views(Context context, boolean expanded, boolean ibm, int step, boolean production) {
        int layout = expanded ? (ibm ? R.layout.expanded_ibm : R.layout.expanded_system)
                : (ibm ? R.layout.compact_ibm : R.layout.compact_system);
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(R.id.primary, step % 2 == 0 ? "5.7" : "5.8");
        views.setTextViewText(R.id.peer, "5.5");
        views.setTextColor(R.id.peer, 0xff81a9f6);
        views.setImageViewResource(R.id.arrow, step >= 2 ? R.drawable.arrow_double : R.drawable.arrow_single);
        // RotateDrawable maps 0..10000 to -90..90 degrees; no bitmap arrows.
        views.setInt(R.id.arrow, "setImageLevel", step % 2 == 0 ? 5000 : 2500);
        views.setInt(R.id.arrow, "setColorFilter", 0xffeeeeee);
        views.setInt(R.id.peer_arrow, "setImageLevel", 5000);
        views.setInt(R.id.peer_arrow, "setColorFilter", 0xff81a9f6);
        views.setContentDescription(R.id.arrow, step % 2 == 0 ? "Test: flat" : "Test: rising");
        views.setContentDescription(R.id.peer_arrow, "Test peer: flat");
        Bitmap chart = Bitmap.createBitmap(688, expanded ? 280 : 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(chart);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(2);
        paint.setColor(0x44777777);
        for (int i = 1; i < 4; i++) {
            canvas.drawLine(688*i/4f, 0, 688*i/4f, chart.getHeight(), paint);
            canvas.drawLine(0, chart.getHeight()*i/4f, 688, chart.getHeight()*i/4f, paint);
        }
        for (int lane = 0; lane < 2; lane++) {
            paint.setColor(lane == 0 ? 0xffe5c3a8 : 0xff81a9f6);
            paint.setStrokeWidth(3);
            float oldX = 24, oldY = chart.getHeight()*0.64f+lane*16;
            for (int i = 1; i <= 80; i++) {
                float x = 24+640*i/80f;
                float y = chart.getHeight()*(0.64f-0.35f*(float)Math.sin(i*Math.PI/80))+lane*16;
                canvas.drawLine(oldX, oldY, x, y, paint);
                oldX = x; oldY = y;
            }
        }
        views.setImageViewBitmap(R.id.chart, chart);
        if (production) {
            views = tk.glucodata.ProductionPreview.values(context, expanded, step, ibm);
            tk.glucodata.ProductionPreview.chart(views, chart);
        }
        return views;
    }

    static void publish(Context context, boolean ibm, int step) {
        publish(context, ibm, step, false);
    }
    static void publish(Context context, boolean ibm, int step, boolean production) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel("probe", "Synthetic native view probe", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null); channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            RemoteViews compact = views(context, false, ibm, step, production);
            TextView local = compact.apply(context, null).findViewById(production
                    ? R.id.notification_glucose : R.id.primary);
            Log.i(TAG, "RemoteViews XML ibm=" + ibm + " width=" + local.getPaint().measureText("5.7 268")
                    + " font=" + local.getTypeface());
            Notification notification = new Notification.Builder(context, "probe")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Synthetic native view probe")
                    .setContentText("Synthetic readings only").setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true)
                    .setStyle(new Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(compact).setCustomBigContentView(views(context, true, ibm, step, production)).build();
            manager.notify(8104, notification);
            Log.i(TAG, "published target=" + context.getApplicationInfo().targetSdkVersion + " ibm=" + ibm
                    + " step=" + step + " production=" + production);
        } catch (Throwable error) { Log.e(TAG, "failed", error); }
    }
}
