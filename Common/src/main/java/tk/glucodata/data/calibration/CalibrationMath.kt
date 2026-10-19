package tk.glucodata.data.calibration

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The calibration computation itself, shared by the phone and the watch.
 *
 * It used to live inside CalibrationManager, which is mobile-only because of its
 * Room layer. That was fine while the phone was the only device that calibrated:
 * readings were corrected there and sent to the watch already calibrated. Once
 * the watch can hold the sensor itself it has to correct its own readings, and a
 * second implementation would be free to disagree with the phone about a glucose
 * value. So the maths moved here unchanged, and both sides call it.
 *
 * Pure by construction: no Room, no Android, no manager state. The settings it
 * depends on arrive as [CalibrationTuning] so the watch can be handed the
 * phone's.
 */

internal data class CalPoint(
        val x: Double,
        val y: Double,
        val timestamp: Long,
        val isEnabled: Boolean = true
    )

internal data class LinearModel(
        val slope: Double,
        val intercept: Double
    ) {
        fun predict(x: Double): Double = slope * x + intercept
    }

internal data class AlgorithmComputation(
        val prediction: Double,
        val slope: Double? = null,
        val intercept: Double? = null,
        val offset: Double? = null,
        val anchorInfluence: Double? = null,
        val confidence: Double? = null,
        val note: String = ""
    )
/** The settings the computation depends on, as their storage values. */
data class CalibrationTuning(
    val algorithm: String,
    val weightMode: String,
    val applyToPast: Boolean,
    val lockPastHistory: Boolean,
    val keepDisabledHistory: Boolean,
) {
    companion object {
        val DEFAULT = CalibrationTuning(
            algorithm = "sane_weighted_ols",
            weightMode = "fresh",
            applyToPast = false,
            lockPastHistory = true,
            keepDisabledHistory = false,
        )
    }
}

internal object CalibrationMath {
    const val ALG_SANE_WEIGHTED_OLS = "sane_weighted_ols"
    const val ALG_XDRIP_MEDIAN_SLOPE = "xdrip_median_slope"
    const val ALG_TIME_WEIGHTED_ROBUST_REGRESSION = "time_weighted_robust_regression"
    const val ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION = "elastic_time_weighted_interpolation"
    const val ALG_ADAPTIVE_ENSEMBLE = "adaptive_ensemble"

    const val WEIGHT_FRESH = "fresh"
    const val WEIGHT_BALANCED = "balanced"
    const val WEIGHT_STABLE = "stable"

    private const val HOUR_MS = 3_600_000.0
    private const val PAST_BLEND_WINDOW_MS = 30L * 60L * 1000L

    /**
     * A raw value that is safe to store beside a calibration, or zero when the source has none.
     *
     * A driver without a raw lane reports it as `NaN` (iCan's `resolveRawLaneValue` does), and
     * SQLite stores a non-finite REAL as NULL while the column is NOT NULL — so the calibration row
     * would fail to insert instead of being recorded. Zero is what the raw lane already means for
     * such a sensor: no raw value to pair with the calibration.
     */
    fun sensorValueRawForStorage(raw: Float): Float =
        raw.takeIf { it.isFinite() && it > 0f } ?: 0f

    fun resolvePointsForTimestamp(
        allPoints: List<CalPoint>,
        targetTimestamp: Long,
        earliestPoint: CalPoint?,
        tuning: CalibrationTuning
    ): List<CalPoint> {
        if (!tuning.lockPastHistory) {
            return allPoints.filter { it.isEnabled }
        }

        val historicalCandidates = allPoints.filter { it.timestamp <= targetTimestamp }
        val activeAtTimestamp = historicalCandidates.filter { it.isEnabled }
        val allActivePoints = allPoints.filter { it.isEnabled }
        val retiredAtTimestamp = if (tuning.keepDisabledHistory) {
            historicalCandidates.filter { retired ->
                if (retired.isEnabled) {
                    false
                } else {
                    val nextActiveTimestamp = allActivePoints
                        .asSequence()
                        .filter { active -> active.timestamp > retired.timestamp }
                        .map { active -> active.timestamp }
                        .minOrNull()
                    nextActiveTimestamp != null && targetTimestamp < nextActiveTimestamp
                }
            }
        } else {
            emptyList()
        }

        return (activeAtTimestamp + retiredAtTimestamp)
            .sortedBy { it.timestamp }
            .ifEmpty {
                if (tuning.applyToPast && earliestPoint != null) listOf(earliestPoint) else emptyList()
            }
    }

