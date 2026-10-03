package tk.glucodata.ui.screens

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.WearGlucoseStore

class WearReadingPeersTests {
    private fun peer(id: String, vararg points: GlucosePoint, mode: Int = 0) =
        WearGlucoseStore.PeerSeries(id, points.toList(), mode, 0xff4285f4.toInt())

    @Test fun pairsOffsetSecondsAndKeepsTheLatestActualPoint() {
        val early = GlucosePoint(120_005L, 100f)
        val latest = GlucosePoint(179_000L, 120f, 90f)
        val series = peer("peer", early, latest, mode = 3)
        val match = readingPeers(listOf(GlucosePoint(120_000L, 110f)), listOf(series), false).getValue(120_000L).single()
        assertSame(latest, match.point)
        assertSame(series, match.series)
        assertEquals(3, match.series.viewMode)
    }

    @Test fun neverCarriesOldValuesAcrossMissingMinutes() {
        val rows = listOf(GlucosePoint(180_000L, 110f), GlucosePoint(120_000L, 110f))
        val matched = readingPeers(rows, listOf(peer("peer", GlucosePoint(179_999L, 120f))), false)
        assertTrue(matched.getValue(180_000L).isEmpty())
        assertEquals(1, matched.getValue(120_000L).size)
    }

    @Test fun retainsSelectionOrderAndIndependentSensorIdentityAtEqualTimestamps() {
        val first = peer("first", GlucosePoint(120_000L, 120f))
        val second = peer("second", GlucosePoint(120_000L, 150f))
        val matched = readingPeers(listOf(GlucosePoint(120_000L, 110f)), listOf(second, first), false)
        assertEquals(listOf("second", "first"), matched.getValue(120_000L).map { it.series.sensorId })
    }

    @Test fun preservesMmolAndRawValuesWithoutConversionOrRecalibration() {
        val point = GlucosePoint(120_000L, 5.6f, 4.2f)
        val match = readingPeers(listOf(GlucosePoint(120_000L, 6f)), listOf(peer("peer", point, mode = 1)), true)
            .getValue(120_000L).single()
        assertEquals(5.6f, match.point.value, 0f)
        assertEquals(4.2f, primaryLaneValue(match.point, match.series.viewMode), 0f)
    }

    @Test fun singleSensorAndEmptyHistoryHaveNoPeerValues() {
        val rows = listOf(GlucosePoint(120_000L, 110f))
        assertTrue(readingPeers(rows, emptyList(), false).getValue(120_000L).isEmpty())
        assertTrue(readingPeers(rows, listOf(peer("empty")), false).getValue(120_000L).isEmpty())
    }
}
