package tk.glucodata;

/**
 * Routing decision for the system NFC-settings page.
 *
 * <p>Kept separate from MainActivity so the truth table is unit-testable on the JVM: MainActivity
 * cannot be loaded without the Android runtime. The native/sensor probing ({@code isNfcNeeded})
 * stays on MainActivity; only this pure decision lives here.
 */
public final class NfcSettingsRouting {
    private NfcSettingsRouting() {
    }

    /**
     * Followers (backupHostNr != 0) never need the NFC page. Everyone else only sees it when NFC
     * is relevant right now: an explicit NFC flow (userRequested, e.g. a Libre scan) or an active
     * Libre sensor (nfcNeeded). BLE-only users get the "NFC disabled" toast at most, never an
     * automatic jump to system settings on cold start.
     */
    public static boolean shouldOpen(boolean userRequested, boolean nfcNeeded, int backupHostNr) {
        return backupHostNr == 0 && (userRequested || nfcNeeded);
    }
}
