package tk.glucodata;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;

public class PhotoScan {
public static void scan(Activity act, int type) { }
public static void scan(Activity act, int type, String title) { }
static void connectSensor(final String scantag) {}
public static void connectSensor(final String scantag, MainActivity act, int request, long sensorptr) {}
public static boolean applyManualPairingCode(Context context, String rawCode) { return false; }
static boolean handleUnifiedScanResult(int resultCode, Intent data, MainActivity act, int type) { return false; }
public static Intent createUnifiedScanIntent(Context context, int type, long sensorptr) { return null; }
public static Intent createUnifiedScanIntent(Context context, int type, long sensorptr, String title) { return null; }
//static boolean zXingResult(int resultCode, Object data) {return false;}
};
