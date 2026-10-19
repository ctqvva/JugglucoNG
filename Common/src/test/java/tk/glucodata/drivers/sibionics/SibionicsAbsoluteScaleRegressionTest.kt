package tk.glucodata.drivers.sibionics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the real-device failure in which Adaptive V2 read roughly
 * 2 mmol/L below stock across an entire history, turning ordinary days into
 * hours of fictitious hypoglycaemia.
 *
 * The failure class matters more than the specific trace: **an early
 * front-end signal that sits systematically below the manufacturer-corrected
 * estimate must not be interpreted as true glucose.** V2 is not required to
 * agree with stock — it must be free to disagree — but it must not disagree by
 * a constant offset that comes from having discarded the vendor's own
 * sensor-state compensation.
 *
 * These tests drive the real [SibionicsAlgorithmContext] and the real vendor
 * core, so they exercise the actual pipeline rather than a stand-in.
 */
class SibionicsAbsoluteScaleRegressionTest {

    /** A multi-day trace with meal excursions, exercising clip/ESA sensor-state drift. */
    private fun sourceSamples(minutes: Int, seed: Int = 9): List<SibionicsSourceSample> {
        val random = Random(seed)
        return (0 until minutes).map { minute ->
            val index = minute + 1
            val raw = (6.5f
                + 2.4f * sin(2 * PI * (index % 1440) / 1440.0 - 1.0).toFloat()
                + 1.2f * sin(2 * PI * (index % 300) / 300.0).toFloat()
                + (random.nextFloat() - 0.5f) * 0.25f).coerceIn(3.0f, 14f)
            SibionicsSourceSample(
                index = index,
                timestampMs = index * 60_000L,
                rawMmol = raw,
                temperatureC = 34f + (random.nextFloat() - 0.5f) * 0.4f,
                impedance = 2_900f + (random.nextFloat() - 0.5f) * 60f,
                variantId = 0,
            )
        }
    }

    @Test
    fun theVendorAppliesALargeAbsoluteCorrectionAfterTheChemicalSignal() {
        val core = SibionicsExactV115GCore(1.4f)
        var chemicalGap = 0.0
        var calibratedGap = 0.0
        var worstChemicalGap = 0f
        var worstCalibratedGap = 0f
        var n = 0

        sourceSamples(TRACE_MINUTES).forEach { sample ->
            val stock = core.process(sample.rawMmol, sample.temperatureC, sample.index)
            val chemical = core.latestChemicalSignal?.mmol
            val observation = core.latestSensorObservation
            if (stock != null && chemical != null && observation != null && sample.index > SETTLE) {
                chemicalGap += (stock - chemical)
                calibratedGap += (stock - observation.calibratedMmol)
                if (abs(stock - chemical) > abs(worstChemicalGap)) worstChemicalGap = stock - chemical
                if (abs(stock - observation.calibratedMmol) > abs(worstCalibratedGap)) {
                    worstCalibratedGap = stock - observation.calibratedMmol
                }
                n++
            }
        }
        val meanChemical = chemicalGap / n
        val meanCalibrated = calibratedGap / n
        println(
            "scale  chemical gap mean=%+.3f worst=%+.3f | calibrated gap mean=%+.3f worst=%+.3f (n=%d)"
                .format(meanChemical, worstChemicalGap, meanCalibrated, worstCalibratedGap, n)
        )

        // This is the bug, stated as a measurement: the raw chemical signal is
        // about a millimole below the vendor's own estimate on average, and far
        // more at times. Anything treating it as absolute glucose inherits that.
        assertTrue("meanChemical=$meanChemical", meanChemical > 0.8)
        assertTrue("worstChemical=$worstChemicalGap", worstChemicalGap > 2.0f)

        // And this is the fix: keeping the vendor's sensor-state compensation
        // removes the offset, leaving only a dynamics difference.
        assertTrue("meanCalibrated=$meanCalibrated", abs(meanCalibrated) < 0.25)
        assertTrue("worstCalibrated=$worstCalibratedGap", abs(worstCalibratedGap) < 1.2f)
    }

