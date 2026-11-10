package tk.glucodata.chart

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseUncertainty

class UncertaintyBandsTests {
    private fun model(serial: String, mode: Int = 0, calibrated: Boolean = false, hide: Boolean = true): HistoryChartModel {
        val points = (1..3).map { minute -> GlucosePoint(minute * 60_000L, 140f, 110f).also {
            it.sensorSerial = serial
            it.uncertainty = GlucoseUncertainty(100f, 160f)
            if (calibrated) { it.sealedDisplayValue = 150f; it.sealedDisplayViewMode = mode }
        } }
        return HistoryChartModelBuilder.build(
            listOf(HistoryChartModelBuilder.SeriesInput(serial, true, mode, 0, points)),
            MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { _, _, _, _ -> null },
            hasCalibration = calibrated, hideInitialWhenCalibrated = hide,
        )
    }

    @Test fun bothAlgorithmFamiliesKeepTheirAsymmetricBoundsOnTheResolvedAutoLine() {
        for (sensor in listOf("X-AIDEX", "SIBI:SENSOR")) {
            val plain = model(sensor).primary!!.runs.first().points.first().uncertainty!!
            assertEquals(100f, plain.lower, 0f)
            val sealed = model(sensor, calibrated = true).primary!!.runs.first().points.first().uncertainty!!
            assertEquals(110f, sealed.lower, 0f)
            assertEquals(170f, sealed.upper, 0f)
        }
    }

    @Test fun rawNeverInheritsAutoUncertaintyAndRawPlusAutoKeepsItOnTheAutoLane() {
        assertNull(model("X-A", 1).primary!!.runs.first().points.first().uncertainty)
        val dual = model("X-A", 3, calibrated = true).primary!!
        assertNull(dual.runs.first().points.first().uncertainty)
        assertNull(dual.uncertaintyAt(60_000L, true, false))
        assertEquals(100f, dual.uncertaintyAt(60_000L, false, true)!!.lower, 0f)
        assertNull(dual.uncertaintyAt(4 * 60_000L, false, true))
    }

    @Test fun visibleBoundsExpandOnlyWhenTheRibbonIsShown() {
        val chart = model("SIBI:A")
        assertEquals(140f to 140f, chart.visibleValueRange(60_000L, 180_000L))
        assertEquals(100f to 160f, chart.visibleValueRange(60_000L, 180_000L, true))
    }

    @Test fun missingAndInvalidIntervalsGapsAndSensorChangesBreakBands() {
        fun p(minute: Int, serial: String = "A", interval: GlucoseUncertainty? = GlucoseUncertainty(4f, 8f)) =
            ChartPointModel(minute * 60_000L, 6f, serial, uncertainty = interval)
        val rows = listOf(p(1), p(2), p(3, interval = null), p(4), p(5),
            p(6, interval = GlucoseUncertainty(Float.NaN, 8f)), p(7), p(8), p(30), p(31), p(32, "B"), p(33, "B"))
        assertEquals(listOf(listOf(1, 2), listOf(4, 5), listOf(7, 8), listOf(30, 31), listOf(32, 33)),
            UncertaintyBands.runs(rows).map { run -> run.map { (it.timestamp / 60_000L).toInt() } })
    }

    @Test fun forecastBeginsAtBothActualBoundsAndGrowsWithoutCollapsingTheirAsymmetry() {
        val band = PredictionBand(7.8f, 1f, GlucoseUncertainty(6.2f, 9.2f))
        val start = band.bounds(7.8f, 1f, true)
        assertEquals(6.2f, start.lower, 0.0001f)
        assertEquals(9.2f, start.upper, 0.0001f)
        val later = band.bounds(8.5f, 0.5f, true)
        assertEquals(6.25f, later.lower, 0.0001f)
        assertEquals(10.55f, later.upper, 0.0001f)
        assertTrue(later.halfWidth >= start.halfWidth)
    }

    @Test fun mgdlForecastKeepsMgdlBoundsAndMissingRibbonKeepsTheOriginalForecastEnvelope() {
        val band = PredictionBand(140f, 1f, GlucoseUncertainty(110f, 170f))
        assertEquals(110f, band.bounds(140f, 1f, false).lower, 0f)
        assertEquals(183f, band.bounds(150f, 0.875f, false).upper, 0f)
        val unanchored = PredictionBand(140f, 1f, null)
        assertFalse(unanchored.isAnchored)
        assertEquals(136.8f, unanchored.bounds(140f, 1f, false).lower, 0.0001f)
    }
}
