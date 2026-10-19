package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import kotlin.math.abs

/**
 * TrendAccess used to reach the TrendEngine by name at runtime. R8 keeps the class name but renames
 * the members (release mapping.txt: calculateTrend(java.util.List,boolean,boolean) -> a), so the
 * lookup threw in every minified build and calculateVelocity() silently degraded to a two-point
 * slope over the last two readings. Every consumer -- the notification arrow and the computed-trend
 * broadcast -- shipped that number as the app's trend.
 *
 * These tests pin the explicit provider path and, more importantly, that the fallback is a
 * *different* number: on a direction turn the two disagree on the sign, which is exactly what the
 * bug looked like in the field (a receiver's arrow pointing up while the app's own trend fell).
 *
 * Deliberately asserted on velocity rather than the rendered angle: the arrow mapping lives in
 * TrendArrowAngle, which is a deterministic function of velocity and is covered by its own tests,
 * so equal velocities imply equal angles.
 */
class TrendAccessProviderTests {

    /** The Flat state's dead zone in TrendEngine: |v| <= 0.5 mg/dL/min renders flat. */
    private val flatDeadZone = 0.5f

    private val t0 = 1_700_000_000_000L
    private fun p(minutesAgo: Long, value: Float) = GlucosePoint(t0 - minutesAgo * 60_000L, value, 0f)

    /** Falling for 20 minutes, then a single +8 uptick on the newest reading. Oldest-first. */
    private val turnSeries = listOf(p(20, 160f), p(15, 150f), p(10, 140f), p(5, 128f), p(0, 136f))

    /** Sub-dead-zone drift: the trend must read as flat, whichever way the noise leans. */
    private val flatSeries = listOf(p(20, 100f), p(15, 100.5f), p(10, 100f), p(5, 100.5f), p(0, 100f))

    private val engineProvider = TrendVelocityProvider { points, useRaw, isMmol ->
        TrendEngine.calculateTrend(points, useRaw, isMmol).velocity
    }

    private fun engineVelocity(points: List<GlucosePoint>) =
        TrendEngine.calculateTrend(points, false, false).velocity

    // ---- The divergence the bug rode on ----

    @Test
    fun engineAndTwoPointSlopeDisagreeOnADirectionTurn() {
        val engine = engineVelocity(turnSeries)
        val fallback = TrendAccess.resolve(null, turnSeries, false, false).velocity
        // The regression over the window still falls; the last pair alone rises.
        assertTrue("engine should read the 20-min trend as falling, was $engine", engine < 0f)
        assertTrue("two-point slope should read the last uptick as rising, was $fallback", fallback > 0f)
        // Opposite signs: the arrows literally point different ways.
        assertNotEquals(engine, fallback, 0.1f)
    }

    // ---- Provider registered ----

    @Test
    fun registeredProviderReturnsEngineVelocity() {
        val r = TrendAccess.resolve(engineProvider, turnSeries, false, false)
        assertFalse("provider present -> must not degrade", r.usedFallback)
        assertEquals(engineVelocity(turnSeries), r.velocity, 0.0001f)
    }

    @Test
    fun registeredProviderKeepsTheEngineSignNotTheFallbackSign() {
        val viaProvider = TrendAccess.resolve(engineProvider, turnSeries, false, false).velocity
        val viaFallback = TrendAccess.resolve(null, turnSeries, false, false).velocity
        assertTrue("provider path must keep falling", viaProvider < 0f)
        assertTrue("fallback path rises", viaFallback > 0f)
    }

    @Test
    fun flatDriftStaysInsideTheDeadZoneOnTheProviderPath() {
        val r = TrendAccess.resolve(engineProvider, flatSeries, false, false)
        assertFalse(r.usedFallback)
        assertEquals(engineVelocity(flatSeries), r.velocity, 0.0001f)
        assertTrue(
            "sub-dead-zone drift must not pick a direction, was ${r.velocity}",
            abs(r.velocity) <= flatDeadZone
        )
    }

    // ---- Provider missing / failing -> fallback, and it is flagged (the Log.e trigger) ----

    @Test
    fun missingProviderFallsBackAndFlagsIt() {
        val r = TrendAccess.resolve(null, turnSeries, false, false)
        assertTrue("no provider -> must be flagged so the caller can log loudly", r.usedFallback)
        // Two-point slope over the last 5 minutes: (136 - 128) / 5.
        assertEquals(1.6f, r.velocity, 0.0001f)
    }

    @Test
    fun throwingProviderFallsBackAndFlagsIt() {
        val boom = TrendVelocityProvider { _, _, _ -> throw IllegalStateException("boom") }
        val r = TrendAccess.resolve(boom, turnSeries, false, false)
        assertTrue(r.usedFallback)
        assertEquals(1.6f, r.velocity, 0.0001f)
    }

    @Test
    fun nonFiniteProviderResultFallsBackAndFlagsIt() {
        val nan = TrendVelocityProvider { _, _, _ -> Float.NaN }
        val r = TrendAccess.resolve(nan, turnSeries, false, false)
        assertTrue(r.usedFallback)
        assertEquals(1.6f, r.velocity, 0.0001f)
    }

    @Test
    fun tooFewPointsFallsBackToZero() {
        val r = TrendAccess.resolve(null, listOf(p(0, 100f)), false, false)
        assertTrue(r.usedFallback)
        assertEquals(0f, r.velocity, 0.0001f)
    }
}