    @Test
    fun adaptiveV2NoLongerCollapsesBelowStockAcrossAWholeTrace() {
        val samples = sourceSamples(TRACE_MINUTES)
        val rows = SibionicsReplayHarness.replay(samples = samples, sensitivity = 1.4f)
        val summary = SibionicsReplayHarness.summarise(rows, fromIndex = SETTLE)
        println("replay  $summary")

        // V2 stays free to disagree with stock, but not by a systematic
        // offset: a whole-trace mean displacement is a scale error, not an
        // opinion. The failing build sat near -2 mmol/L here.
        assertTrue("$summary", abs(summary.meanV2MinusStock) < 0.5)
        assertTrue("$summary", abs(summary.worstV2MinusStock) < 2.5)

        // V1 remains the conservative, stock-aware model.
        assertTrue("$summary", abs(summary.meanV1MinusStock) < 0.4)
    }

    @Test
    fun adaptiveV2DoesNotManufactureHypoglycaemiaOnANormalTrace() {
        val samples = sourceSamples(TRACE_MINUTES)
        val rows = SibionicsReplayHarness.replay(samples = samples, sensitivity = 1.4f)

        var stockLow = 0
        var v2Low = 0
        var counted = 0
        rows.forEach { row ->
            if (row.index < SETTLE) return@forEach
            if (!row.stockMmol.isFinite() || !row.adaptiveV2Mmol.isFinite()) return@forEach
            counted++
            if (row.stockMmol < LOW_THRESHOLD) stockLow++
            if (row.adaptiveV2Mmol < LOW_THRESHOLD) v2Low++
        }
        val stockFraction = stockLow.toDouble() / counted
        val v2Fraction = v2Low.toDouble() / counted
        println("hypo  stock=%.3f v2=%.3f (n=%d)".format(stockFraction, v2Fraction, counted))

        // The screenshots that prompted this showed V2 painting large stretches
        // of hypoglycaemia that stock did not see. V2 may legitimately find a
        // low stock misses, but not an order of magnitude more of them.
        assertTrue("stock=$stockFraction v2=$v2Fraction", v2Fraction < stockFraction + 0.15)
    }

    @Test
    fun aHistoryRebuildActuallyRunsV2AndProducesIntervals() {
        val samples = sourceSamples(3_000)
        val result = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "rebuild-v2",
            sourceSamples = samples,
            selection = SibionicsAlgorithmSelection.ADAPTIVE_V2,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = true,
        ) { displayStock, _ -> displayStock }

        val settled = result.readings.filter { it.index > SETTLE }
        assertTrue("readings=${result.readings.size}", settled.size > 500)

        // The rebuilt context's exact core is restored from a snapshot and then
        // never advanced, so its own sensor observation is stale — the rebuild
        // has to carry each sample's observation across from the context that
        // produced it. When that plumbing broke, every rebuilt sample fell back
        // to the stock value and wrote no interval at all: the chart looked
        // completely normal and the ribbon simply never appeared, which is a
        // failure with no error message anywhere.
        val withInterval = settled.count { it.uncertainty?.isUsable == true }
        val coverage = withInterval.toDouble() / settled.size
        println("rebuild  samples=${settled.size} withInterval=$withInterval coverage=%.3f".format(coverage))
        assertTrue("coverage=$coverage", coverage > 0.95)

