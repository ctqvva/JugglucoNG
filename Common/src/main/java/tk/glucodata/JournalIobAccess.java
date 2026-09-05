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

import android.os.Looper;

// Bridge to the journal-based insulin/carb snapshot. The journal only exists
// in the mobile source set, so callers in src/main (JugglucoSend, Notify)
// resolve it by name — same pattern as OutboundApi. Returns null on variants
// without the journal (wear/small) and when the journal is unused/disabled.
public class JournalIobAccess {
    private static java.lang.reflect.Method snapshotMethod;
    private static boolean snapshotResolved;

    // Feeds the current journal IOB/COB to the native webserver so /pebble
    // polls (GlucoDataHandler's Juggluco IOB support) report the journal
    // instead of the unused native amounts store. Pushing NaN clears a value:
    // the webserver then falls back to its native computation.
    static void pushWatchserver(long atMillis) {
        float[] values = snapshot(atMillis);
        if (values != null && values.length >= 3) {
            float iobNext30 = values.length >= 5 ? values[3] : Float.NaN;
            float cobNext30 = values.length >= 5 ? values[4] : Float.NaN;
            Natives.setJournalIob(values[0], iobNext30, values[2], cobNext30, atMillis);
        } else if (Looper.myLooper() != Looper.getMainLooper()) {
            // Off the main thread null is authoritative (journal disabled or
            // empty); on the main thread it may just be a cold cache, so a
            // stored value is left to expire on its own.
            Natives.setJournalIob(Float.NaN, Float.NaN, Float.NaN, Float.NaN, atMillis);
        }
    }

    // [classicIob, eiob, cob] in units/grams, NaN marking "no data of that
    // kind"; null when unavailable. Public: the PRE_HIGH IOB-coverage check
    // in tk.glucodata.alerts reads the classic IOB through this bridge.
    public static float[] snapshot(long atMillis) {
        try {
            if (!snapshotResolved) {
                snapshotResolved = true;
                snapshotMethod = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
                        .getMethod("broadcastIobSnapshot", long.class);
            }
            if (snapshotMethod == null)
                return null;
            return (float[]) snapshotMethod.invoke(null, atMillis);
        } catch (Throwable e) {
            snapshotMethod = null;
            return null;
        }
    }

    private static final float MGDL_PER_MMOLL = 18.016f;

    // "IOB 4.2U · eIOB 3.1U · COB 24g" for the persistent notification, or
    // null when nothing requested is available. eIOB follows the journal
    // display toggle. With riskColored=true the journal segment is tinted
    // yellow/red when the uncovered IOB is projected (carb ratio + ISF) to
    // drop the current glucose below the target range / low alarm.
    static CharSequence notificationLine(android.content.SharedPreferences prefs, boolean showIob, boolean showCob,
            boolean riskColored, boolean darkTheme, float displayGlucose, boolean isMmol) {
        float[] values = snapshot(System.currentTimeMillis());
        if (values == null || values.length < 3)
            return null;
        StringBuilder line = new StringBuilder();
        if (showIob && !Float.isNaN(values[0])) {
            line.append("IOB ").append(formatUnits(values[0])).append("U");
            if (!Float.isNaN(values[1]) && prefs.getBoolean("dashboard_journal_eiob_display_enabled", true))
                line.append(" · eIOB ").append(formatUnits(values[1])).append("U");
        }
        if (showCob && !Float.isNaN(values[2])) {
            if (line.length() > 0)
                line.append(" · ");
            line.append("COB ").append(formatUnits(values[2])).append("g");
        }
        if (line.length() == 0)
            return null;
        if (!riskColored || values.length < 5)
            return line.toString();
        // Without carbs on board the coverage comparison is only meaningful
        // for users who actually log carbs — opt-in via its own setting.
        final boolean carbsOnBoard = !Float.isNaN(values[2]) && values[2] > 0f;
        if (!carbsOnBoard && !prefs.getBoolean("notification_iob_risk_without_cob", false))
            return line.toString();

        // Project with the 30-minute windowed quantities: only insulin
        // actually delivering soon counts, with matching carb credit —
        // total IOB with hours of tail must not scream red.
        final float toMgdl = isMmol ? MGDL_PER_MMOLL : 1.0f;
        final tk.glucodata.data.prediction.PredictionModelParameters profile =
                tk.glucodata.data.prediction.PredictionModelProfileStore.parametersAt(
                        prefs,
                        System.currentTimeMillis());
        final int risk = IobCobRisk.classify(
                values[3],
                values[4],
                profile.getCarbRatioGramsPerUnit(),
                profile.getInsulinSensitivityMgDlPerUnit(),
                displayGlucose * toMgdl,
                Natives.targetlow() * toMgdl,
                Natives.alarmverylow() * toMgdl);
        if (risk == IobCobRisk.NONE)
            return line.toString();
        final int tint = risk == IobCobRisk.OUT
                ? GlucoseRangeColors.valueOut(darkTheme)
                : GlucoseRangeColors.valueBorderline(darkTheme);
        android.text.SpannableStringBuilder spanned = new android.text.SpannableStringBuilder(line);
        spanned.setSpan(new android.text.style.ForegroundColorSpan(tint), 0, spanned.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spanned;
    }

    private static String formatUnits(float units) {
        if (units % 1f < 0.05f)
            return String.valueOf(Math.round(units));
        return String.format(java.util.Locale.getDefault(), "%.1f", units);
    }
}
