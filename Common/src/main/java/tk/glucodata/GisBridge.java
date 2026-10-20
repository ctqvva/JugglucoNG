/*  GIS Monitor — based on Juggluco (GPL-3). Local data bridge for the GIS app: no internet, two interfaces only.   */
/*  Provider  content://com.gismonitor.data/latest   (columns: ts, raw_mgdl, cal_mgdl, trend, sensor_name, sensor_start, sensor_end, signal) */
/*  Broadcast com.gismonitor.CALIBRATE               (extras: ts, meter_mgdl)                                          */
package tk.glucodata;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class GisBridge extends ContentProvider {
    private static final String PREFS = "gis_bridge";
    private static final String[] COLS = {"ts", "raw_mgdl", "cal_mgdl", "trend", "sensor_name", "sensor_start", "sensor_end", "signal"};

    /** Called for every new value (from SendLikexDrip.broadcastglucose). rate: mg/dL per minute * 60000 scale as in Juggluco. */
    public static void publish(double glucoseMgdl, float rate, long timeMs, long sensorStartMs, int sensorGen) {
        try {
            final Context c = Applic.app;
            if (c == null) return;
            final double perMin = rate / 60000.0; // mg/dL per minute
            int trend = perMin >= 3 ? 3 : perMin >= 2 ? 2 : perMin >= 1 ? 1 : perMin > -1 ? 0 : perMin > -2 ? -1 : perMin > -3 ? -2 : -3;
            final long days = sensorGen == 0x10 ? 22 : 14; // Sibionics 2 — 22 days (GS3 as well); other sensors per their own life
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("ts", timeMs).putFloat("raw", (float) glucoseMgdl).putInt("trend", trend)
                .putString("name", name(sensorGen)).putLong("start", sensorStartMs)
                .putLong("end", sensorStartMs > 0 ? sensorStartMs + (sensorGen == 0x15 ? 22L : days) * 86_400_000L : 0L)
                .apply();
            c.getContentResolver().notifyChange(Uri.parse("content://com.gismonitor.data/latest"), null);
        } catch (Throwable ignored) { }
    }

    /** С разбором значения: сырое и калиброванное отдельно (калиброванное — то, что уходит в AAPS и Nightscout). */
    static void publishPayload(ExchangeGlucosePayload p, long sensorStartMs) {
        try {
            final Context c = Applic.app;
            if (c == null) return;
            final double k = Applic.unit == 1 ? 18.0182 : 1.0;
            final double cal = p.getPrimaryMgdl();
            final double raw = Float.isNaN(p.getRawValue()) || p.getRawValue() <= 0f ? cal : p.getRawValue() * k;
            publish(raw, p.getRate(), p.getTimeMillis(), sensorStartMs, p.getSensorGen());
            final SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (Math.abs(cal - raw) >= 1.0) e.putFloat("cal", (float) cal); else e.remove("cal");
            e.apply();
        } catch (Throwable ignored) { }
    }

    private static String name(int gen) {
        switch (gen) {
            case 0x10: return "Sibionics 1";
            case 0x15: return "Sibionics 2";
            case 0x50: return "Lumiflex";
            default: return "Sensor";
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        final SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final MatrixCursor cur = new MatrixCursor(COLS);
        if (sp.contains("ts")) {
            final double raw = sp.getFloat("raw", 0f);
            cur.addRow(new Object[]{sp.getLong("ts", 0), raw, sp.contains("cal") ? (Object) (double) sp.getFloat("cal", 0f) : null,
                sp.getInt("trend", 0), sp.getString("name", "Sensor"), sp.getLong("start", 0), sp.getLong("end", 0), 100});
        }
        return cur;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.gismonitor.reading"; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
