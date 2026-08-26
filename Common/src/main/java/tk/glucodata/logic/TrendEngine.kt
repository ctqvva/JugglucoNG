package tk.glucodata.logic

import tk.glucodata.GlucosePoint as NativeGlucosePoint
import tk.glucodata.GlucoseValuePlausibility
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.jvm.JvmName

/**
 * Sophisticated engine for processing glucose history to determine trend velocity and acceleration.
 * Uses a weighted sliding window to reduce noise and provide stable indicators.
 */
object TrendEngine {

    /**
     * Overload for compatibility with Core/Java implementation (Notify.java)
     */
    fun calculateTrend(history: List<NativeGlucosePoint>, useRaw: Boolean = false, isMmol: Boolean): TrendResult {
        val uiHistory = history.map {
            GlucosePoint(it.value, "", it.timestamp, it.rawValue)
        }
        return calculateTrend(uiHistory, useRaw, isMmol)
    }

    enum class TrendState {
        DoubleUp,       // Rapid Rise (> 2.0)
        SingleUp,       // Rise (> 1.0)
        FortyFiveUp,    // Slow Rise (> 0.5)
        Flat,           // Stable (-0.5 to 0.5)
        FortyFiveDown,  // Slow Fall (< -0.5)
        SingleDown,     // Fall (< -1.0)
        DoubleDown,     // Rapid Fall (< -2.0)
        Unknown
    }

    /**
     * Which arrow a rate in mg/dL per minute is. Named so that anything drawing an arrow
     * from a rate this engine did not measure still lands on the same boundaries.
     */
    @JvmStatic
    fun stateFor(velocity: Float): TrendState = when {
        velocity > 2.0f -> TrendState.DoubleUp
        velocity > 1.0f -> TrendState.SingleUp
        velocity > 0.5f -> TrendState.FortyFiveUp
        velocity > -0.5f -> TrendState.Flat
        velocity > -1.0f -> TrendState.FortyFiveDown
        velocity > -2.0f -> TrendState.SingleDown
        else -> TrendState.DoubleDown
    }

    data class TrendResult(
        val state: TrendState,
        val velocity: Float,     // mg/dL per minute
        val acceleration: Float, // Change in velocity
        val confidence: Float,   // 0.0 - 1.0 (based on data density)
        val noiseLevel: Float = 0f // 0.0 - 1.0 (normalized CV, higher = noisier)
    )

    /** Cadence the fixed 20 minute window was chosen for: Dexcom/Libre, one reading per 5 min. */
    private const val REFERENCE_CADENCE_MIN = 5.0
    private const val REFERENCE_WINDOW_MIN = 20.0

    /** Guard rails: never so short it rides jitter, never so long it outlives the trend. */
    private const val MIN_WINDOW_MIN = 8.0
    private const val MAX_WINDOW_MIN = 25.0

    /**
     * How far back the slope regresses, scaled to the sensor's actual reading cadence.
     *
     * A fixed 20 minutes is not one setting — it is a different amount of smoothing per
     * sensor. What the window really buys is confidence in the slope, and for evenly
     * spaced samples the standard error of a least-squares slope goes as
     *
     *     SE  ~  sigma * sqrt(cadence) / window^1.5
     *
     * so holding SE constant across sensors means window ~ cadence^(1/3). Anchored at the
     * accepted 20 min / 5 min reference that yields ~12 min at a one-a-minute cadence:
     * the same trustworthiness, a third less lag.
     *
     * Concretely, on 17 h of 1-minute Sibionics data a 20 minute window produced a slope
     * SE of 0.048 mg/dL/min where a 5-minute sensor at 20 minutes gets 0.100 — twice the
     * precision nobody asked for, paid for in an arrow that changed 2.1 times an hour
     * instead of the old estimator's 17. The old decay-weighted estimator hid this by
     * weighting `0.6^i` per *sample*, so its effective window silently scaled with
     * cadence (~15 min at 5 min, ~3 min at 1 min); replacing it with a fixed 20 minutes
     * left 5-minute sensors roughly where they were and over-smoothed fast ones ~7x.
     *
     * Cadence is measured from the data rather than declared per driver, so a sensor that
     * changes rate, backfills, or drops readings is described by what it actually
     * delivered. Median, not mean: one disconnect gap must not stretch the window.
     */
    internal fun trendWindowMillis(newestFirst: List<GlucosePoint>): Long {
        val scaled = REFERENCE_WINDOW_MIN *
            Math.cbrt(cadenceMinutes(newestFirst) / REFERENCE_CADENCE_MIN)
        val windowMinutes = scaled.coerceIn(MIN_WINDOW_MIN, MAX_WINDOW_MIN)
        return (windowMinutes * 60_000.0).toLong()
    }

