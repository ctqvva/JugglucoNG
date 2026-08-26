package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the forecast rearm rework: the episode hangs on the
 * MEASURED value, ends on recovery past the margin or on a strong refutation,
 * and never flaps on projection jitter. Field case: PRE_HIGH threshold
 * 234 mg/dl fired three times within an hour at measured 221/226/218 because
 * the old episode survival hung on the projection with a 4 mg/dl margin.
 */
class ForecastRearmPolicyTests {

    private val activeConfig: (AlertConfig) -> Boolean = { true }

    private fun preHighConfig(rearmMargin: Float? = null) = AlertConfig(
        type = AlertType.PRE_HIGH,
        enabled = true,
        threshold = 234f,
        forecastMinutes = 30,
        rearmMargin = rearmMargin ?: 20f
    )

    private class Harness(private val config: AlertConfig) {
        private val episodes = AlertEpisodeState<AlertType>()
        var fires = 0
            private set

        /** One evaluator tick; counts a fire on episode entry like the runtime does. */
        fun tick(glucose: Float, rate: Float): AlertEpisodeTransition<AlertType> {
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = glucose,
                rate = rate,
                configs = mapOf(config.type to config),
                alertTypes = listOf(config.type),
                isMmol = false,
                isConfigActive = { true },
                wasConditionActive = episodes::isActive
            )
            val transition = episodes.update(active.keys)
            if (transition.shouldTryFire(config.type)) {
                fires++
            }
            return transition
        }
    }

    @Test
    fun fieldCaseFiresOnceWhileTheValueStaysUndecided() {
        // 234 threshold, measured 218-226, fluctuating rate, one hour: the
        // original episode must survive it all - one alarm, not three.
        val h = Harness(preHighConfig())
        h.tick(221f, 1.0f)    // 21:30 entry, projected 251
        h.tick(226f, 0.2f)    // projected 232 - jitter below threshold
        h.tick(224f, -0.4f)   // mild negative wobble, projected 212
        h.tick(218f, 0.6f)    // projected 236 again
        h.tick(226f, 0.9f)    // projected 253
        h.tick(220f, -0.2f)   // projected 214
        assertEquals(1, h.fires)
    }

    @Test
    fun recoveryPastTheMarginEndsTheEpisodeAndAllowsExactlyOneNewAlarm() {
        val h = Harness(preHighConfig())
        h.tick(221f, 1.0f)                       // fire #1
        val cleared = h.tick(210f, 0f)           // 210 <= 234 - 20: recovered
        assertTrue(AlertType.PRE_HIGH in cleared.cleared)
        h.tick(220f, 1.0f)                       // fresh rise, projected 250: fire #2
        h.tick(222f, 0.9f)                       // same episode, no extra fire
        assertEquals(2, h.fires)
    }

    @Test
    fun crossingTheThresholdHandsOverToHighWithoutAnExtraForecastFire() {
        val preHigh = preHighConfig()
        val high = AlertConfig(AlertType.HIGH, enabled = true, threshold = 234f)
        val episodes = AlertEpisodeState<AlertType>()
        val types = listOf(AlertType.HIGH, AlertType.PRE_HIGH)

        fun tick(glucose: Float, rate: Float): AlertEpisodeTransition<AlertType> {
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = glucose,
                rate = rate,
                configs = mapOf(AlertType.PRE_HIGH to preHigh, AlertType.HIGH to high),
                alertTypes = types,
                isMmol = false,
                isConfigActive = { true },
                wasConditionActive = episodes::isActive
            )
            return episodes.update(active.keys)
        }

        assertTrue(tick(230f, 1.0f).shouldTryFire(AlertType.PRE_HIGH))
        val crossed = tick(238f, 0.5f)
        assertTrue(crossed.shouldTryFire(AlertType.HIGH))
        assertFalse(AlertType.PRE_HIGH in crossed.entered)
    }

    @Test
    fun projectionSwingsAtConstantMeasuredValueChangeNothing() {
        val h = Harness(preHighConfig())
        h.tick(225f, 1.0f)  // entry, fire
        // Constant measured value, rate swinging: projections 255/219/240/213.
        val t1 = h.tick(225f, 1.0f)
        val t2 = h.tick(225f, -0.2f)
        val t3 = h.tick(225f, 0.5f)
        val t4 = h.tick(225f, -0.4f)
        listOf(t1, t2, t3, t4).forEach {
            assertTrue(it.entered.isEmpty())
            assertTrue(it.cleared.isEmpty())
        }
        assertEquals(1, h.fires)
    }

    @Test
    fun strongDirectionFlipFalsifiesTheForecastImmediately() {
        // Field case, other direction: 237 mg/dl, double down arrow
        // (-2 mg/dl/min), threshold 234. The projection (177) is refuted; the
        // episode must end at once instead of retrying against the curve.
        val h = Harness(preHighConfig())
        h.tick(230f, 1.0f)  // entry
        val refuted = h.tick(237f, -2.0f)
        assertTrue(AlertType.PRE_HIGH in refuted.cleared)
        assertTrue(
            ForecastThresholdPolicy.isFalsified(
                type = AlertType.PRE_HIGH,
                projectedValue = 177f,
                threshold = 234f,
                rate = -2.0f,
                margin = 20f,
                isMmol = false,
                forecastMinutes = 30
            )
        )
        // A mild wobble is not a refutation.
        assertFalse(
            ForecastThresholdPolicy.isFalsified(
                type = AlertType.PRE_HIGH,
                projectedValue = 212f,
                threshold = 234f,
                rate = -0.4f,
                margin = 20f,
                isMmol = false,
                forecastMinutes = 30
            )
        )
    }

    @Test
    fun preLowFalsificationMirrorsPreHigh() {
        assertTrue(
            ForecastThresholdPolicy.isFalsified(
                type = AlertType.PRE_LOW,
                projectedValue = 130f,
                threshold = 70f,
                rate = 2.0f,
                margin = 20f,
                isMmol = false,
                forecastMinutes = 30
            )
        )
        assertFalse(
            ForecastThresholdPolicy.isFalsified(
                type = AlertType.PRE_LOW,
                projectedValue = 95f,
                threshold = 70f,
                rate = 0.3f,
                margin = 20f,
                isMmol = false,
                forecastMinutes = 30
            )
        )
    }

    @Test
    fun marginZeroRestoresTheOldBehaviour() {
        // Explicit opt-out: with margin 0 the old projection-carried survival
        // (4 mg/dl legacy margin) applies, flapping and all - the documented
        // regression path for existing users.
        val h = Harness(preHighConfig(rearmMargin = 0f))
        h.tick(221f, 1.0f)    // fire #1, projected 251
        h.tick(226f, 0.2f)    // projected 232 > 230: old rule keeps it alive
        h.tick(224f, -0.4f)   // current 224 <= 230, projected 212 <= 230: old rule ends it
        h.tick(226f, 0.5f)    // re-entry, projected 241: fire #2 - the old flapping
        assertEquals(2, h.fires)
    }

    // --- Hard-threshold rearm margin (LOW/HIGH/VERY_LOW/VERY_HIGH) ---

    private class HardHarness(private val config: AlertConfig) {
        private val episodes = AlertEpisodeState<AlertType>()
        var fires = 0
            private set

        fun tick(glucose: Float): AlertEpisodeTransition<AlertType> {
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = glucose,
                rate = 0f,
                configs = mapOf(config.type to config),
                alertTypes = listOf(config.type),
                isMmol = false,
                isConfigActive = { true },
                wasConditionActive = episodes::isActive
            )
            val transition = episodes.update(active.keys)
            if (transition.shouldTryFire(config.type)) {
                fires++
            }
            return transition
        }
    }

    @Test
    fun oneSampleExactlyOnTheThresholdDoesNotEndAHardEpisode() {
        // Field trace: VERY_HIGH threshold 280 fired twice while the value
        // never went below 280 - the single reading AT 280 ended the episode
        // under the strict compare and 281 re-entered.
        val config = AlertConfig(
            AlertType.VERY_HIGH,
            enabled = true,
            threshold = 280f,
            rearmMargin = 10f
        )
        val h = HardHarness(config)
        h.tick(281f)   // entry, fire
        h.tick(287f)
        val onTheLine = h.tick(280f)   // exactly threshold: stays active
        assertTrue(onTheLine.cleared.isEmpty())
        h.tick(281f)   // same episode, no second fire
        assertEquals(1, h.fires)
    }

    @Test
    fun hardEpisodeEndsOnlyPastTheRearmMargin() {
        val config = AlertConfig(
            AlertType.VERY_HIGH,
            enabled = true,
            threshold = 280f,
            rearmMargin = 10f
        )
        val h = HardHarness(config)
        h.tick(281f)
        assertTrue(h.tick(272f).cleared.isEmpty())   // within margin: still active
        assertTrue(AlertType.VERY_HIGH in h.tick(269f).cleared)  // past 280-10: over
        h.tick(281f)   // fresh entry
        assertEquals(2, h.fires)
    }

    @Test
    fun lowSideMirrorsWithThePlusMargin() {
        val config = AlertConfig(
            AlertType.LOW,
            enabled = true,
            threshold = 70f,
            rearmMargin = 10f
        )
        val h = HardHarness(config)
        h.tick(69f)                                   // entry
        assertTrue(h.tick(70f).cleared.isEmpty())     // exactly threshold: active
        assertTrue(h.tick(75f).cleared.isEmpty())     // within margin: active
        assertTrue(AlertType.LOW in h.tick(81f).cleared)  // past 70+10: recovered
        assertEquals(1, h.fires)
    }

    @Test
    fun hardMarginZeroKeepsTheStrictCompare() {
        val config = AlertConfig(
            AlertType.VERY_HIGH,
            enabled = true,
            threshold = 280f,
            rearmMargin = 0f
        )
        val h = HardHarness(config)
        h.tick(281f)
        assertTrue(AlertType.VERY_HIGH in h.tick(280f).cleared)  // old behaviour
        h.tick(281f)
        assertEquals(2, h.fires)
    }

    @Test
    fun rearmCooldownExtendsButNeverShortensTheBuiltInFloor() {
        val fiveMinutes = 5L * 60L * 1000L
        assertEquals(fiveMinutes, AlertStateTracker.effectiveRearmCooldownMs(null))
        assertEquals(
            fiveMinutes,
            AlertStateTracker.effectiveRearmCooldownMs(preHighConfig().copy(rearmMinIntervalMinutes = 0))
        )
        assertEquals(
            fiveMinutes,
            AlertStateTracker.effectiveRearmCooldownMs(preHighConfig().copy(rearmMinIntervalMinutes = 3))
        )
        assertEquals(
            20L * 60L * 1000L,
            AlertStateTracker.effectiveRearmCooldownMs(preHighConfig().copy(rearmMinIntervalMinutes = 20))
        )
    }
}
