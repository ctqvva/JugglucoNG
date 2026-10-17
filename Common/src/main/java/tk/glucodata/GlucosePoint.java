package tk.glucodata;

public class GlucosePoint {
    public long timestamp;
    public float value;
    public float rawValue; // Added for Raw data
    public int color; // Optional, for point coloring
    /**
     * The main value recorded for this minute on the auto line (view modes 0/2),
     * in the same unit as {@link #value}, or NaN when nothing was recorded there.
     * A renderer that finds one draws it instead of recomputing — see
     * tk.glucodata.data.ReadingDisplay. This bridge type dropped the record for a
     * long time, which is why the notification chart could only ever show
     * today's derivation of yesterday's line.
     */
    public float sealedAutoLaneValue = Float.NaN;
    /** The same for the raw line (view modes 1/3). */
    public float sealedRawLaneValue = Float.NaN;

    /** The recorded main value of the lane being drawn, or NaN. */
    public float sealedValueFor(boolean isRawMode) {
        return isRawMode ? sealedRawLaneValue : sealedAutoLaneValue;
    }

    /** The sensor that produced this reading, when known. */
    public String sensorSerial;

    public GlucosePoint(long timestamp, float value) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = 0f;
    }

    public GlucosePoint(long timestamp, float value, int color) {
        this.timestamp = timestamp;
        this.value = value;
        this.color = color;
        this.rawValue = 0f;
    }

    public GlucosePoint(long timestamp, float value, float rawValue) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = rawValue;
    }
}
