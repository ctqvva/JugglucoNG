package tk.glucodata.nums;

/**
 * The phone's numbers hub as its concrete class.
 *
 * <p>{@code Applic.numdata} is a {@link tk.glucodata.NumberDataHub} because shared code
 * holds it in a field and must not name a phone class. Phone code here and there reads
 * phone-only state the interface does not describe -- {@code sendshortcuts}, {@code reinit},
 * {@code stop}, {@code loadDevices}, {@code setcolor} and the Garmin snapshot fields -- so
 * it asks for the concrete class in one place instead of the interface carrying a dozen
 * members no shared caller uses.
 *
 * <p>Null when the phone's instance is not registered. Every caller either already handled
 * a null {@code numdata} or is inside a flavour that never registers one.
 */
public class AllDataAccess {
    private AllDataAccess() {
    }

    public static AllData phone() {
        tk.glucodata.NumberDataHub hub = tk.glucodata.NumberDataAccess.get();
        return hub instanceof AllData ? (AllData) hub : null;
    }
}
