package tk.glucodata;

import android.content.Context;
import android.widget.RemoteViews;
import java.util.Collections;

/** Calls the production presenter with synthetic data; no ingestion or sensor APIs. */
public final class ProductionPreview {
    public static RemoteViews values(Context context, boolean expanded, int step, boolean ibm) {
        float rate = step == 0 ? 0f : step == 1 ? 1f : step == 2 ? 3f : -3f;
        return CustomGlucoseNotification.values(context, expanded, step % 2 == 0 ? "5.7" : "5.8",
                0xffeeeeee, 0xffcccccc, 0xffaaaaaa,
                Collections.singletonList(new NotificationChartDrawer.ValueItem("5.5", 0xff81a9f6, 0f)),
                rate, 0xffeeeeee, true, context.getSharedPreferences("native-view-probe", Context.MODE_PRIVATE).getFloat("scale", 1f), 400, !ibm, false, true, 1f, "+0.1 · IOB 1.2", true);
    }
    public static void chart(RemoteViews views, android.graphics.Bitmap bitmap) {
        CustomGlucoseNotification.chart(views, bitmap);
    }
}
