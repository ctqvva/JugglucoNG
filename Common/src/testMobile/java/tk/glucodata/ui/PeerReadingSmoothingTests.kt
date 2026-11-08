package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading row pairs the main sensor's value with each peer's value at the same minute.
 *
 * The main value came from the smoothed evaluation series while the peer value came
 * straight from stored history, so turning smoothing on moved one number on the row and
 * not the other, and the newest row's peer disagreed with the hero's peer chip (which
 * resolves through the same window). Both sides of a row now go through one transform.
 */
class PeerReadingSmoothingTests {

    private val nowMillis = 1_700_000_000_000L
    private val minute = 60_000L

    // A rise with a kink in it — the shape the reported screenshots were taken on.
    private val values = listOf(9.2f, 9.3f, 9.6f, 9.7f, 9.9f, 10.1f, 10.3f, 10.2f, 10.4f, 10.5f, 10.5f)

    private fun series(sensor: String): List<GlucosePoint> =
        values.mapIndexed { index, value ->
            GlucosePoint(
                value = value,
                time = "",
                timestamp = nowMillis - (values.lastIndex - index) * minute,
                rawValue = value,
                rate = null,
                sensorSerial = sensor
            )
        }

    private fun peerDisplay() = MultiSensorDisplay.buildDisplayData(
        points = series("peer"),
        selectedPeerIds = listOf("peer"),
        sensorViewModes = emptyMap()
    )

    @Test
    fun aPeerOnARowIsSmoothedLikeTheMainValueBesideIt() {
        val smoothingMinutes = 3
        val mainRows = buildSmoothedConsumerHistory(series("main"), smoothingMinutes, evaluationCollapseMinutes = 0)
        val peers = buildSmoothedPeerReadings(peerDisplay(), smoothingMinutes)

        // The kink is where smoothing visibly moves a value; without it this test proves nothing.
        assertNotEquals(values[7], mainRows[7].value)
        mainRows.forEach { main ->
            val peer = peers.peersAt(main.timestamp).single()
            assertEquals(main.timestamp, peer.timestamp)
            assertEquals(main.value, peer.value, 0.0001f)
            assertEquals(main.rawValue, peer.rawValue, 0.0001f)
        }
    }

    @Test
    fun thePeerArrowsReadTheSameSmoothedSeriesAsThePeerValues() {
        val peers = buildSmoothedPeerReadings(peerDisplay(), evaluationSmoothingMinutes = 3)
        val series = peers.series.single()

        series.points.forEach { point ->
            assertSame(point, peers.peersAt(point.timestamp).single())
        }
        assertEquals("peer", series.points.map { it.sensorSerial }.distinct().single())
    }

    @Test
    fun peersAreNotThinnedWhenTheMainRowsAre() {
        val smoothingMinutes = 3
        val collapseMinutes = tk.glucodata.DataSmoothing.collapseIntervalMinutes(smoothingMinutes)
        val mainRows = buildSmoothedConsumerHistory(series("main"), smoothingMinutes, collapseMinutes)
        val peers = buildSmoothedPeerReadings(peerDisplay(), smoothingMinutes)

        // Every thinned main row still finds its peer, with the value the peer would show unthinned.
        assertTrue(mainRows.size < values.size)
        mainRows.forEach { main ->
            assertEquals(main.value, peers.peersAt(main.timestamp).single().value, 0.0001f)
        }
        assertEquals(values.size, peers.series.single().points.size)
    }

    @Test
    fun smoothingOffLeavesThePeersAsStored() {
        val display = peerDisplay()
        assertSame(display, buildSmoothedPeerReadings(display, evaluationSmoothingMinutes = 0))
    }
}
