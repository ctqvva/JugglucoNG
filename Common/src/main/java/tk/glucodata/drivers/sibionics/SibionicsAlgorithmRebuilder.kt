package tk.glucodata.drivers.sibionics

internal data class SibionicsRebuiltReading(
    val sampleMs: Long,
    val glucoseMgdl: Float,
    val rawMgdl: Float,
    val temperatureC: Float,
    val impedance: Float,
    val index: Int,
    /** Credible interval in mg/dL; null for models that do not estimate one. */
    val uncertainty: tk.glucodata.GlucoseUncertainty? = null,
)

internal data class SibionicsReplayResult(
    val context: SibionicsAlgorithmContext,
    val readings: List<SibionicsRebuiltReading>,
    val sourceSamples: List<SibionicsSourceSample>,
)

internal data class SibionicsIntegratedCalibrationBaseline(
    val values: FloatArray,
    val timestamps: LongArray,
)

internal object SibionicsAlgorithmRebuilder {
    private const val CALIBRATION_ANCHOR_MATCH_MS = 10L * 60L * 1000L

    fun isContiguousFromSensorStart(samples: List<SibionicsSourceSample>): Boolean {
        if (samples.isEmpty() || samples.first().index !in 0..1) return false
        return samples.zipWithNext().all { (before, after) -> after.index == before.index + 1 }
    }

    fun rebuild(
        sensorId: String,
        sourceSamples: List<SibionicsSourceSample>,
        selection: SibionicsAlgorithmSelection,
        variant: SibionicsConstants.Variant,
        shortCode: String,
        sensitivity: Float,
        unitIsMmol: Boolean,
        referenceAnchors: List<SibionicsCalibrationAnchor> = emptyList(),
        calibrateDisplaySeries: (FloatArray, LongArray) -> FloatArray,
    ): SibionicsReplayResult {
        val stockContext = SibionicsAlgorithmContext(sensorId).also {
            it.configure(shortCode, sensitivity, variant, SibionicsAlgorithmSelection.STOCK)
        }
        val validSources = ArrayList<SibionicsSourceSample>(sourceSamples.size)
        val stockMmol = ArrayList<Float>(sourceSamples.size)
        val chemicalSignals = ArrayList<SibionicsChemicalSignal?>(sourceSamples.size)
        // The rebuilt context's exact core is restored from a snapshot and then
        // never advanced — processPreparedMeasurement deliberately does not run
        // it — so its own sensor observation is stale. Capture each sample's
        // observation from the context that actually produced it.
        val sensorObservations = ArrayList<SibionicsSensorObservation?>(sourceSamples.size)
        sourceSamples.forEach { sample ->
            val stock = stockContext.processStock(
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                mode = SibionicsAlgorithmMode.REPLAY,
            )
            val stockMgdl = stock * SibionicsConstants.MGDL_PER_MMOLL
            if (stock.isFinite() && stock > 0f && SibionicsConstants.isValidAlgorithmGlucoseMgdl(stockMgdl)) {
                validSources += sample
                stockMmol += stock
                chemicalSignals += stockContext.latestChemicalSignal()
                sensorObservations += stockContext.latestSensorObservation()
            }
        }
        if (validSources.isEmpty()) {
            return SibionicsReplayResult(stockContext, emptyList(), sourceSamples)
        }

        val displayStock = FloatArray(stockMmol.size) { index ->
            if (unitIsMmol) stockMmol[index] else stockMmol[index] * SibionicsConstants.MGDL_PER_MMOLL
        }
        val calibratedDisplay = if (selection.calibrationEnabled) {
            calibrateDisplaySeries(
                displayStock,
                LongArray(validSources.size) { validSources[it].timestampMs },
            ).takeIf { it.size == displayStock.size } ?: displayStock
        } else {
            displayStock
        }

        val rebuiltContext = SibionicsAlgorithmContext(sensorId).also {
            it.configure(shortCode, sensitivity, variant, selection)
            check(it.restore(stockContext.snapshot())) { "could not transfer exact algorithm state" }
        }
        val readings = ArrayList<SibionicsRebuiltReading>(validSources.size)
        validSources.forEachIndexed { index, sample ->
            val calibrated = calibratedDisplay[index]
            val preparedMmol = if (calibrated.isFinite() && calibrated > 0f) {
                if (unitIsMmol) calibrated else calibrated / SibionicsConstants.MGDL_PER_MMOLL
            } else {
                stockMmol[index]
            }
            val displayMmol = rebuiltContext.processPreparedMeasurement(
                stockMmol = stockMmol[index],
                measurementMmol = preparedMmol,
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                impedance = sample.impedance,
                eventTimeMs = sample.timestampMs,
                chemicalSignal = chemicalSignals[index],
                sensorObservation = sensorObservations[index],
                // Replayed anchors are filtered by timestamp inside the
                // estimator, so a rebuild applies each one at the sample it
                // actually belongs to rather than all of them at the end.
                calibrationAnchors = referenceAnchors,
            )
            val displayMgdl = displayMmol * SibionicsConstants.MGDL_PER_MMOLL
            if (SibionicsConstants.isValidAlgorithmGlucoseMgdl(displayMgdl)) {
                readings += SibionicsRebuiltReading(
                    sampleMs = sample.timestampMs,
                    glucoseMgdl = displayMgdl,
                    rawMgdl = sample.rawMmol * SibionicsConstants.MGDL_PER_MMOLL,
                    temperatureC = sample.temperatureC,
                    impedance = sample.impedance,
                    index = sample.index,
                    uncertainty = rebuiltContext.latestUncertaintyMmol()
                        ?.scaled(SibionicsConstants.MGDL_PER_MMOLL),
                )
            }
        }
        return SibionicsReplayResult(rebuiltContext, readings, sourceSamples)
    }

    fun calibrationBaselineAtAnchors(
        displayStock: FloatArray,
        timestamps: LongArray,
        packedAnchors: DoubleArray,
    ): SibionicsIntegratedCalibrationBaseline? {
        if (displayStock.size != timestamps.size || displayStock.isEmpty()) return null
        if (packedAnchors.size < 3 || packedAnchors.size % 3 != 0) return null
        val matched = LinkedHashMap<Long, Float>()
        packedAnchors.indices.step(3).forEach { anchorOffset ->
            val anchorTimestamp = packedAnchors[anchorOffset + 2].toLong()
            if (anchorTimestamp <= 0L) return@forEach
            var nearestIndex = -1
            var nearestDistance = Long.MAX_VALUE
            timestamps.indices.forEach { index ->
                val value = displayStock[index]
                val timestamp = timestamps[index]
                if (!value.isFinite() || value <= 0f || timestamp <= 0L) return@forEach
                val distance = kotlin.math.abs(timestamp - anchorTimestamp)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestIndex = index
                }
            }
            if (nearestIndex >= 0 && nearestDistance <= CALIBRATION_ANCHOR_MATCH_MS) {
                matched[timestamps[nearestIndex]] = displayStock[nearestIndex]
            }
        }
        if (matched.isEmpty()) return null
        return SibionicsIntegratedCalibrationBaseline(
            values = matched.values.toFloatArray(),
            timestamps = matched.keys.toLongArray(),
        )
    }
}
