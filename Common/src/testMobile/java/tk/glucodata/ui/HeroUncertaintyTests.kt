package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.GlucoseUncertainty

class HeroUncertaintyTests {
    private val time = 1_700_000_000_000L
    private fun point(sensor: String, at: Long = time) = GlucosePoint(
        5f, "", at, sensorSerial = sensor, uncertainty = GlucoseUncertainty(3.9f, 6f))
    private fun range(history: List<GlucosePoint>, sensor: String = "main", snapshot: String = sensor,
                      mode: Int = 0, fresh: Boolean = true) =
        heroUncertaintyText(history, history.lastOrNull(), sensor, snapshot, time, mode, true, fresh)

    @Test fun disabledDisplayDoesNotReadHistoryOrShowStoredIntervals() {
        val inaccessibleHistory = object : AbstractList<GlucosePoint>() {
            override val size: Int get() = error("Disabled UI must not scan history")
            override fun get(index: Int): GlucosePoint = error("Disabled UI must not scan history")
        }
        assertNull(heroUncertaintyText(inaccessibleHistory, point("main"), "main", "main", time, 0, true, true, enabled = false))
    }
    @Test fun currentReadingDoesNotWalkTheWholeHistoryToFindItsRange() {
        val inaccessibleHistory = object : AbstractList<GlucosePoint>() {
            override val size: Int get() = error("Current point is already resolved")
            override fun get(index: Int): GlucosePoint = error("Current point is already resolved")
        }
        assertNotNull(heroUncertaintyText(inaccessibleHistory, point("main"), "main", "main", time, 0, true, true))
    }

    @Test fun switchingBeforeNewHistoryArrivesCannotReusePreviousSensorsRange() {
        assertNotNull(range(listOf(point("main"))))
        assertNull(range(listOf(point("main")), sensor = "peer"))
        assertNull(range(listOf(point("main")), snapshot = "peer"))
        assertNull(range(listOf(point(""))))
    }
    @Test fun mixedHistoryUsesOnlyTheDisplayedSensorsLatestReading() {
        assertNotNull(range(listOf(point("main"), point("peer"))))
        assertNull(range(listOf(point("main").copy(uncertainty = null), point("peer"))))
    }
    @Test fun staleRawOrUnmatchedTimestampsDoNotShowAnInterval() {
        assertNull(range(listOf(point("main", time - 120_000))))
        assertNull(range(listOf(point("main")), mode = 1))
        assertNull(range(listOf(point("main")), mode = 3))
        assertNull(range(listOf(point("main")), fresh = false))
    }
}
