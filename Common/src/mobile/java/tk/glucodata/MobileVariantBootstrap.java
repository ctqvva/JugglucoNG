package tk.glucodata;

import android.app.Application;
import android.content.Context;

import tk.glucodata.data.ScheduledBackupWorker;

/**
 * The phone's {@link VariantBootstrap}. Everything here used to be a static on
 * {@link Specific}, which is now a one-method shim; see {@link VariantBootstrap} for why the
 * shim is still there and what pins it.
 *
 * Four of these are no-ops on the phone and have always been, which is why they are kept
 * rather than dropped: the contract is shared, and the alternative to an empty body is the
 * shared caller branching on the variant, which is what the seam exists to remove.
 */
public class MobileVariantBootstrap implements VariantBootstrap {
    @Override
    public void start(Application application) {
        // Idempotent safety net; the real registration happens in onCreate.
        Specific.registerBridges();
        watchdrip.set(Natives.getwatchdrip());
        SuperGattCallback.doGadgetbridge = Natives.getgadgetbridge();
        // Re-arm the nightly backup chain from process start, not only from the
        // settings screen: a chain that died stays dead until someone re-enqueues it.
        ScheduledBackupWorker.initialize(application);
    }

    @Override
    public void splash(MainActivity activity) {
    }

    @Override
    public void initScreen(MainActivity activity) {
    }

    @Override
    public void wearnosensors(MainActivity activity) {
    }

    @Override
    public boolean historyDatabaseCompatible(Context context) {
        return tk.glucodata.data.HistoryDatabase.isCompatibleAtStartup(context);
    }

    @Override
    public void settext(String text) {
    }

    @Override
    public void rmlayout() {
    }

    @Override
    public boolean useCloseButton() {
        return true;
    }

}
