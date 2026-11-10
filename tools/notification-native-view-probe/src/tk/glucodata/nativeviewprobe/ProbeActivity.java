package tk.glucodata.nativeviewprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("Synthetic native notification probe. Installed CGM app unchanged.\nBelow: ordinary XML inflation, system then bundled IBM.");
        root.addView(title);
        for (boolean ibm : new boolean[]{false, true}) {
            int layout = ibm ? R.layout.expanded_ibm : R.layout.expanded_system;
            View sample = getLayoutInflater().inflate(layout, root, false);
            TextView primary = sample.findViewById(R.id.primary);
            primary.setText("5.7 268");
            Log.i(ProbePublisher.TAG, "ordinary XML ibm=" + ibm + " width=" + primary.getPaint().measureText("5.7 268")
                    + " font=" + primary.getTypeface());
            root.addView(sample);
        }
        setContentView(root);
        getSharedPreferences("native-view-probe", MODE_PRIVATE).edit()
                .putFloat("scale", getIntent().getFloatExtra("scale", 1f)).apply();
        ProbePublisher.publish(this, getIntent().getBooleanExtra("ibm", false), getIntent().getIntExtra("step", 0),
                getIntent().getBooleanExtra("production", false));
    }
}