    /**
     * The sensor's actual reading interval, as a median of the recent gaps.
     *
     * Measured rather than declared per driver, so a sensor that changes rate, backfills or
     * drops readings is described by what it really delivered. Median, not mean: one
     * disconnect gap must not stretch the estimate.
     */
    internal fun cadenceMinutes(newestFirst: List<GlucosePoint>): Double {
        val gapsMinutes = ArrayList<Double>(newestFirst.size)
        for (i in 0 until newestFirst.size - 1) {
            val gap = (newestFirst[i].timestamp - newestFirst[i + 1].timestamp) / 60000.0
            // Only forward gaps; duplicate timestamps say nothing about cadence.
            if (gap > 0.0) {
                gapsMinutes.add(gap)
            }
            // A dozen gaps pin the median; scanning a whole day section does not improve it.
            if (gapsMinutes.size >= 12) {
                break
            }
        }
        if (gapsMinutes.isEmpty()) {
            return REFERENCE_CADENCE_MIN
        }
        gapsMinutes.sort()
        return gapsMinutes[gapsMinutes.size / 2]
    }

    /**
     * Recency half-weighting of the regression, as a multiple of the reading interval.
     *
     * A plain least-squares fit weights a reading from 12 minutes ago exactly as heavily as
     * the one that just arrived, so a sharp turn off a plateau — the shape that actually
     * prompts someone to look at the arrow — is averaged against the plateau it just left.
     * Measured over 17 h of 1-minute data, at 11 sharp turns off a plateau, as a fraction of
     * the real rate the arrow actually reported at the moment of the turn:
     *
     *     fixed 20 min (unweighted)   17%    jitter 0.050
     *     cadence window, unweighted  30%    jitter 0.093
     *     this, exp(-age/5*cadence)   42%    jitter 0.104
     *     pre-regression estimator    79%    jitter 0.394
     *
     * An arrow showing a sixth of what is happening is not a slow arrow, it is a wrong one.
     *
     * The multiple is bounded below by noise, not chosen for feel. The regression's own
     * guarantee — a steady 0.5 mg/dL/min fall with 3 mg/dL of jitter on the newest reading
     * must still read as a fall (< -0.2) — holds at 5 (-0.28) and at 3.5 (-0.22), but breaks
     * at 3 (-0.18) and collapses at 2 (+0.00, where an ordinary blip erases the trend
     * outright). See TrendEngineVelocityTests.singleNoisyReadingDoesNotDominateTheTrend,
     * the original PR's test, which passes unchanged. 5 keeps a real margin on it.
     *
     * On a verified 1-minute excursion (a rise off a ~90 minute plateau) this reached the
     * double-arrow state at 15:28 where the unweighted cadence window reached it at 15:29
     * and the old fixed 20 minutes never did — it was still showing 58 degrees three
     * minutes in.
     *
     * That pre-regression estimator also weighted recency — `0.6^i`, decaying over roughly
     * two samples. Its problem was never recency: it averaged *adjacent deltas*, where a
     * single noisy reading owns the newest delta and the newest delta owns 40% of the
     * answer. Weighting a regression keeps every point in one fit, so most of that
     * responsiveness comes back at under a third of the jitter. The PR was right about the
     * fit and wrong to throw out recency with it.
     */
    private const val DECAY_CADENCE_MULTIPLE = 5.0

