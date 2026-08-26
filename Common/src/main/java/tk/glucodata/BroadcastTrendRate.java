/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

package tk.glucodata;

/**
 * Opt-in replacement of the exchange rate: the outgoing rate normally
 * carries the sensor's own estimate, which lags fast moves badly — a
 * receiver rotating its arrow by it ends up contradicting this app's arrow
 * and its own delta readout. With the toggle enabled, every exchange output
 * (glucodata broadcast, xDrip-style, watch, Gadgetbridge, API)
 * carries the same trend the app's own arrows show instead.
 */
public final class BroadcastTrendRate {
    public static final String PREF_KEY = "broadcast_computed_trend";
    private static final String PREFS_NAME = "tk.glucodata_preferences";

    private BroadcastTrendRate() {
    }

    public static boolean enabled() {
        try {
            return Applic.app
                    .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY, false);
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * The app's trend over the usual 20-minute window for this sensor, in
     * mg/dL per minute; falls back to the given native rate when history is
     * too thin to say anything.
     */
    public static float computed(String sensorId, float nativeRate) {
        try {
            final boolean isMmol = Applic.unit == 1;
            // Load twice the trend window: the canonical cut below anchors at the newest
            // point, which lags the wall clock, so a window-sized load could miss the tail.
            final long startT = System.currentTimeMillis() - 2 * DisplayTrendSource.TREND_WINDOW_MS;
            java.util.List<GlucosePoint> points = NotificationHistorySource.getDisplayHistory(
                    startT,
                    isMmol,
                    sensorId);
            // Append the live reading before regressing, exactly like the notification and the
            // dashboard do. Room persistence lags the current value, so without this the outgoing
            // rate is computed over a point list one reading shorter than the one the app's own
            // arrow uses -- the two then disagree on precisely the fast moves this option exists
            // for. resolveCurrent (not ...ForExchange) on purpose: the promise here is to carry
            // the trend the app's arrows show, which is the locally smoothed series.
            CurrentDisplaySource.Snapshot current = null;
            try {
                current = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, sensorId);
            } catch (Throwable th) {
                if (Log.doLog)
                    Log.i("BroadcastTrendRate", "resolveCurrent failed: " + th);
            }
            if (current != null) {
                final Float vendorRate = VendorTrendRate.resolve(current.getSensorId(), current.getTimeMillis());
                if (vendorRate != null) {
                    return vendorRate;
                }
            }
            points = DisplayTrendSource.resolveTrendPoints(points, current, sensorId);
            final int viewMode = Notify.resolveSensorViewMode(sensorId);
            // current is deliberately not handed to resolveArrowRate: when the history is too thin
            // to say anything this must fall back to the caller's native rate, as documented above,
            // rather than to the display snapshot's own rate.
            final float computed = DisplayTrendSource.resolveArrowRate(points, null, viewMode, isMmol, Float.NaN);
            return Float.isFinite(computed) ? computed : nativeRate;
        } catch (Throwable th) {
            if (Log.doLog)
                Log.i("BroadcastTrendRate", "computed failed: " + th);
            return nativeRate;
        }
    }
}
