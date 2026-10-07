package tk.glucodata;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

/** Sends Nightscout SGV intents to xDrip's NSClientReceiver in Follower mode. */
public final class XdripFollowerSend {
    private static final String PREFS = "exchange_xdrip_follower";
    private static final String ENABLED = "enabled";
    private static final String ACTION = "info.nightscout.client.NEW_SGV";
    private static final String XDRIP_PACKAGE = "com.eveningoutpost.dexdrip";

    private XdripFollowerSend() { }

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, enabled).apply();
    }

    static void broadcastGlucose(Context context, ExchangeGlucosePayload payload) {
        if (payload == null || payload.primaryMgdl <= 0 || payload.timeMillis <= 0) return;
        try {
            context.sendBroadcast(createIntent(payload.primaryMgdl, payload.timeMillis, payload.trendName));
        } catch (JSONException error) {
            Log.e("XdripFollowerSend", "Cannot encode SGV: " + error);
        }
    }

    static Intent createIntent(int mgdl, long timeMillis, String trendName) throws JSONException {
        JSONObject sgv = new JSONObject()
                .put("mills", timeMillis)
                .put("mgdl", mgdl)
                .put("direction", trendName.isEmpty() ? "NOT_COMPUTABLE" : trendName);
        Intent intent = new Intent(ACTION);
        intent.setPackage(XDRIP_PACKAGE);
        intent.putExtra("sgv", sgv.toString());
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return intent;
    }
}