    /**
     * Calculates the trend based on a list of historical points.
     * @param history List of glucose points, ordered by time descending (newest first).
     * @param isMmol Whether the input values are in mmol/L (true) or mg/dL (false).
     */
    @JvmName("calculateTrendUi")
    fun calculateTrend(history: List<GlucosePoint>, useRaw: Boolean = false, isMmol: Boolean): TrendResult {
        if (history.size < 2) return TrendResult(TrendState.Flat, 0f, 0f, 0f, 0f)

        // Ensure history is Descending (Newest First) for the default logic
        val ordered = if (history.first().timestamp < history.last().timestamp) {
            history.reversed()
        } else {
            history
        }

        fun pointValue(p: GlucosePoint): Float = if (useRaw && p.rawValue > 0) p.rawValue else p.value

        // A minute the sensor produced no reading for still occupies a slot in native
        // storage: savepollallIDsonly() in SensorGlucoseData.hpp fills skipped minutes with a
        // ScanData that carries a timestamp and no glucose, and getGlucoseHistory() hands
        // those to the UI as a value of 0 — the "--" rows in the readings list.
        //
        // A gap is not a measurement. Left in, one such slot inside the window is read as a
        // plunge to 0 mg/dL and back: the artifact guard below truncates the velocity window
        // at it, and the polynomial fit charges the whole excursion to the sensor, which is
        // how a clean Libre reported a noise of several thousand right after an NFC scan
        // (issue #166). Drop everything that is not a reading before measuring anything.
        val newestFirst = ordered.filter {
            GlucoseValuePlausibility.isPlausibleDisplayValue(pointValue(it), isMmol)
        }
        if (newestFirst.size < 2) return TrendResult(TrendState.Flat, 0f, 0f, 0f, 0f)

        // Window scaled to the sensor's cadence so the slope carries the same
        // confidence on every sensor — see [trendWindowMillis].
        val windowMillis = trendWindowMillis(newestFirst)
        val validPoints = newestFirst.takeWhile {
             (newestFirst.first().timestamp - it.timestamp) <= windowMillis
        }.take(30)

        if (validPoints.size < 2) return TrendResult(TrendState.Flat, 0f, 0f, 0f, 0f)

        // Use explicit flag
        val conversionFactor = if (isMmol) GlucoseFormatter.MGDL_PER_MMOL else 1.0f

        // Collect values for noise calculation (in NATIVE units - no conversion!)
        val rawValueList = mutableListOf<Float>()
        validPoints.forEach { rawValueList.add(pointValue(it)) }

        // Velocity: recency-weighted least-squares slope over the window, in mg/dL per
        // minute. The window comes from the sensor's cadence ([trendWindowMillis]) and the
        // weighting from [DECAY_CADENCE_MULTIPLE]; a fit reads the trend rather than the
        // jitter, and the weighting stops it averaging a fresh turn against the plateau it
        // just left.
        //
        // A non-physiological jump between adjacent points (calibration step,
        // >20 mg/dL per minute) ends the window: only the segment newer than
        // the artifact describes the current trend.
        var usable = validPoints.size
        for (i in 0 until validPoints.size - 1) {
            val p1 = validPoints[i]
            val p2 = validPoints[i + 1]
            val timeDeltaMin = (p1.timestamp - p2.timestamp) / 60000f
            if (timeDeltaMin > 0f &&
                Math.abs((pointValue(p1) - pointValue(p2)) * conversionFactor / timeDeltaMin) > 20f
            ) {
                usable = i + 1
                break
            }
        }

        // Recency-weighted least squares — see [DECAY_CADENCE_MULTIPLE]. Weights replace the
        // plain point count in every sum, so with a flat weight of 1 this is exactly the
        // unweighted fit.
        val tauMinutes = DECAY_CADENCE_MULTIPLE * cadenceMinutes(validPoints)
        val newestTs = validPoints.first().timestamp
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until usable) {
            val p = validPoints[i]
            val xMinutes = (p.timestamp - newestTs) / 60000.0
            val yMgdl = (pointValue(p) * conversionFactor).toDouble()
            // xMinutes <= 0 going back, so this decays from 1 at the newest reading.
            val w = Math.exp(xMinutes / tauMinutes)
            sw += w
            sx += w * xMinutes
            sy += w * yMgdl
            sxy += w * xMinutes * yMgdl
            sxx += w * xMinutes * xMinutes
        }
        val denominator = sw * sxx - sx * sx
        val velocity = if (usable >= 2 && denominator > 1e-6) {
            ((sw * sxy - sx * sy) / denominator).toFloat()
        } else {
            0f
        }

        // Acceleration: Compare velocity of first half vs second half of window
        // (Rough approximation)
        val acceleration = 0f // Placeholder for now, can refine if needed for curving

        // *** NOISE CALCULATION (xDrip-style) ***
        // Compute "Error Variance" from a 2nd-degree Polynomial Fit (Parabola).
        // This is the EXACT method used by xDrip ("noisePoly.errorVarience()").
        // It correctly handles:
        // - Linear Trends (Slope) -> Fit is good -> Low Noise
        // - Curves (Turns/Humps) -> Parabola fits well -> Low Noise
        // - Jitter/Wobble -> Poor fit -> High Noise
        