        // And the rebuilt values must be V2's, not a silent stock fallback.
        val stockRebuild = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "rebuild-stock",
            sourceSamples = samples,
            selection = SibionicsAlgorithmSelection.STOCK,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = true,
        ) { displayStock, _ -> displayStock }
        val stockByIndex = stockRebuild.readings.associateBy { it.index }
        val differing = settled.count { reading ->
            val stock = stockByIndex[reading.index] ?: return@count false
            abs(reading.glucoseMgdl - stock.glucoseMgdl) > 0.5f
        }
        assertTrue("differing=$differing of ${settled.size}", differing > settled.size / 10)
    }

    @Test
    fun theHarnessEmitsAnAlignedThreeWayCsv() {
        val rows = SibionicsReplayHarness.replay(
            samples = sourceSamples(400),
            sensitivity = 1.4f,
        )
        val csv = SibionicsReplayHarness.toCsv(rows)
        val lines = csv.trim().lines()

        assertEquals(SibionicsReplayHarness.Row.CSV_HEADER, lines.first())
        assertEquals(rows.size + 1, lines.size)
        val columns = SibionicsReplayHarness.Row.CSV_HEADER.split(",").size
        lines.drop(1).forEach { assertEquals(columns, it.split(",").size) }
        // Stock and V1 are present as comparison columns.
        assertTrue(SibionicsReplayHarness.Row.CSV_HEADER.contains("stock"))
        assertTrue(SibionicsReplayHarness.Row.CSV_HEADER.contains("adaptiveV1"))
    }

    @Test
    fun adaptiveV2RunsOnBothAlgorithmFamilies() {
        // Three of the five variants use V1.1.6A, so a V2 that only works on
        // V1.1.5G is switched off for most sensors — and switched off silently,
        // because the no-observation path falls back to the vendor value and
        // publishes no interval. That is indistinguishable on screen from
        // "Adaptive V2 is selected and simply looks like stock".
        listOf(
            SibionicsConstants.Variant.CHINESE to "V115G",
            SibionicsConstants.Variant.SIBIONICS2 to "V116A",
        ).forEach { (variant, family) ->
            val context = SibionicsAlgorithmContext("family-$family").apply {
                configure(
                    "46HU804EBJ4", 1.4f, variant,
                    SibionicsAlgorithmSelection.ADAPTIVE_V2,
                )
            }
            var output = Float.NaN
            sourceSamples(3_000).forEach { sample ->
                output = context.process(
                    rawMmol = sample.rawMmol,
                    temperatureC = sample.temperatureC,
                    index = sample.index,
                    mode = SibionicsAlgorithmMode.REPLAY,
                    impedance = sample.impedance,
                    eventTimeMs = sample.timestampMs,
                )
            }

            assertTrue("$family output=$output", output.isFinite() && output > 0f)
            val estimate = context.latestProbabilisticEstimate()
            assertTrue("$family produced no estimate", estimate != null && estimate.isUsable)
            val uncertainty = context.latestUncertaintyMmol()
            assertTrue("$family produced no interval", uncertainty != null && uncertainty.isUsable)
        }
    }

    @Test
    fun bothFamiliesExposeACalibratedObservationCloseToTheirOwnOutput() {
        // The V1.1.6A core is a transliterated state machine, so its calibrated
        // observation is recovered from the value the deconvolution stage
        // recorded as its input rather than read from a structured stage. This
        // pins that the recovered quantity is the right one: like V1.1.5G, it
        // must sit far closer to the vendor's own output than the raw chemical
        // signal does.
        val core = tk.glucodata.drivers.sibionics.v116a.SibionicsExactV116ACore(1.4f)
        var chemicalGap = 0.0
        var calibratedGap = 0.0
        var n = 0
        sourceSamples(TRACE_MINUTES).forEach { sample ->
            val output = core.process(sample.rawMmol, sample.temperatureC, sample.index)
            val chemical = core.latestChemicalSignal?.mmol
            val observation = core.latestSensorObservation
            if (output != null && chemical != null && observation != null && sample.index > SETTLE) {
                chemicalGap += (output - chemical)
                calibratedGap += (output - observation.calibratedMmol)
                n++
            }
        }
        val meanChemical = chemicalGap / n
        val meanCalibrated = calibratedGap / n
        println("v116 scale  chemical mean=%+.3f | calibrated mean=%+.3f (n=%d)".format(
            meanChemical, meanCalibrated, n))

        assertTrue("meanChemical=$meanChemical", meanChemical > 0.25)
        assertTrue("meanCalibrated=$meanCalibrated", abs(meanCalibrated) < 0.25)
        assertTrue("calibrated must beat chemical", abs(meanCalibrated) < abs(meanChemical))
    }

    private companion object {
        private const val TRACE_MINUTES = 12_000
        /** Past the vendor warm-up and the first clip/ESA stages. */
        private const val SETTLE = 2_000
        private const val LOW_THRESHOLD = 3.9f
    }
}
