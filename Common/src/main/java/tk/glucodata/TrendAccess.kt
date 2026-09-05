package tk.glucodata

import kotlin.math.abs

/**
 * Bridge from the shared code to the app's trend estimator.
 *
 * This used to find the estimator by name at runtime. R8 keeps the class name but renames its
 * members -- mapping.txt of a release build reads
 * `tk.glucodata.logic.TrendEngine -> tk.glucodata.logic.TrendEngine:` with
 * `calculateTrend(java.util.List,boolean,boolean) -> a` -- so the method lookup threw in every
 * minified build. The failure was invisible: [calculateVelocity] just returned [fallbackVelocity],
 * a slope over the last two readings, and every caller (the notification arrow and the
 * computed-trend broadcast) shipped that number as if it were the app's trend. After a direction
 * turn the two disagree outright.
 *
 * A keep rule would paper over the symptom and stays fragile -- the commented-out "doesnt work"
 * attempts in proguard-rules.pro show how that goes here. The detour itself was the problem, so the
 * implementation is registered explicitly instead ([register], from the mobile Specific.start()),
 * leaving an ordinary interface call that R8 renames on both sides.
 */
object TrendAccess {
    private const val LOG_ID = "TrendAccess"

    @Volatile
    private var provider: TrendVelocityProvider? = null

    /** Registered at startup, before any reading can be drawn or broadcast. */
    @JvmStatic
    fun register(provider: TrendVelocityProvider) {
        this.provider = provider
    }

    /**
     * Whether the real estimator is available. Forecast alerts check this: a
     * two-point fallback slope is tolerable for an arrow, but scaled over a
     * 30-minute horizon it fabricates projections (a reboot-window -3 mg/dl/min
     * turned 125 mg/dl into a projected 33 and fired PRE_LOW).
     */
    @JvmStatic
    fun hasProvider(): Boolean = provider != null

    internal data class Resolution(val velocity: Float, val usedFallback: Boolean)

    /**
     * Pure decision, so the degraded path is testable without the Android log: the estimator's
     * velocity, or the fallback plus the flag that something is wrong.
     */
    internal fun resolve(
        provider: TrendVelocityProvider?,
        points: List<GlucosePoint>,
        useRaw: Boolean,
        isMmol: Boolean
    ): Resolution {
        if (provider != null) {
            val velocity = runCatching { provider.velocity(points, useRaw, isMmol) }.getOrNull()
            if (velocity != null && velocity.isFinite()) {
                return Resolution(velocity, usedFallback = false)
            }
        }
        return Resolution(fallbackVelocity(points, useRaw), usedFallback = true)
    }

    @JvmStatic
    fun calculateVelocity(points: List<GlucosePoint>, useRaw: Boolean, isMmol: Boolean): Float {
        val resolution = resolve(provider, points, useRaw, isMmol)
        if (resolution.usedFallback) {
            // Deliberately not behind doLog: without the provider every arrow and every outgoing
            // rate quietly carries a two-point slope instead of the app's trend. A value other
            // apps rotate their arrow by must never degrade in silence.
            Log.e(LOG_ID, "TrendVelocityProvider missing or unusable - degraded to two-point slope")
        }
        return resolution.velocity
    }

    private fun fallbackVelocity(points: List<GlucosePoint>, useRaw: Boolean): Float {
        if (points.size < 2) return 0f
        val last = points.last()
        val previous = points.dropLast(1).lastOrNull { candidate ->
            val value = if (useRaw) candidate.rawValue else candidate.value
            value.isFinite() && value > 0f
        } ?: return 0f
        val lastValue = if (useRaw) last.rawValue else last.value
        val prevValue = if (useRaw) previous.rawValue else previous.value
        if (!lastValue.isFinite() || !prevValue.isFinite() || lastValue <= 0f || prevValue <= 0f) {
            return 0f
        }
        val minutes = (last.timestamp - previous.timestamp) / 60000f
        if (!minutes.isFinite() || abs(minutes) < 0.1f) {
            return 0f
        }
        return (lastValue - prevValue) / minutes
    }
}
