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
 * Linear "where is this heading" projection for the trend arrow: extrapolates
 * the current rate over a short horizon and reports whether that lands outside
 * the target range ({@link #BORDERLINE}) or beyond very low/very high
 * ({@link #OUT}). Recovery trends never warn — a low value rising back into
 * range projects clean. All inputs in mg/dL and mg/dL per minute.
 */
public final class TrendProjection {
    public static final int NONE = 0;
    public static final int BORDERLINE = 1;
    public static final int OUT = 2;

    public static final int DEFAULT_HORIZON_MINUTES = 30;

    // A forecast is only trustworthy while readings are current. Beyond a few
    // missed one-minute readings (reconnect gap, app restart) the projection
    // would extrapolate from a value and trend that no longer describe now,
    // so it must stop claiming where glucose is heading.
    public static final long MAX_DATA_AGE_MILLIS = 5L * 60L * 1000L;

    private TrendProjection() {
    }

    /**
     * Like {@link #classify(float, float, int, float, float, float, float)}
     * but gated on the age of the newest reading: stale data yields
     * {@link #NONE}. Slightly negative ages (live points a few seconds ahead
     * of the clock) count as fresh.
     */
    public static int classify(
            float glucoseMgdl,
            float rateMgdlPerMin,
            long dataAgeMillis,
            int horizonMinutes,
            float targetLowMgdl,
            float targetHighMgdl,
            float veryLowMgdl,
            float veryHighMgdl) {
        if (dataAgeMillis > MAX_DATA_AGE_MILLIS)
            return NONE;
        return classify(glucoseMgdl, rateMgdlPerMin, horizonMinutes,
                targetLowMgdl, targetHighMgdl, veryLowMgdl, veryHighMgdl);
    }

    public static int classify(
            float glucoseMgdl,
            float rateMgdlPerMin,
            int horizonMinutes,
            float targetLowMgdl,
            float targetHighMgdl,
            float veryLowMgdl,
            float veryHighMgdl) {
        if (!Float.isFinite(glucoseMgdl) || glucoseMgdl <= 0.0f)
            return NONE;
        if (!Float.isFinite(rateMgdlPerMin))
            return NONE;
        final int horizon = horizonMinutes > 0 ? horizonMinutes : DEFAULT_HORIZON_MINUTES;

        final float low = Float.isFinite(targetLowMgdl) && targetLowMgdl > 0.0f
                ? targetLowMgdl
                : GlucoseRangeColors.DEFAULT_LOW_MGDL;
        float high = Float.isFinite(targetHighMgdl) && targetHighMgdl > low
                ? targetHighMgdl
                : GlucoseRangeColors.DEFAULT_HIGH_MGDL;
        high = Math.max(high, low + 0.1f);
        float veryLow = Float.isFinite(veryLowMgdl) && veryLowMgdl > 0.0f
                ? veryLowMgdl
                : GlucoseRangeColors.DEFAULT_VERY_LOW_MGDL;
        veryLow = Math.min(veryLow, low - 0.1f);
        float veryHigh = Float.isFinite(veryHighMgdl) && veryHighMgdl > 0.0f
                ? veryHighMgdl
                : GlucoseRangeColors.DEFAULT_VERY_HIGH_MGDL;
        veryHigh = Math.max(veryHigh, high + 0.1f);

        final float projected = glucoseMgdl + (rateMgdlPerMin * horizon);
        if (projected <= veryLow || projected >= veryHigh)
            return OUT;
        if (projected < low || projected > high)
            return BORDERLINE;
        return NONE;
    }
}
