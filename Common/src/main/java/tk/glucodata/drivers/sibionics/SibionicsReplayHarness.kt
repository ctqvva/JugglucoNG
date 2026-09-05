package tk.glucodata.drivers.sibionics

import tk.glucodata.drivers.sibionics.adaptive2.AdaptiveV2Diagnostics

/**
 * Replays one history through Stock, Adaptive V1 and Adaptive V2 and emits an
 * aligned per-sample CSV.
 *
 * The point is to make real-sensor behaviour inspectable without editing source
 * each time. Every model sees identical input, and Stock and V1 appear in the
 * output strictly as comparison columns — neither is ever fed into V2, and the
 * harness deliberately reports no "difference from stock" objective, because
 * minimising that is not what V2 is for.
 */
internal object SibionicsReplayHarness {

    /** One aligned row across all three models plus the vendor ladder behind them. */
    internal data class Row(
        val index: Int,
        val timestampMs: Long,
        val rawMmol: Float,
        val temperatureC: Float,
        val impedance: Float,
        val chemicalMmol: Float,
        val calibratedMmol: Float,
        val sensorStateCompensationMmol: Float,
        val activeSensitivity: Float,
        val factorySensitivity: Float,
        val stockMmol: Float,
        val adaptiveV1Mmol: Float,
        val adaptiveV2Mmol: Float,
        val diagnostics: AdaptiveV2Diagnostics?,
    ) {
        fun toCsv(): String = listOf(
            index, timestampMs, rawMmol, temperatureC, impedance,
            chemicalMmol, calibratedMmol, sensorStateCompensationMmol,
            activeSensitivity, factorySensitivity,
            stockMmol, adaptiveV1Mmol, adaptiveV2Mmol,
            diagnostics?.lower90Mmol ?: Float.NaN,
            diagnostics?.upper90Mmol ?: Float.NaN,
            diagnostics?.rateMmolPerMin ?: Float.NaN,
            diagnostics?.rateUncertainty ?: Float.NaN,
            diagnostics?.steadyProbability ?: Float.NaN,
            diagnostics?.dynamicProbability ?: Float.NaN,
            diagnostics?.artifactProbability ?: Float.NaN,
            diagnostics?.driftProbability ?: Float.NaN,
            diagnostics?.sensitivity ?: Float.NaN,
            diagnostics?.biasMmol ?: Float.NaN,
            diagnostics?.lagMinutes ?: Float.NaN,
            diagnostics?.measurementNoise ?: Float.NaN,
            diagnostics?.innovation ?: Float.NaN,
            diagnostics?.interstitialMmol ?: Float.NaN,
            diagnostics?.artifactMmol ?: Float.NaN,
            diagnostics?.temperatureQuality ?: Float.NaN,
            diagnostics?.impedanceQuality ?: Float.NaN,
        ).joinToString(",")

        companion object {
            const val CSV_HEADER =
                "index,timestampMs,raw,temperature,impedance," +
                    "chemical,calibrated,sensorStateCompensation," +
                    "activeSensitivity,factorySensitivity," +
                    "stock,adaptiveV1,adaptiveV2," +
                    "v2Lower90,v2Upper90,v2Rate,v2RateSd," +
                    "pSteady,pDynamic,pArtifact,pDrift," +
                    "v2Sensitivity,v2Bias,v2Lag,v2MeasurementNoise,v2Innovation," +
                    "v2Interstitial,v2Artifact,tempQuality,impedanceQuality"
        }
    }

