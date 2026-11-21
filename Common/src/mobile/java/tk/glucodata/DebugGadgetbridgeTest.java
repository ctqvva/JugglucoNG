package tk.glucodata;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/**
 * Debug builds only: lets a developer push a fake reading to Gadgetbridge without a sensor,
 * replacing the legacy Watch "Test" button that the Compose UI no longer reaches.
 *
 * <pre>
 * adb shell am broadcast -a tk.glucodata.DEBUG_GADGETBRIDGE_TEST --ef glucose 6.2 --ef rate -1.2
 * </pre>
 * {@code glucose} is in the app's display unit (mmol/L or mg/dL); {@code rate} is mg/dL per minute.
 */
final class DebugGadgetbridgeTest {
    static final String ACTION = "tk.glucodata.DEBUG_GADGETBRIDGE_TEST";
    private static final String LOG_ID = "DebugGadgetbridgeTest";
    private static boolean registered = false;

    private DebugGadgetbridgeTest() {
    }

    static synchronized void register(Application app) {
        if (!BuildConfig.DEBUG || registered)
            return;
        registered = true;
        ContextCompat.registerReceiver(app, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final float glucose = intent.getFloatExtra("glucose", Applic.unit == 1 ? 6.2f : 112f);
                final float rate = intent.getFloatExtra("rate", 0f);
                final int mgdl = Applic.unit == 1 ? Math.round(glucose * 18.0182f) : Math.round(glucose);
                Log.i(LOG_ID, "sending test glucose " + glucose + " rate " + rate + " to Gadgetbridge");
                Gadgetbridge.sendglucose("" + glucose, mgdl, glucose, rate, System.currentTimeMillis());
            }
        }, new IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED);
    }
}
