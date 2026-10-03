package tk.glucodata;

/**
 * One-shot nag state for the "NFC disabled" prompt.
 *
 * <p>The old code folded the automatic nag and explicit user requests into a single {@code
 * askNFC} flag, so a passive check consumed the flag and a later explicit Libre scan silently
 * did nothing. Here the flag controls only the automatic nag: an explicit request always gets
 * its toast and (on a primary) the settings page, even after passive checks ran first.
 *
 * <p>Dependency-free on purpose, so the state machine is unit-testable on the JVM.
 */
public final class NfcPromptState {
    public static final class Decision {
        public final boolean toast;
        public final boolean openSettings;

        Decision(boolean toast, boolean openSettings) {
            this.toast = toast;
            this.openSettings = openSettings;
        }
    }

    private boolean nagRemaining = true;

    /**
     * Decides what an NFC-disabled encounter does.
     *
     * <ul>
     * <li>BLE-only passive check: nothing at all, nag preserved.
     * <li>Libre passive check: toast once per instance, never settings.
     * <li>Explicit NFC action on a primary: toast plus settings, every time.
     * <li>Follower: toast at most, never settings.
     * </ul>
     */
    public Decision decide(boolean userRequested, boolean nfcNeeded, int backupHostNr) {
        final boolean relevant = userRequested || nfcNeeded;
        final boolean toast = relevant && (userRequested || nagRemaining);
        if (relevant && nagRemaining) {
            nagRemaining = false;
        }
        return new Decision(toast, NfcSettingsRouting.shouldOpen(userRequested, backupHostNr));
    }
}