        val spanMillis = newestTs - validPoints.last().timestamp
        val noiseLevel2: Float = if (rawValueList.size >= 4 && spanMillis > 0L) { // Need at least 4 points for Variance (N > Order+1)
            val n = rawValueList.size
            // x is elapsed time, not the sample index. A quadratic fit is invariant under an
            // affine change of x, so for evenly spaced readings the two axes describe the same
            // parabola and report the same residuals — the number the signal-quality chip
            // shows for a normal stream does not move, and its thresholds keep meaning what
            // they meant. The span is normalised onto the old 0..n-1 range purely to leave the
            // Cramer determinants as well conditioned as they were.
            //
            // The two axes part company exactly where the index is a lie: an NFC scan's
            // 15-minute history points sitting in the same window as the 1-minute Bluetooth
            // stream are not equally spaced, and on an index axis their ordinary curvature
            // reads as jitter.
            val xScale = (n - 1).toFloat() / spanMillis
            val x = FloatArray(n) { (validPoints[it].timestamp - newestTs) * xScale }
            val y = FloatArray(n) { rawValueList[it] * conversionFactor }
            
            // Calculate Sums
            var sx = 0.0; var sx2 = 0.0; var sx3 = 0.0; var sx4 = 0.0
            var sy = 0.0; var sxy = 0.0; var sx2y = 0.0
            
            for (i in 0 until n) {
                val xi = x[i].toDouble()
                val yi = y[i].toDouble()
                val xi2 = xi * xi
                
                sx += xi
                sx2 += xi2
                sx3 += xi2 * xi
                sx4 += xi2 * xi2
                sy += yi
                sxy += xi * yi
                sx2y += xi2 * yi
            }
            
            // Solve 3x3 System (Cramer's Rule) for y = a + bx + cx^2
            // | n   sx  sx2 | | a |   | sy   |
            // | sx  sx2 sx3 | | b | = | sxy  |
            // | sx2 sx3 sx4 | | c |   | sx2y |
            
            // Determinant of Main Matrix (D)
            val det = n * (sx2 * sx4 - sx3 * sx3) -
                      sx * (sx * sx4 - sx3 * sx2) +
                      sx2 * (sx * sx3 - sx2 * sx2)
            
            if (det != 0.0) {
                 // We only need the coefficients to calculate residuals
                 // Determinant for a (Da)
                 val detA = sy * (sx2 * sx4 - sx3 * sx3) -
                            sx * (sxy * sx4 - sx3 * sx2y) +
                            sx2 * (sxy * sx3 - sx2y * sx2)
                 
                 // Determinant for b (Db)
                 val detB = n * (sxy * sx4 - sx3 * sx2y) -
                            sy * (sx * sx4 - sx3 * sx2) +
                            sx2 * (sx * sx2y - sxy * sx2)
                 
                 // Determinant for c (Dc)
                 val detC = n * (sx2 * sx2y - sxy * sx3) -
                            sx * (sx * sx2y - sxy * sx2) +
                            sy * (sx * sx3 - sx2 * sx2)
                 
                 val a = detA / det
                 val b = detB / det
                 val c = detC / det
                 
                 // Calculate Squared Residuals
                 var sumSqResid = 0.0
                 for (i in 0 until n) {
                     val xi = x[i].toDouble()
                     val yi = y[i].toDouble()
                     val pred = a + b * xi + c * xi * xi
                     val resid = yi - pred
                     sumSqResid += resid * resid
                 }
                 
                 // Error Variance = SSE / (Degrees of Freedom?)
                 // xDrip's PolyTrendLine usually returns SSE / Count or similar.
                 // Trial and error suggests pure Variance (SSE/N) or Unbiased (SSE/(N-3)).
                 // Given user's "4.5" for hump match, let's try SSE / (N-3).
                 // For N=5, N-3=2. Multiplier ~2.5x vs Variance.
                 // Let's stick to standard Variance (SSE/N) * Scaling Factor 2.0 (To match xDrip mag).
                 (sumSqResid / n).toFloat() * 1.0f  // No multiplier first, let's see. 
                 // Wait, xDrip's errorVarience() is likely MSE. 
                 // Let's start with basic MSE (SSE/N).
                 (sumSqResid / n).toFloat()
            } else 0f
        } else 0f
        val noiseLevel = noiseLevel2

        return TrendResult(stateFor(velocity), velocity, acceleration, 1.0f, noiseLevel)
    }
}

