package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.SensorVisuals

class MultiSensorDisplayTests {
    @Test
    fun bucketLookupKeepsLatestReadingPerSensorPerMinuteBucket() {
        val timestamp = 1_800_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = timestamp + 5_000L, value = 6.1f),
                point(sensor = "peer-a", timestamp = timestamp + 50_000L, value = 6.4f),
                point(sensor = "peer-b", timestamp = timestamp + 10_000L, value = 5.7f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        val peers = data.peersAt(timestamp)

        assertEquals(listOf("peer-a", "peer-b"), peers.map { it.sensorSerial })
        assertEquals(6.4f, peers[0].value)
    }

    @Test
    fun bucketLookupOrdersPeersBySelectionOrder() {
        val timestamp = 2_400_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-b", timestamp = timestamp + 10_000L, value = 4.1f),
                point(sensor = "peer-a", timestamp = timestamp + 20_000L, value = 4.8f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        assertEquals(listOf("peer-a", "peer-b"), data.peersAt(timestamp).map { it.sensorSerial })
    }

    @Test
    fun buildDisplayDataDropsSensorsOutsideSelection() {
        val timestamp = 3_000_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "main", timestamp = timestamp + 15_000L, value = 4.8f),
                point(sensor = "peer-a", timestamp = timestamp + 20_000L, value = 5.1f)
            ),
            selectedPeerIds = listOf("peer-a"),
            sensorViewModes = emptyMap()
        )

        assertEquals(listOf("peer-a"), data.series.map { it.sensorId })
        assertEquals(listOf("peer-a"), data.peersAt(timestamp).map { it.sensorSerial })
    }

    @Test
    fun selectedPeerColorsResolvePaletteCollisions() {
        val libreSerial = "46HU804EBJ4"
        val iCanSerial = "X-2222268X25"
        assertEquals(SensorVisuals.colorIndex(libreSerial), SensorVisuals.colorIndex(iCanSerial))

        val colors = SensorVisuals.distinctColorArgbs(listOf(libreSerial, iCanSerial))
        val colorMap = SensorVisuals.distinctColorArgbMap(listOf(libreSerial, iCanSerial))

        assertEquals(SensorVisuals.colorArgb(libreSerial), colors[0])
        assertNotEquals(colors[0], colors[1])
        assertEquals(colors[0], colorMap[libreSerial])
        assertEquals(colors[1], colorMap[iCanSerial])
    }

    @Test
    fun buildDisplayDataUsesFullSelectedOrderForPeerColors() {
        val timestamp = 3_600_000L
        val primarySerial = "X-2222268X25"
        val peerSerial = "46HU804EBJ4"
        assertEquals(SensorVisuals.colorArgb(primarySerial), SensorVisuals.colorArgb(peerSerial))

        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = peerSerial, timestamp = timestamp + 5_000L, value = 3.1f),
            ),
            selectedPeerIds = listOf(peerSerial),
            sensorViewModes = emptyMap(),
            selectedSensorIdsForColors = listOf(primarySerial, peerSerial)
        )

        assertEquals(listOf(peerSerial), data.series.map { it.sensorId })
        assertEquals(
            SensorVisuals.colorArgbForSelected(peerSerial, listOf(primarySerial, peerSerial)),
            data.series.single().colorArgb
        )
        // The two share a slot, so one is bumped — but which one is a property of
        // the pair, not of who is main today. Swapping the selection order must
        // give the peer the same colour it had.
        assertEquals(
            data.series.single().colorArgb,
            SensorVisuals.colorArgbForSelected(peerSerial, listOf(peerSerial, primarySerial))
        )
        assertNotEquals(
            SensorVisuals.colorArgbForSelected(primarySerial, listOf(primarySerial, peerSerial)),
            data.series.single().colorArgb
        )
    }

    @Test
    fun bucketLookupIgnoresOtherBuckets() {
        val requested = 4_200_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = requested + 20_000L, value = 5.1f),
                point(sensor = "peer-b", timestamp = requested + 90_000L, value = 7.1f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        val peers = data.peersAt(requested)

        assertEquals(listOf("peer-a"), peers.map { it.sensorSerial })
        assertTrue(peers.none { it.sensorSerial == "peer-b" })
    }

    @Test
    fun seriesPointsAreSortedAscendingAndCarryViewMode() {
        val timestamp = 5_400_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = timestamp + 120_000L, value = 6.0f),
                point(sensor = "peer-a", timestamp = timestamp, value = 5.0f),
                point(sensor = "peer-a", timestamp = timestamp + 60_000L, value = 5.5f),
            ),
            selectedPeerIds = listOf("peer-a"),
            sensorViewModes = mapOf("peer-a" to 3)
        )

        val series = data.seriesFor("peer-a")!!
        assertEquals(3, series.viewMode)
        assertEquals(
            listOf(timestamp, timestamp + 60_000L, timestamp + 120_000L),
            series.points.map { it.timestamp }
        )
    }

    @Test
    fun recentWindowReturnsNewestFirstUpToTimestamp() {
        val base = 6_000_000L
        val ascending = (0 until 10).map { i ->
            point(sensor = "peer-a", timestamp = base + i * 60_000L, value = 5f + i)
        }

        val window = MultiSensorDisplay.recentWindow(
            pointsAscending = ascending,
            untilTimestamp = base + 4 * 60_000L,
            count = 3
        )

        assertEquals(
            listOf(base + 4 * 60_000L, base + 3 * 60_000L, base + 2 * 60_000L),
            window.map { it.timestamp }
        )
    }

    @Test
    fun recentWindowEmptyWhenAllPointsAreNewer() {
        val base = 7_000_000L
        val ascending = listOf(
            point(sensor = "peer-a", timestamp = base + 60_000L, value = 5f),
            point(sensor = "peer-a", timestamp = base + 120_000L, value = 6f),
        )

        assertTrue(
            MultiSensorDisplay.recentWindow(
                pointsAscending = ascending,
                untilTimestamp = base,
                count = 5
            ).isEmpty()
        )
    }

    private fun point(sensor: String, timestamp: Long, value: Float): GlucosePoint =
        GlucosePoint(
            value = value,
            time = "",
            timestamp = timestamp,
            rawValue = value,
            rate = null,
            sensorSerial = sensor
        )
}
