package tk.glucodata.ui.stats

import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class StatsHistorySignatureTests {
    private val original = (0..100).map {
        GlucosePoint(value = 100f, rawValue = 80f, time = "", timestamp = it * 60_000L, sensorSerial = "A")
    }

    private fun changed(point: GlucosePoint) = original.toMutableList().also { it[37] = point }

    @Test fun interiorSealingAndLaneChangesReachTheStatsConsumer() = runBlocking {
        val sealed = changed(original[37].copy(sealedDisplayValue = 120f, sealedDisplayViewMode = 0))
        val revised = changed(sealed[37].copy(sealedDisplayValue = 125f))
        val rawLane = changed(revised[37].copy(sealedDisplayViewMode = 1))
        val emissions = flowOf(original, sealed, sealed.toList(), revised, rawLane, original)
            .distinctUntilChangedBy(::statsHistorySignature).toList()
        assertEquals(listOf(original, sealed, revised, rawLane, original), emissions)
        // The same signature also keys both the full-history and range projection caches.
        assertNotEquals(statsHistorySignature(sealed), statsHistorySignature(revised))
        assertNotEquals(statsHistorySignature(revised), statsHistorySignature(rawLane))
    }

    @Test fun interiorSensorAndSourceLaneChangesInvalidateTheProjection() {
        for (point in listOf(
            original[37].copy(sensorSerial = "B"),
            original[37].copy(value = 101f),
            original[37].copy(rawValue = 81f),
        )) {
            assertNotEquals(statsHistorySignature(original), statsHistorySignature(changed(point)))
        }
        assertEquals(statsHistorySignature(original), statsHistorySignature(original.toList()))
    }
}
