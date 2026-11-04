package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test

class OttaiFirmwareDiscoveryTests {
    private class Clock {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit) { pending += (now + delay) to action }
        fun advance(milliseconds: Long) {
            val end = now + milliseconds
            while (true) {
                val next = pending.filter { it.first <= end }.minByOrNull { it.first } ?: break
                pending.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }
    }

    @Test fun duplicateMtuCallbacksDelayDiscoveryUntilTheLastOneSettles() {
        val clock = Clock()
        val discoveries = mutableListOf<Long>()
        val gate = OttaiFirmwareDiscovery(clock::schedule) { discoveries += clock.now }
        gate.connected(mtuRequested = true)
        gate.mtuChanged()
        clock.advance(1_000)
        gate.mtuChanged()
        clock.advance(1_249)
        assertEquals(emptyList<Long>(), discoveries)
        clock.advance(1)
        assertEquals(listOf(2_250L), discoveries)
        clock.advance(10_000)
        gate.mtuChanged()
        clock.advance(10_000)
        assertEquals(listOf(2_250L), discoveries)
    }

    @Test fun aLateMtuCallbackReplacesTheOriginalFallback() {
        val clock = Clock()
        var discoveries = 0
        val gate = OttaiFirmwareDiscovery(clock::schedule) { discoveries++ }
        gate.connected(mtuRequested = true)
        clock.advance(2_000)
        gate.mtuChanged()
        clock.advance(500)
        assertEquals(0, discoveries)
        clock.advance(749)
        assertEquals(0, discoveries)
        clock.advance(1)
        assertEquals(1, discoveries)
    }

    @Test fun aMissingMtuCallbackUsesTheFallback() {
        val clock = Clock()
        var discoveries = 0
        val gate = OttaiFirmwareDiscovery(clock::schedule) { discoveries++ }
        gate.connected(mtuRequested = true)
        clock.advance(2_499)
        assertEquals(0, discoveries)
        clock.advance(1)
        assertEquals(1, discoveries)
    }

    @Test fun aRefusedMtuRequestUsesTheShortFallback() {
        val clock = Clock()
        var discoveries = 0
        val gate = OttaiFirmwareDiscovery(clock::schedule) { discoveries++ }
        gate.connected(mtuRequested = false)
        clock.advance(299)
        assertEquals(0, discoveries)
        clock.advance(1)
        assertEquals(1, discoveries)
    }

    @Test fun closingTheConnectionDiscardsEveryOldDiscoveryTimer() {
        val clock = Clock()
        var discoveries = 0
        val old = OttaiFirmwareDiscovery(clock::schedule) { discoveries++ }
        old.connected(mtuRequested = true)
        old.mtuChanged()
        old.close()
        old.mtuChanged()
        val replacement = OttaiFirmwareDiscovery(clock::schedule) { discoveries++ }
        replacement.connected(mtuRequested = true)
        clock.advance(2_499)
        assertEquals(0, discoveries)
        clock.advance(1)
        assertEquals(1, discoveries)
    }
}
