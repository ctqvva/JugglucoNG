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
 * Shared rotation math for the trend arrow: 45 degrees per mg/dL per minute,
 * vertical at +/-2 (the CGM arrow convention), rendered flat below the Flat
 * trend state threshold. Under 0.5 mg/dL per minute the sign of the rate is
 * noise — two honest estimators regularly disagree on it for the same data —
 * so a slow drift must not tilt the arrow in either direction.
 */
public final class TrendArrowAngle {
    public static final float DEGREES_PER_UNIT = 45f;
    public static final float FLAT_BELOW_RATE = 0.5f;

    private TrendArrowAngle() {
    }

    /**
     * Rotation in degrees for a rate in mg/dL per minute; negative result =
     * arrow tip up (rising), positive = down, matching the screen-space
     * rotation both renderers apply.
     */
    public static float rotationDegrees(float rateMgdlPerMin) {
        if (!Float.isFinite(rateMgdlPerMin))
            return 0f;

        float magnitude = Math.abs(rateMgdlPerMin);
        if (magnitude <= FLAT_BELOW_RATE)
            return 0f;

        if (magnitude < 1f) {
            float rotation =
                    2f * DEGREES_PER_UNIT * (magnitude - FLAT_BELOW_RATE);
            return rateMgdlPerMin > 0f ? -rotation : rotation;
        }

        float rotation = -rateMgdlPerMin * DEGREES_PER_UNIT;
        if (rotation < -90f)
            return -90f;
        if (rotation > 90f)
            return 90f;
        return rotation;
    }
}