    fun applyPastPolicy(
        originalValue: Float,
        calibratedValue: Float,
        targetTimestamp: Long,
        points: List<CalPoint>
    ): Float {
        val firstCalibrationTs = points.minOfOrNull { it.timestamp } ?: return calibratedValue
        if (targetTimestamp >= firstCalibrationTs) return calibratedValue

        val blendStartTs = firstCalibrationTs - PAST_BLEND_WINDOW_MS
        if (targetTimestamp <= blendStartTs) return originalValue

        val blend = ((targetTimestamp - blendStartTs).toDouble() / PAST_BLEND_WINDOW_MS.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
        return originalValue + (calibratedValue - originalValue) * blend
    }


    fun computeAlgorithm(
        algorithm: String,
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        if (points.size == 1) {
            val offset = points.first().y - points.first().x
            return AlgorithmComputation(
                prediction = targetValue + offset,
                offset = offset,
                anchorInfluence = 1.0,
                confidence = 1.0,
                note = "Single-point offset calibration"
            )
        }

        return when (algorithm) {
            ALG_SANE_WEIGHTED_OLS ->
                saneWeightedOls(targetValue, targetTimestamp, points, tuning)

            ALG_XDRIP_MEDIAN_SLOPE ->
                xdripMedianSlope(targetValue, targetTimestamp, points, tuning)

            ALG_TIME_WEIGHTED_ROBUST_REGRESSION ->
                timeWeightedRobustRegression(targetValue, targetTimestamp, points, tuning)

            ALG_ELASTIC_TIME_WEIGHTED_INTERPOLATION ->
                elasticTimeWeightedInterpolation(targetValue, targetTimestamp, points, tuning)

            ALG_ADAPTIVE_ENSEMBLE ->
                adaptiveEnsemble(targetValue, targetTimestamp, points, tuning)

            // An algorithm this build does not know about must not silently
            // change the numbers; fall back to the default fit.
            else ->
                saneWeightedOls(targetValue, targetTimestamp, points, tuning)
        }
    }


    fun sanitizeCalibratedValue(calibrated: Double, fallback: Float): Float {
        if (!calibrated.isFinite()) return fallback
        return calibrated.coerceIn(0.0, 1000.0).toFloat()
    }


    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun temporalWeight(
        pointTimestamp: Long,
        targetTimestamp: Long,
        pastHalfLifeHours: Double,
        futureHalfLifeHours: Double,
        tuning: CalibrationTuning
    ): Double {
        val deltaHours = (targetTimestamp - pointTimestamp) / HOUR_MS
        val ageHours = abs(deltaHours)
        val baseHalfLife = if (deltaHours >= 0.0) pastHalfLifeHours else futureHalfLifeHours
        val halfLifeScale = when (tuning.weightMode) {
            WEIGHT_FRESH -> if (deltaHours >= 0.0) 0.45 else 0.65
            WEIGHT_STABLE -> if (deltaHours >= 0.0) 1.75 else 1.25
            else -> 1.0
        }
        val halfLife = baseHalfLife * halfLifeScale
        return 0.5.pow(ageHours / halfLife.coerceAtLeast(0.1))
    }

    private fun weightedOffset(points: List<CalPoint>, weights: List<Double>): Double {
        var sumW = 0.0
        var sumOffset = 0.0
        points.indices.forEach { idx ->
            val w = weights[idx].coerceAtLeast(0.0)
            sumW += w
            sumOffset += (points[idx].y - points[idx].x) * w
        }
        return if (sumW > 1e-9) sumOffset / sumW else points.map { it.y - it.x }.average()
    }

    private fun weightedLinearModel(
        points: List<CalPoint>,
        weights: List<Double>,
        slopeMin: Double,
        slopeMax: Double
    ): LinearModel? {
        if (points.size != weights.size || points.isEmpty()) return null

        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        var sumWXY = 0.0
        var sumWX2 = 0.0

        points.indices.forEach { idx ->
            val w = weights[idx].coerceAtLeast(0.0)
            val x = points[idx].x
            val y = points[idx].y
            sumW += w
            sumWX += w * x
            sumWY += w * y
            sumWXY += w * x * y
            sumWX2 += w * x * x
        }

        if (sumW <= 1e-9) return null
        val denominator = sumW * sumWX2 - sumWX * sumWX
        if (abs(denominator) <= 1e-9) return null

        var slope = (sumW * sumWXY - sumWX * sumWY) / denominator
        slope = slope.coerceIn(slopeMin, slopeMax)
        val intercept = (sumWY - slope * sumWX) / sumW

        if (!slope.isFinite() || !intercept.isFinite()) return null
        return LinearModel(slope = slope, intercept = intercept)
    }


    private fun saneWeightedOls(
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        val newestTimestamp = points.maxOfOrNull { it.timestamp } ?: targetTimestamp
        val weights = points.map { p ->
            var w = temporalWeight(
                pointTimestamp = p.timestamp,
                targetTimestamp = targetTimestamp,
                pastHalfLifeHours = 18.0,
                futureHalfLifeHours = 6.0
            , tuning)
            if (p.timestamp == newestTimestamp) w *= 1.25
            w
        }

        val model = weightedLinearModel(points, weights, slopeMin = 0.65, slopeMax = 1.35)
        val fallback = targetValue + weightedOffset(points, weights)
        val regression = model?.predict(targetValue) ?: fallback

        val nearest = points.minByOrNull {
            abs(it.x - targetValue) + abs(it.timestamp - targetTimestamp) / HOUR_MS
        }

        if (nearest != null) {
            val dx = abs(nearest.x - targetValue)
            val dtHours = abs(nearest.timestamp - targetTimestamp) / HOUR_MS
            val snap = (1.0 / (1.0 + dx * 2.5)) * (1.0 / (1.0 + dtHours / 8.0)) * 0.25
            val anchor = targetValue + (nearest.y - nearest.x)
            val blended = regression * (1.0 - snap) + anchor * snap
            val confidence = ((weights.maxOrNull() ?: 0.0) / weights.sum().coerceAtLeast(1e-6)).coerceIn(0.15, 1.0)
            return AlgorithmComputation(
                prediction = blended,
                slope = model?.slope,
                intercept = model?.intercept,
                offset = blended - targetValue,
                anchorInfluence = snap,
                confidence = confidence,
                note = "Recency-weighted OLS with local anchor snap"
            )
        }

        val confidence = ((weights.maxOrNull() ?: 0.0) / weights.sum().coerceAtLeast(1e-6)).coerceIn(0.15, 1.0)
        return AlgorithmComputation(
            prediction = regression,
            slope = model?.slope,
            intercept = model?.intercept,
            offset = regression - targetValue,
            anchorInfluence = 0.0,
            confidence = confidence,
            note = "Recency-weighted OLS"
        )
    }

    private fun xdripMedianSlope(
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        val workingSet = points
            .sortedBy { abs(it.timestamp - targetTimestamp) }
            .take(12)
            .ifEmpty { points }

        val slopes = mutableListOf<Double>()
        for (i in 0 until workingSet.size) {
            for (j in i + 1 until workingSet.size) {
                val dx = workingSet[j].x - workingSet[i].x
                if (abs(dx) <= 1e-9) continue
                slopes.add((workingSet[j].y - workingSet[i].y) / dx)
            }
        }

        if (slopes.isEmpty()) {
            val weights = workingSet.map {
                temporalWeight(it.timestamp, targetTimestamp, pastHalfLifeHours = 18.0, futureHalfLifeHours = 6.0, tuning)
            }
            val prediction = targetValue + weightedOffset(workingSet, weights)
            return AlgorithmComputation(
                prediction = prediction,
                offset = prediction - targetValue,
                anchorInfluence = 0.0,
                confidence = 0.35,
                note = "Median slope fallback to weighted offset"
            )
        }

        val slope = median(slopes).coerceIn(0.60, 1.40)
        val intercept = median(workingSet.map { it.y - slope * it.x })
        val prediction = slope * targetValue + intercept

        return if (prediction.isFinite()) {
            AlgorithmComputation(
                prediction = prediction,
                slope = slope,
                intercept = intercept,
                offset = prediction - targetValue,
                anchorInfluence = 0.0,
                confidence = 0.70,
                note = "Theil-Sen style median slope"
            )
        } else {
            val weights = workingSet.map {
                temporalWeight(it.timestamp, targetTimestamp, pastHalfLifeHours = 18.0, futureHalfLifeHours = 6.0, tuning)
            }
            val fallback = targetValue + weightedOffset(workingSet, weights)
            AlgorithmComputation(
                prediction = fallback,
                offset = fallback - targetValue,
                anchorInfluence = 0.0,
                confidence = 0.40,
                note = "Median slope invalid, using weighted offset"
            )
        }
    }

    private fun timeWeightedRobustRegression(
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        val baseWeights = points.map {
            temporalWeight(
                pointTimestamp = it.timestamp,
                targetTimestamp = targetTimestamp,
                pastHalfLifeHours = 24.0,
                futureHalfLifeHours = 8.0
            , tuning)
        }

        var weights = baseWeights.toMutableList()
        var model = weightedLinearModel(points, weights, slopeMin = 0.60, slopeMax = 1.40)
            ?: run {
                val fallback = targetValue + weightedOffset(points, baseWeights)
                return AlgorithmComputation(
                    prediction = fallback,
                    offset = fallback - targetValue,
                    anchorInfluence = 0.0,
                    confidence = 0.35,
                    note = "Robust model unavailable, using weighted offset"
                )
            }

        var finalMad = 0.05

        repeat(4) {
            val residuals = points.map { p -> p.y - model.predict(p.x) }
            val mad = median(residuals.map { abs(it) }).coerceAtLeast(0.05)
            finalMad = mad
            val scale = (mad * 1.4826).coerceAtLeast(0.05)

            weights = baseWeights.indices.map { idx ->
                val u = residuals[idx] / (1.5 * scale)
                val robustWeight = if (abs(u) <= 1.0) 1.0 else 1.0 / abs(u)
                (baseWeights[idx] * robustWeight).coerceAtLeast(1e-4)
            }.toMutableList()

            model = weightedLinearModel(points, weights, slopeMin = 0.60, slopeMax = 1.40) ?: model
        }

        val regression = model.predict(targetValue)
        val offsetFallback = targetValue + weightedOffset(points, weights)
        return if (regression.isFinite()) {
            val prediction = regression * 0.85 + offsetFallback * 0.15
            val confidence = (1.0 / (1.0 + finalMad)).coerceIn(0.20, 1.0)
            AlgorithmComputation(
                prediction = prediction,
                slope = model.slope,
                intercept = model.intercept,
                offset = prediction - targetValue,
                anchorInfluence = 0.0,
                confidence = confidence,
                note = "Huber-style robust regression"
            )
        } else {
            AlgorithmComputation(
                prediction = offsetFallback,
                offset = offsetFallback - targetValue,
                anchorInfluence = 0.0,
                confidence = 0.35,
                note = "Robust regression fallback to weighted offset"
            )
        }
    }

    private fun elasticTimeWeightedInterpolation(
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        val scale = median(points.map { abs(it.x - targetValue) }).coerceAtLeast(0.5)

        val localWeights = points.map { p ->
            val timeWeight = temporalWeight(
                pointTimestamp = p.timestamp,
                targetTimestamp = targetTimestamp,
                pastHalfLifeHours = 14.0,
                futureHalfLifeHours = 5.0
            , tuning)
            val proximity = 1.0 / (1.0 + ((abs(p.x - targetValue) / scale).pow(2.0)))
            (timeWeight * proximity).coerceAtLeast(1e-4)
        }

        val localModel = weightedLinearModel(points, localWeights, slopeMin = 0.55, slopeMax = 1.45)
        val localPrediction = localModel?.predict(targetValue)
            ?: (targetValue + weightedOffset(points, localWeights))

        val globalPrediction = timeWeightedRobustRegression(targetValue, targetTimestamp, points, tuning).prediction
        val dominance = (localWeights.maxOrNull() ?: 0.0) / localWeights.sum().coerceAtLeast(1e-6)
        val alpha = (0.35 + 0.55 * dominance).coerceIn(0.35, 0.90)

        var blended = localPrediction * alpha + globalPrediction * (1.0 - alpha)
        var snapApplied = 0.0

        val nearestByValue = points.minByOrNull { abs(it.x - targetValue) }
        if (nearestByValue != null) {
            val dx = abs(nearestByValue.x - targetValue)
            val snap = (1.0 / (1.0 + dx * 3.0)) * 0.20
            snapApplied = snap
            val anchor = targetValue + (nearestByValue.y - nearestByValue.x)
            blended = blended * (1.0 - snap) + anchor * snap
        }

        return AlgorithmComputation(
            prediction = blended,
            slope = localModel?.slope,
            intercept = localModel?.intercept,
            offset = blended - targetValue,
            anchorInfluence = snapApplied,
            confidence = alpha,
            note = "Elastic local interpolation blended with robust global trend"
        )
    }

    private fun adaptiveEnsemble(
        targetValue: Double,
        targetTimestamp: Long,
        points: List<CalPoint>,
        tuning: CalibrationTuning
    ): AlgorithmComputation {
        val sane = saneWeightedOls(targetValue, targetTimestamp, points, tuning)
        val xdrip = xdripMedianSlope(targetValue, targetTimestamp, points, tuning)
        val robust = timeWeightedRobustRegression(targetValue, targetTimestamp, points, tuning)
        val elastic = elasticTimeWeightedInterpolation(targetValue, targetTimestamp, points, tuning)

        val pSane = sane.prediction
        val pXdrip = xdrip.prediction
        val pRobust = robust.prediction
        val pElastic = elastic.prediction

        val predictions = listOf(pSane, pXdrip, pRobust, pElastic)
        val center = median(predictions)
        val dispersion = predictions.map { abs(it - center) }.average().coerceAtLeast(0.01)
        val harmony = (1.0 / (1.0 + dispersion)).coerceIn(0.20, 1.00)

        val scale = median(points.map { abs(it.x - targetValue) }).coerceAtLeast(0.5)
        val localWeights = points.map { p ->
            val timeWeight = temporalWeight(
                pointTimestamp = p.timestamp,
                targetTimestamp = targetTimestamp,
                pastHalfLifeHours = 12.0,
                futureHalfLifeHours = 4.0
            , tuning)
            val proximity = 1.0 / (1.0 + ((abs(p.x - targetValue) / scale).pow(2.0)))
            (timeWeight * proximity).coerceAtLeast(1e-4)
        }
        val localDominance = (localWeights.maxOrNull() ?: 0.0) / localWeights.sum().coerceAtLeast(1e-6)

        var wSane = 0.20
        var wXdrip = 0.15
        var wRobust = 0.30
        var wElastic = 0.35 + (0.20 * localDominance)

        if (harmony < 0.50) {
            wSane *= 0.85
            wXdrip *= 0.70
            wRobust *= 1.10
            wElastic *= 1.10
        }

        val sumW = wSane + wXdrip + wRobust + wElastic
        var blended = (
            pSane * wSane +
                pXdrip * wXdrip +
                pRobust * wRobust +
                pElastic * wElastic
            ) / sumW
        var snapApplied = 0.0

        val nearest = points.minByOrNull {
            abs(it.x - targetValue) + abs(it.timestamp - targetTimestamp) / HOUR_MS
        }
        if (nearest != null) {
            val dx = abs(nearest.x - targetValue)
            val dtHours = abs(nearest.timestamp - targetTimestamp) / HOUR_MS
            val snap = (1.0 / (1.0 + dx * 2.0)) * (1.0 / (1.0 + dtHours / 6.0)) * 0.35
            snapApplied = snap
            val anchor = targetValue + (nearest.y - nearest.x)
            blended = blended * (1.0 - snap) + anchor * snap
        }

        return AlgorithmComputation(
            prediction = blended,
            slope = robust.slope ?: elastic.slope,
            intercept = robust.intercept ?: elastic.intercept,
            offset = blended - targetValue,
            anchorInfluence = snapApplied,
            confidence = harmony,
            note = "Adaptive ensemble blend of sane/xDrip/robust/elastic"
        )
    }
}
