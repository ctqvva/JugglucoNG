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
 * Glucose-aware IOB-vs-COB risk: warns when the insulin on board that is not
 * covered by carbs on board is projected (via the user's carb ratio and
 * insulin sensitivity) to drop the current glucose below the target range
 * ({@link #BORDERLINE}) or below very low ({@link #OUT}). A correction
 * bolus while high does not warn — the projection still lands in range.
 * All glucose inputs in mg/dL.
 */
public final class IobCobRisk {
    public static final int NONE = 0;
    public static final int BORDERLINE = 1;
    public static final int OUT = 2;

    private static final float DEFAULT_CARB_RATIO_G_PER_U = 10.0f;
    private static final float DEFAULT_ISF_MGDL_PER_U = 54.0f;

    private IobCobRisk() {
    }

    public static int classify(
            float iobUnits,
            float cobGrams,
            float carbRatioGramsPerUnit,
            float isfMgdlPerUnit,
            float glucoseMgdl,
            float targetLowMgdl,
            float veryLowMgdl) {
        if (!Float.isFinite(iobUnits) || iobUnits <= 0.0f)
            return NONE;
        if (!Float.isFinite(glucoseMgdl) || glucoseMgdl <= 0.0f)
            return NONE;

        final float cob = Float.isFinite(cobGrams) && cobGrams > 0.0f ? cobGrams : 0.0f;
        final float carbRatio = Float.isFinite(carbRatioGramsPerUnit) && carbRatioGramsPerUnit > 0.0f
                ? carbRatioGramsPerUnit
                : DEFAULT_CARB_RATIO_G_PER_U;
        final float isf = Float.isFinite(isfMgdlPerUnit) && isfMgdlPerUnit > 0.0f
                ? isfMgdlPerUnit
                : DEFAULT_ISF_MGDL_PER_U;
        final float targetLow = Float.isFinite(targetLowMgdl) && targetLowMgdl > 0.0f
                ? targetLowMgdl
                : GlucoseRangeColors.DEFAULT_LOW_MGDL;
        float veryLow = Float.isFinite(veryLowMgdl) && veryLowMgdl > 0.0f
                ? veryLowMgdl
                : GlucoseRangeColors.DEFAULT_VERY_LOW_MGDL;
        veryLow = Math.min(veryLow, targetLow);

        final float uncoveredUnits = Math.max(0.0f, iobUnits - (cob / carbRatio));
        if (uncoveredUnits <= 0.0f)
            return NONE;

        final float projectedMgdl = glucoseMgdl - (uncoveredUnits * isf);
        if (projectedMgdl < veryLow)
            return OUT;
        if (projectedMgdl < targetLow)
            return BORDERLINE;
        return NONE;
    }
}
