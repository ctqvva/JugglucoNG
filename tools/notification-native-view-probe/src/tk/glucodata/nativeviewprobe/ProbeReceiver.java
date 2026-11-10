package tk.glucodata.nativeviewprobe;

import android.content.*;

public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra("cancel", false)) {
            context.getSystemService(android.app.NotificationManager.class).cancel(8104);
            return;
        }
        if (intent.hasExtra("scale")) context.getSharedPreferences("native-view-probe", Context.MODE_PRIVATE).edit()
                .putFloat("scale", intent.getFloatExtra("scale", 1f)).apply();
        ProbePublisher.publish(context, intent.getBooleanExtra("ibm", false), intent.getIntExtra("step", 0),
                intent.getBooleanExtra("production", false));
    }
}