    /**
     * Replays [samples] through all three models.
     *
     * Each model runs in its own context so none can perturb another, and the
     * V2 pass reads the vendor observation from its own core rather than from a
     * shared one, keeping the replay deterministic and independent.
     */
    fun replay(
        samples: List<SibionicsSourceSample>,
        variant: SibionicsConstants.Variant = SibionicsConstants.Variant.CHINESE,
        shortCode: String = "",
        sensitivity: Float,
        references: List<SibionicsCalibrationAnchor> = emptyList(),
    ): List<Row> {
        fun context(selection: SibionicsAlgorithmSelection) =
            SibionicsAlgorithmContext("replay-${selection.storageId}").apply {
                configure(shortCode, sensitivity, variant, selection)
            }

        val stock = context(SibionicsAlgorithmSelection.STOCK)
        val v1 = context(SibionicsAlgorithmSelection.STATE_MODEL)
        val v2 = context(SibionicsAlgorithmSelection.ADAPTIVE_V2).apply {
            enableV2Diagnostics(samples.size.coerceAtMost(MAX_DIAGNOSTIC_ROWS))
        }

        val rows = ArrayList<Row>(samples.size)
        samples.forEach { sample ->
            val stockValue = stock.process(
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = sample.impedance,
                eventTimeMs = sample.timestampMs,
            )
            val v1Value = v1.process(
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = sample.impedance,
                eventTimeMs = sample.timestampMs,
            )
            val v2Value = v2.process(
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = sample.impedance,
                eventTimeMs = sample.timestampMs,
                calibrationAnchors = references,
            )
            val observation = v2.latestSensorObservation()
            rows += Row(
                index = sample.index,
                timestampMs = sample.timestampMs,
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                impedance = sample.impedance,
                chemicalMmol = observation?.chemicalMmol ?: Float.NaN,
                calibratedMmol = observation?.calibratedMmol ?: Float.NaN,
                sensorStateCompensationMmol = observation?.sensorStateCompensationMmol ?: Float.NaN,
                activeSensitivity = observation?.activeSensitivity ?: Float.NaN,
                factorySensitivity = observation?.factorySensitivity ?: Float.NaN,
                stockMmol = stockValue,
                adaptiveV1Mmol = v1Value,
                adaptiveV2Mmol = v2Value,
                diagnostics = v2.v2Diagnostics().lastOrNull()?.takeIf { it.index == sample.index },
            )
        }
        return rows
    }

    fun toCsv(rows: List<Row>): String = buildString {
        appendLine(Row.CSV_HEADER)
        rows.forEach { appendLine(it.toCsv()) }
    }

    /** Summary of how the three models relate. Diagnostics only — never an objective. */
    internal data class Summary(
        val samples: Int,
        val meanV2MinusStock: Double,
        val worstV2MinusStock: Double,
        val meanV1MinusStock: Double,
        val worstV1MinusStock: Double,
        val meanIntervalWidth: Double,
        val meanArtifactProbability: Double,
    ) {
        override fun toString(): String =
            "n=%d V2-stock mean=%+.3f worst=%+.3f | V1-stock mean=%+.3f worst=%+.3f | width=%.3f pArtifact=%.3f"
                .format(
                    samples, meanV2MinusStock, worstV2MinusStock,
                    meanV1MinusStock, worstV1MinusStock,
                    meanIntervalWidth, meanArtifactProbability,
                )
    }

    fun summarise(rows: List<Row>, fromIndex: Int = 0): Summary {
        var n = 0
        var sumV2 = 0.0
        var worstV2 = 0.0
        var sumV1 = 0.0
        var worstV1 = 0.0
        var sumWidth = 0.0
        var widthCount = 0
        var sumArtifact = 0.0
        rows.forEach { row ->
            if (row.index < fromIndex) return@forEach
            if (!row.stockMmol.isFinite() || row.stockMmol <= 0f) return@forEach
            if (row.adaptiveV2Mmol.isFinite() && row.adaptiveV2Mmol > 0f) {
                val delta = (row.adaptiveV2Mmol - row.stockMmol).toDouble()
                sumV2 += delta
                if (kotlin.math.abs(delta) > kotlin.math.abs(worstV2)) worstV2 = delta
                n++
            }
            if (row.adaptiveV1Mmol.isFinite() && row.adaptiveV1Mmol > 0f) {
                val delta = (row.adaptiveV1Mmol - row.stockMmol).toDouble()
                sumV1 += delta
                if (kotlin.math.abs(delta) > kotlin.math.abs(worstV1)) worstV1 = delta
            }
            row.diagnostics?.let {
                sumWidth += (it.upper90Mmol - it.lower90Mmol).toDouble()
                sumArtifact += it.artifactProbability.toDouble()
                widthCount++
            }
        }
        return Summary(
            samples = n,
            meanV2MinusStock = if (n > 0) sumV2 / n else Double.NaN,
            worstV2MinusStock = worstV2,
            meanV1MinusStock = if (n > 0) sumV1 / n else Double.NaN,
            worstV1MinusStock = worstV1,
            meanIntervalWidth = if (widthCount > 0) sumWidth / widthCount else Double.NaN,
            meanArtifactProbability = if (widthCount > 0) sumArtifact / widthCount else Double.NaN,
        )
    }

    private const val MAX_DIAGNOSTIC_ROWS = 60_000
}
