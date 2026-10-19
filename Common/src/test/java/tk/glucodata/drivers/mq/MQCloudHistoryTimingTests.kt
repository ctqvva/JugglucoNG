package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQCloudHistoryTimingTests {
    private val receivedAt = 1789374278299L
    private val anchor = MQCloudHistoryTiming.Anchor(443, receivedAt)
    private val cloudOrigin = 1789338811000L
    // The trace reports 189 records, 249..438 (one missing), with these endpoints.
    // The internal missing position is illustrative; no missing glucose is synthesized.
    private val history = (249..438).filter { it != 250 }.map {
        MQBootstrapHistoryPoint(cloudOrigin + it * 180_000L, it, 64.8f)
    }

    @Test fun capturedFutureTimelineIsRecoveredAgainstLiveCounterWithoutChangingValues() {
        assertEquals(1789383631000L, history.first().timestampMs)
        assertEquals(1789417651000L, history.last().timestampMs)
        val prepared = prepare(history)
        assertTrue(prepared.estimated)
        assertEquals(189, prepared.history.size)
        assertEquals(receivedAt - (443 - 249) * 180_000L, prepared.history.first().timestampMs)
        assertEquals(receivedAt - 5 * 180_000L, prepared.history.last().timestampMs)
        assertEquals(history.map { it.packetIndex }, prepared.history.map { it.packetIndex })
        assertEquals(history.map { it.glucoseMgdl }, prepared.history.map { it.glucoseMgdl })
        assertFalse(prepared.history.any { it.packetIndex == 250 })
    }

    @Test fun futureHistoryWaitsForDirectBleEvidenceInsteadOfUsingRestoredCounter() {
        val result = MQCloudHistoryTiming.prepare(history, null, receivedAt, 3)
        assertTrue(result.deferred)
        assertTrue(result.history.isEmpty())
    }

    @Test fun alreadyValidHistoryRetainsItsExactTimestampsWithoutALiveAnchor() {
        val valid = history.map { it.copy(timestampMs = it.timestampMs - 44_280_000L) }
        val prepared = MQCloudHistoryTiming.prepare(valid, null, receivedAt, 3)
        assertEquals(valid, prepared.history)
        assertFalse(prepared.estimated)
        assertFalse(prepared.deferred)
    }

    @Test fun inconsistentCadenceAndMixedPacketCyclesAreNotShiftedIntoThePast() {
        assertTrue(prepare(history.mapIndexed { index, point ->
            if (index == 0) point.copy(timestampMs = point.timestampMs + 120_000L) else point
        }).history.isEmpty())
        assertTrue(prepare(history + history.last().copy(packetIndex = 444)).history.isEmpty())
        assertTrue(prepare(history + history.first().copy(packetIndex = 0)).history.isEmpty())
        assertTrue(prepare(history.take(1)).history.isEmpty())
        assertTrue(prepare(listOf(history.first(), history.first())).history.isEmpty())
    }

    @Test fun malformedAnchorAndUnsupportedCadenceCannotCreateATimeline() {
        assertTrue(MQCloudHistoryTiming.prepare(history, anchor.copy(receivedAtMs = 0), receivedAt, 3).history.isEmpty())
        assertTrue(MQCloudHistoryTiming.prepare(history, anchor.copy(packetIndex = 15000), receivedAt, 3).history.isEmpty())
        assertTrue(MQCloudHistoryTiming.prepare(history, anchor, receivedAt, 0).history.isEmpty())
    }

    private fun prepare(points: List<MQBootstrapHistoryPoint>) =
        MQCloudHistoryTiming.prepare(points, anchor, receivedAt, 3)
}
