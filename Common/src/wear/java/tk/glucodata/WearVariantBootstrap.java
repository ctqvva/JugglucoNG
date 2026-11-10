package tk.glucodata;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.splashscreen.SplashScreen;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.settings.Settings.removeContentView;

/**
 * The watch's {@link VariantBootstrap}. Everything here used to be a static on
 * {@link Specific}, which is now a one-method shim; see {@link VariantBootstrap} for why the
 * shim is still there and what pins it.
 *
 * [layout] and [text] are static, and that is deliberate. They hold the startup screen, which
 * [settext] writes into and [rmlayout] tears down, and the three are called from different
 * places, so per-instance state would let one instance's [settext] write into another's view.
 * They were static on [Specific] for the same reason; the access object caches its instance
 * so a second one is not normally created either.
 */
public class WearVariantBootstrap implements VariantBootstrap {
    @SuppressLint("StaticFieldLeak")
    static ViewGroup layout = null;
    @SuppressLint("StaticFieldLeak")
    static TextView text = null;

    @Override
    public void start(Application application) {
        Specific.registerBridges();
    }

    @Override
    public void splash(MainActivity activity) {
        SplashScreen.installSplashScreen(activity);
    }

    @Override
    public void initScreen(MainActivity activity) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        ViewGroup inflated = (ViewGroup) inflater.inflate(R.layout.startview, null, false);
        text = inflated.findViewById(R.id.text2);
        layout = inflated;
        activity.addContentView(inflated, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    @Override
    public void wearnosensors(MainActivity activity) {
        Switch.wearnosensors(activity);
    }

    @Override
    public boolean historyDatabaseCompatible(Context context) {
        return true;
    }

    /**
     * The watch used to return whether the text view existed to put the message in. Nothing
     * reads that, so it is dropped here rather than narrowed to a boolean the contract has to
     * carry; see {@link VariantBootstrap#settext}.
     */
    @Override
    public void settext(String text) {
        TextView current = this.text;
        if (current != null) {
            current.setText(text);
        }
    }

    @Override
    public void rmlayout() {
        ViewGroup current = layout;
        if (current != null) {
            text = null;
            layout = null;
            removeContentView(current);
        }
    }

    /**
     * A persisted native setting, not variant state. The `useclose` cache and its
     * {@code setclose} writer are gone, so this reads the setting instead.
     */
    @Override
    public boolean useCloseButton() {
        return !Natives.getdontuseclose();
    }

}
