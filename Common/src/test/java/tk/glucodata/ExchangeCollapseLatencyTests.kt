package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a sensor that reports every minute through the same pieces
 * SuperGattCallback.emitExchangeOutputs uses to decide what an exchange output sends:
 * exchangeSmoothingMode -> prepareRecentPointsForCurrent -> exchangeTargetTimeMillis ->
 * resolveFromLive -> ExchangeUpdateGate.
 *
 * "Collapse into chunks" thins how OFTEN an exchange output is fed. It must never change WHAT
 * is sent: the newest reading, under its own timestamp. This applies uniformly to every exchange
 * destination, including the xDrip/xInfuus loop feed AAPS doses from — it used to be exempted
 * from collapse because the old design sent a stale chunk point instead of the newest reading;
 * now that ExchangeUpdateGate always carries the newest reading's own timestamp, there is no
 * reason left to keep it uncollapsed (see [tk.glucodata.ExchangeOutputPolicy]'s LOOP_FEED
 * destination).
 *
 * Shape taken from the field trace: readings every 60 s, each one reaching the callback ~4 s
 * before its own timestamp, smoothing window 3 min. handleGlucoseResultInternal hands the
 * reading to Room (storeCurrentReadingAsync, asynchronous on Dispatchers.IO) before dowithglucose
 * reaches emitExchangeOutputs. The replay models the usual case, where that row is already in
 * history when the payload is resolved; the case where it has not landed yet is not covered here.
 */
class ExchangeCollapseLatencyTests {
    private val minute = 60_000L
    private val arrivalLeadMs = 4_000L
    private val sensorId = "test-sensor"

    /** Epoch-aligned to every collapse interval used below (2, 3, 4, 5 min). */
    private val base = 60L * 60 * 60 * minute

    private val steadyState = 6 // skip the warm-up before the first bucket completes

    private data class Settings(
        val smoothingMinutes: Int = 3,
        val graphOnly: Boolean = false,
        val exchangeOnly: Boolean = true,
        val collapse: Boolean = true
    )

    private data class Emission(
        val readingTimeMs: Long,
        val payloadTimeMs: Long,
        val payloadValue: Float,
        val emitted: Boolean
    )

