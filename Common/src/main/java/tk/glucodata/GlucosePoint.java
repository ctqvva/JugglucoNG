package tk.glucodata;

public class GlucosePoint {
    public long timestamp;
    public float value;
    public float rawValue; // Added for Raw data
    public int color; // Optional, for point coloring
    /**
     * The value this reading was recorded as having shown, in the same unit as
     * {@link #value}, or NaN when nothing was recorded. A renderer that finds one
     * draws it instead of recomputing — see tk.glucodata.data.ReadingDisplay.
     * This bridge type dropped it for a long time, which is why the notification
     * chart could only ever show today's derivation of yesterday's line.
     */
    public float sealedDisplayValue = Float.NaN;
    /**
     * The view mode in force when {@link #sealedDisplayValue} was recorded, so a
     * renderer knows which lane the number belongs to (0/2 auto, 1/3 raw).
     * -1 when nothing was recorded.
     */
    public int sealedDisplayViewMode = -1;
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
