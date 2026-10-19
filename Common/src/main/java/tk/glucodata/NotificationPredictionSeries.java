package tk.glucodata;

import androidx.annotation.Keep;
import java.util.List;

@Keep
public final class NotificationPredictionSeries {
    public static final int KIND_RAW = 0;
    public static final int KIND_AUTO = 1;
    public static final int KIND_CALIBRATED = 2;

    public final int kind;
    public final List<NotificationPredictionPoint> points;
    public final tk.glucodata.chart.PredictionBand band;

    public NotificationPredictionSeries(int kind, List<NotificationPredictionPoint> points) {
        this(kind, points, null);
    }

    public NotificationPredictionSeries(int kind, List<NotificationPredictionPoint> points,
            tk.glucodata.chart.PredictionBand band) {
        this.kind = kind;
        this.points = points;
        this.band = band;
    }
}