    private fun replay(
        settings: Settings,
        readings: Int = 18,
        valueAt: (Int) -> Float = { 100f + it }
    ): List<Emission> {
        val mode = CurrentDisplaySource.exchangeSmoothingMode(
            settings.smoothingMinutes, settings.graphOnly, settings.exchangeOnly, settings.collapse
        )
        val intervalMinutes = DataSmoothing.exchangeThrottleIntervalMinutes(
            settings.smoothingMinutes, settings.graphOnly, settings.exchangeOnly, settings.collapse
        )
        val gate = ExchangeUpdateGate()
        val history = ArrayList<GlucosePoint>()
        val out = ArrayList<Emission>()
        for (k in 0 until readings) {
            val stamp = base + k * minute
            history += GlucosePoint(stamp, valueAt(k), 0f)
            val current = CurrentGlucoseSource.Snapshot(
                timeMillis = stamp,
                valueText = "",
                numericValue = valueAt(k),
                rawNumericValue = Float.NaN,
                rate = 0f,
                sensorId = sensorId,
                sensorGen = 0,
                index = k,
                source = "test"
            )
            val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
                recentPoints = history.toList(),
                current = current,
                historyStart = 0L,
                viewMode = 0,
                smoothAllData = mode.smoothAllData,
                smoothingMinutes = mode.smoothingMinutes,
                collapseChunks = mode.collapseChunks,
                nowMillis = stamp - arrivalLeadMs
            )
            val target = CurrentDisplaySource.exchangeTargetTimeMillis(mode.collapseChunks, processed, stamp)
            val snapshot = requireNotNull(
                CurrentDisplaySource.resolveFromLive(
                    liveValueText = null,
                    liveNumericValue = valueAt(k),
                    rate = 0f,
                    targetTimeMillis = target,
                    sensorId = sensorId,
                    sensorGen = 0,
                    index = k,
                    source = "test",
                    recentPoints = processed,
                    viewMode = 0,
                    isMmol = false
                )
            )
            out += Emission(
                readingTimeMs = stamp,
                payloadTimeMs = snapshot.timeMillis,
                payloadValue = snapshot.primaryValue,
                emitted = gate.shouldEmit(sensorId, snapshot.timeMillis, intervalMinutes)
            )
        }
        return out
    }

    // --- what the old design did, kept so the reason for the change stays on record ----------

    @Test
    fun aCollapsedSnapshotCarriesTheLastPointOfThePreviousChunk() {
        // Not used for exchange outputs any more: this is why. Two minutes old at a 3 min window.
        val collapsed = replayCollapsedSnapshot()
        assertTrue(collapsed.isNotEmpty())
        collapsed.forEach { assertEquals(2 * minute, it) }
    }

    /** Ages (reading time - snapshot time) of the readings whose collapsed snapshot moved on. */
    private fun replayCollapsedSnapshot(): List<Long> {
        val history = ArrayList<GlucosePoint>()
        val ages = ArrayList<Long>()
        var lastSnapshotTime = -1L
        for (k in 0 until 18) {
            val stamp = base + k * minute
            val current = CurrentGlucoseSource.Snapshot(
                timeMillis = stamp, valueText = "", numericValue = 100f + k, rawNumericValue = Float.NaN,
                rate = 0f, sensorId = sensorId, sensorGen = 0, index = k, source = "test"
            )
            val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
                history.toList(), current, 0L, 0, true, 3, true, stamp - arrivalLeadMs
            )
            val time = CurrentDisplaySource.exchangeTargetTimeMillis(true, processed, stamp)
            if (k >= steadyState && time != lastSnapshotTime) ages += stamp - time
            lastSnapshotTime = time
            history += GlucosePoint(stamp, 100f + k, 0f)
        }
        return ages
    }

    // --- what exchange outputs do now ----------------------------------------------------------

    @Test
    fun chunkedTarget_sendsTheFirstReadingOfEachIntervalWithItsOwnTime() {
        val run = replay(Settings()).drop(steadyState)

        val sent = run.filter { it.emitted }
        assertEquals("12 readings, interval 3 min", 4, sent.size)
        sent.forEach {
            assertEquals("newest reading, not the previous chunk", it.readingTimeMs, it.payloadTimeMs)
            assertEquals("first reading of its interval", 0L, it.readingTimeMs % (3 * minute))
        }
    }

    @Test
    fun chunkedTarget_neverSendsTheSameIntervalTwice() {
        val sent = replay(Settings()).filter { it.emitted }

        assertEquals(sent.size, sent.map { it.readingTimeMs / (3 * minute) }.toSet().size)
    }

    @Test
    fun collapseOff_sendsEveryReadingWithItsOwnTimestamp() {
        val run = replay(Settings(collapse = false)).drop(steadyState)

        assertEquals(12, run.count { it.emitted })
        run.forEach { assertEquals(it.readingTimeMs, it.payloadTimeMs) }
    }

    @Test
    fun valueIsSmoothedFromTheWindowBehindTheReading() {
        val noisy = { k: Int -> 100f + k + if (k % 2 == 0) 4f else -4f }

        // 5 min, not the default 3: at 3 the boundary fit spans only the last two readings and
        // returns the newest one unchanged, so an unsmoothed payload would pass too.
        val run = replay(Settings(smoothingMinutes = 5), valueAt = noisy).drop(steadyState)

        run.forEachIndexed { i, e ->
            val k = steadyState + i
            // Built from readings 0..k only: nothing after the reading may shape its value.
            val upToReading = (0..k).map { GlucosePoint(base + it * minute, noisy(it), 0f) }
            val expected = DataSmoothing.smoothNativePoints(
                upToReading, 5, false, e.readingTimeMs - arrivalLeadMs
            ).last().value
            assertEquals("reading $k", expected, e.payloadValue, 0.001f)
            assertNotEquals("reading $k is sent smoothed, not raw", noisy(k), e.payloadValue, 0.001f)
        }
    }

    // --- settings -> decisions ------------------------------------------------------------------

    @Test
    fun theExchangeSnapshotIsNeverCollapsed() {
        for (graphOnly in listOf(false, true)) for (exchangeOnly in listOf(false, true))
            for (collapse in listOf(false, true)) {
                val mode = CurrentDisplaySource.exchangeSmoothingMode(3, graphOnly, exchangeOnly, collapse)
                assertFalse("graphOnly=$graphOnly exchangeOnly=$exchangeOnly collapse=$collapse", mode.collapseChunks)
            }
    }

    @Test
    fun throttleInterval_followsTheCollapseSetting() {
        fun interval(min: Int, graphOnly: Boolean, exchangeOnly: Boolean, collapse: Boolean) =
            DataSmoothing.exchangeThrottleIntervalMinutes(min, graphOnly, exchangeOnly, collapse)

        assertEquals(3, interval(3, graphOnly = false, exchangeOnly = true, collapse = true))
        assertEquals(5, interval(13, graphOnly = false, exchangeOnly = true, collapse = true)) // MAX_CHUNK_INTERVAL_MINUTES
        assertEquals(3, interval(3, graphOnly = true, exchangeOnly = false, collapse = true)) // d7f827240 kept
        assertEquals(0, interval(3, graphOnly = false, exchangeOnly = true, collapse = false))
        assertEquals(0, interval(0, graphOnly = false, exchangeOnly = true, collapse = true))
    }

    // --- the gate -------------------------------------------------------------------------------

    @Test
    fun gate_withoutAnIntervalLetsEverythingThrough() {
        val gate = ExchangeUpdateGate()
        repeat(3) { assertTrue(gate.shouldEmit("a", base + it * minute, 0)) }
        assertTrue(gate.shouldEmit("a", base, 0)) // same time again: not deduped
    }

    @Test
    fun gate_letsOnePayloadPerIntervalThrough_perSensor() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base, 3))
        assertFalse(gate.shouldEmit("a", base + minute, 3))
        assertFalse(gate.shouldEmit("a", base + 2 * minute, 3))
        assertTrue(gate.shouldEmit("a", base + 3 * minute, 3))
        assertTrue("other sensor has its own interval", gate.shouldEmit("b", base + minute, 3))
    }

    @Test
    fun gate_doesNotDedupeAnUnusableTimestamp() {
        val gate = ExchangeUpdateGate()
        assertTrue(gate.shouldEmit("a", 0L, 3))
        assertTrue(gate.shouldEmit("a", 0L, 3))
    }

    @Test
    fun gate_ignoresAReadingThatArrivesOutOfOrder() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base + 3 * minute, 3))
        // A backfilled reading from the previous interval: not sent, and it must not make the
        // gate forget that this interval was already sent.
        assertFalse(gate.shouldEmit("a", base + minute, 3))
        assertFalse("same interval as the first send", gate.shouldEmit("a", base + 4 * minute, 3))
        assertTrue("next interval", gate.shouldEmit("a", base + 6 * minute, 3))
    }

    @Test
    fun gate_stillSendsTheFirstReadingItSeesEvenIfItIsOld() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base + minute, 3))
        assertFalse(gate.shouldEmit("a", base, 3))
    }

    @Test
    fun gate_outOfOrderOnOneSensorDoesNotAffectAnother() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base + 3 * minute, 3))
        assertTrue(gate.shouldEmit("b", base + minute, 3))
        assertFalse(gate.shouldEmit("a", base + minute, 3))
    }

    @Test
    fun gate_sendsTheNextReadingAfterTheIntervalGrows() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base, 3))
        // 3 -> 5 minutes: the 5-minute count is far below the stored 3-minute one. Compared
        // across lengths it would stay behind for years and the outputs would go silent.
        assertTrue("first reading after the change sends", gate.shouldEmit("a", base + minute, 5))
        assertFalse("then one per 5-minute interval", gate.shouldEmit("a", base + 2 * minute, 5))
    }

    @Test
    fun gate_sendsTheNextReadingAfterTheIntervalShrinks() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base, 5))
        assertTrue("first reading after the change sends", gate.shouldEmit("a", base + minute, 3))
        assertTrue("next 3-minute interval", gate.shouldEmit("a", base + 3 * minute + minute, 3))
    }

    @Test
    fun gate_turningCollapseOffAndOnAgainKeepsGoing() {
        val gate = ExchangeUpdateGate()

        assertTrue(gate.shouldEmit("a", base, 3))
        assertTrue("collapse off: everything sends", gate.shouldEmit("a", base + minute, 0))
        assertTrue("collapse back on, a later interval", gate.shouldEmit("a", base + 6 * minute, 3))
    }

    // --- production exchange destination policy ------------------------------------------------

    @Test
    fun destinationGatesDoNotShareABucketAcrossMinuteGateJitter() {
        val policy = ExchangeOutputPolicy()
        val boundary = base
        val early = policy.decide(
            sensorId = sensorId,
            payloadTimeMs = boundary - 20_000L,
            intervalMinutes = 5,
            shouldBroadcastMinuteUpdate = true,
            jugglucoEnabled = false,
            outboundApiEnabled = true,
            wearIntEnabled = true,
            gadgetbridgeEnabled = true,
            loopFeedEnabled = false
        )
        assertTrue("outbound API is eligible on every reading", early.sendOutboundApi)
        assertTrue("WearInt sends in the previous bucket", early.sendWearInt)
        assertTrue("Gadgetbridge sends in the previous bucket", early.sendGadgetbridge)

        val fastDestinationUsesCurrentBucket = policy.decide(
            sensorId = sensorId,
            payloadTimeMs = boundary + 30_000L,
            intervalMinutes = 5,
            shouldBroadcastMinuteUpdate = false,
            jugglucoEnabled = false,
            outboundApiEnabled = true,
            wearIntEnabled = true,
            gadgetbridgeEnabled = true,
            loopFeedEnabled = false
        )
        assertTrue("the fast destination uses the new bucket", fastDestinationUsesCurrentBucket.sendOutboundApi)
        assertFalse("the slow destinations are still behind the minute gate", fastDestinationUsesCurrentBucket.sendWearInt)
        assertFalse("the slow destinations are still behind the minute gate", fastDestinationUsesCurrentBucket.sendGadgetbridge)

        val sameBucketAfterJitter = policy.decide(
            sensorId = sensorId,
            payloadTimeMs = boundary + 90_000L,
            intervalMinutes = 5,
            shouldBroadcastMinuteUpdate = true,
            jugglucoEnabled = false,
            outboundApiEnabled = true,
            wearIntEnabled = true,
            gadgetbridgeEnabled = true,
            loopFeedEnabled = false
        )
        assertFalse("API already used its own bucket", sameBucketAfterJitter.sendOutboundApi)
        assertTrue("WearInt gets the first eligible callback in its bucket", sameBucketAfterJitter.sendWearInt)
        assertTrue("Gadgetbridge gets the first eligible callback in its bucket", sameBucketAfterJitter.sendGadgetbridge)
    }

    @Test
    fun disabledDestinationDoesNotReserveItsBucketBeforeItIsEnabled() {
        val policy = ExchangeOutputPolicy()

        val disabled = policy.decide(
            sensorId = sensorId,
            payloadTimeMs = base + 30_000L,
            intervalMinutes = 5,
            shouldBroadcastMinuteUpdate = true,
            jugglucoEnabled = false,
            outboundApiEnabled = true,
            wearIntEnabled = false,
            gadgetbridgeEnabled = false,
            loopFeedEnabled = false
        )
        assertTrue("the enabled API may consume its own bucket", disabled.sendOutboundApi)
        assertFalse(disabled.sendGadgetbridge)

        val enabled = policy.decide(
            sensorId = sensorId,
            payloadTimeMs = base + 90_000L,
            intervalMinutes = 5,
            shouldBroadcastMinuteUpdate = true,
            jugglucoEnabled = false,
            outboundApiEnabled = true,
            wearIntEnabled = false,
            gadgetbridgeEnabled = true,
            loopFeedEnabled = false
        )
        assertTrue("the first enabled reading starts Gadgetbridge's bucket", enabled.sendGadgetbridge)
    }

    @Test
    fun destinationPolicyKeepsIntervalChangeAndOutOfOrderRules() {
        val policy = ExchangeOutputPolicy()

        assertTrue(
            policy.decide(sensorId, base, 3, true, false, true, false, false, false).sendOutboundApi
        )
        assertFalse(
            "an older reading cannot reopen a sent interval",
            policy.decide(sensorId, base - minute, 3, true, false, true, false, false, false).sendOutboundApi
        )
        assertFalse(
            "the same interval remains closed",
            policy.decide(sensorId, base + minute, 3, true, false, true, false, false, false).sendOutboundApi
        )
        assertTrue(
            "changing the interval starts its count afresh",
            policy.decide(sensorId, base + minute, 5, true, false, true, false, false, false).sendOutboundApi
        )
    }

    // --- loop feed as a destination: collapses like everything else, opt-out removed -----------

    @Test
    fun loopFeedDestinationCollapsesLikeOutboundApi() {
        val policy = ExchangeOutputPolicy()

        val first = policy.decide(
            sensorId = sensorId, payloadTimeMs = base, intervalMinutes = 3,
            shouldBroadcastMinuteUpdate = true, jugglucoEnabled = false, outboundApiEnabled = false,
            wearIntEnabled = false, gadgetbridgeEnabled = false, loopFeedEnabled = true
        )
        assertTrue("first reading of the interval sends", first.sendLoopFeed)

        val sameInterval = policy.decide(
            sensorId = sensorId, payloadTimeMs = base + minute, intervalMinutes = 3,
            shouldBroadcastMinuteUpdate = true, jugglucoEnabled = false, outboundApiEnabled = false,
            wearIntEnabled = false, gadgetbridgeEnabled = false, loopFeedEnabled = true
        )
        assertFalse("same interval stays closed, same as every other destination", sameInterval.sendLoopFeed)

        val nextInterval = policy.decide(
            sensorId = sensorId, payloadTimeMs = base + 3 * minute, intervalMinutes = 3,
            shouldBroadcastMinuteUpdate = true, jugglucoEnabled = false, outboundApiEnabled = false,
            wearIntEnabled = false, gadgetbridgeEnabled = false, loopFeedEnabled = true
        )
        assertTrue("next interval sends", nextInterval.sendLoopFeed)
    }

    @Test
    fun loopFeedDestinationDisabledNeverSends() {
        val policy = ExchangeOutputPolicy()

        val decision = policy.decide(
            sensorId = sensorId, payloadTimeMs = base, intervalMinutes = 3,
            shouldBroadcastMinuteUpdate = true, jugglucoEnabled = false, outboundApiEnabled = false,
            wearIntEnabled = false, gadgetbridgeEnabled = false, loopFeedEnabled = false
        )
        assertFalse(decision.sendLoopFeed)
    }

    @Test
    fun loopFeedDestinationRespectsTheMinuteGateLikeWearIntAndGadgetbridge() {
        val policy = ExchangeOutputPolicy()

        val behindTheMinuteGate = policy.decide(
            sensorId = sensorId, payloadTimeMs = base + 30_000L, intervalMinutes = 3,
            shouldBroadcastMinuteUpdate = false, jugglucoEnabled = false, outboundApiEnabled = false,
            wearIntEnabled = false, gadgetbridgeEnabled = false, loopFeedEnabled = true
        )
        assertFalse("not eligible this callback", behindTheMinuteGate.sendLoopFeed)
    }
}
