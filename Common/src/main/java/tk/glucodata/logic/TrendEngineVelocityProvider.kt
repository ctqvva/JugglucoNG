package tk.glucodata.logic

import tk.glucodata.GlucosePoint
import tk.glucodata.TrendVelocityProvider

/**
 * Hands the shared code the very estimator the UI draws its arrow from, so the notification arrow
 * and the outgoing broadcast rate cannot drift apart from the dashboard. Registered once in
 * Specific.start(); see [tk.glucodata.TrendAccess] for why this is not looked up reflectively.
 */
object TrendEngineVelocityProvider : TrendVelocityProvider {
    override fun velocity(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): Float =
        TrendEngine.calculateTrend(points, useRaw, isMmol).velocity
}
