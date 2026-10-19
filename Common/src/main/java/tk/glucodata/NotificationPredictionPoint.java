package tk.glucodata;

import androidx.annotation.Keep;

@Keep
public final class NotificationPredictionPoint {
    public final long timestamp;
    public final float value;
    public final float confidence;

    public NotificationPredictionPoint(long timestamp, float value, float confidence) {
        this.timestamp = timestamp;
        this.value = value;
        this.confidence = confidence;
    }
}
