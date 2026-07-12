package tk.glucodata;

public class GlucosePoint {
    public long timestamp;
    public float value;
    public float rawValue; // Added for Raw data
    public int color; // Optional, for point coloring
    public String sensorSerial;

    public GlucosePoint(long timestamp, float value) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = 0f;
        this.sensorSerial = "";
    }

    public GlucosePoint(long timestamp, float value, int color) {
        this.timestamp = timestamp;
        this.value = value;
        this.color = color;
        this.rawValue = 0f;
        this.sensorSerial = "";
    }

    public GlucosePoint(long timestamp, float value, float rawValue) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = rawValue;
        this.sensorSerial = "";
    }

    public GlucosePoint(long timestamp, float value, float rawValue, String sensorSerial) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = rawValue;
        this.sensorSerial = sensorSerial != null ? sensorSerial : "";
    }
}
