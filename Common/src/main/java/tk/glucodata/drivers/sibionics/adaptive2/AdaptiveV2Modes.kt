package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.max
import kotlin.math.min

/**
 * The four hypotheses Adaptive V2 keeps alive at all times.
 *
 * [STEADY] and [DYNAMIC] separate quiet glucose from real movement.
 * [ARTIFACT] is the one that matters most for safety: it lets the filter say
 * "the *sensor* moved, the glucose did not", which is the only way to tell a
 * real rapid fall apart from a sensor suddenly reporting a low excursion.
 * [DRIFT] absorbs slow sensitivity/offset change so it is not mistaken for
 * glucose movement.
 */
internal enum class AdaptiveV2Mode {
    STEADY,
    DYNAMIC,
    ARTIFACT,
    DRIFT;

    companion object {
        val ALL = entries.toTypedArray()
        val COUNT = ALL.size
    }
}

/**
 * Evidence that the current sample is more likely to be a sensor artifact than
 * a glucose movement, expressed as a shift in the mode prior.
 *
 * This is the seam for a future learned artifact detector. Everything the IMM
 * needs from such a model is a single number in [0,1] per sample; nothing in
 * the filter knows or cares how it was produced. A learned implementation would
 * replace [TelemetryArtifactPrior] and change nothing else — no state, no
 * observation model, no serialisation.
 *
 * Deliberately not added here: any learned model at all. There is no training
 * dataset with labelled compression events for this sensor, and a detector
 * fitted to a handful of hand-picked traces would be worse than the telemetry
 * rule it replaced while looking far more authoritative.
 */
internal fun interface AdaptiveV2ArtifactPrior {
    /**
     * @return additional prior mass to move into the artifact mode, in [0,1].
     *   Zero leaves the base transition matrix untouched.
     */
    fun artifactEvidence(impedanceDisturbance: Float, vendorArtifactHint: Float): Float
}

/**
 * The shipped prior: front-end telemetry only.
 *
 * A resistance step and a vendor quality flag are the two signals that
 * genuinely carry information about the sensor rather than about glucose.
 */
internal object TelemetryArtifactPrior : AdaptiveV2ArtifactPrior {
    override fun artifactEvidence(impedanceDisturbance: Float, vendorArtifactHint: Float): Float =
        (impedanceDisturbance * IMPEDANCE_WEIGHT + vendorArtifactHint * VENDOR_WEIGHT)
            .coerceIn(0f, MAX_EVIDENCE)

    private const val IMPEDANCE_WEIGHT = 0.22f
    private const val VENDOR_WEIGHT = 0.30f
    private const val MAX_EVIDENCE = 0.40f
}

/**
 * Per-mode process noise and mode-transition behaviour.
 *
 * Every value below is a variance *per minute*. Long gaps are propagated in
 * one-minute substeps by the estimator rather than as a single scaled jump, so
 * a gap widens the posterior by the same amount however it is delivered.
 */
internal object AdaptiveV2ModeModel {
    /** Swappable for a learned detector; see [AdaptiveV2ArtifactPrior]. */
    var artifactPrior: AdaptiveV2ArtifactPrior = TelemetryArtifactPrior

    /**
     * Diagonal process noise per mode, in (unit)²/min.
     *
     * Rationale for the shape rather than the exact digits:
     *  - STEADY suppresses rate and acceleration noise so quiet stretches
     *    produce a narrow interval instead of tracking minute noise.
     *  - DYNAMIC opens rate/acceleration by two orders of magnitude, which is
     *    what lets a genuine rapid fall be followed without lag.
     *  - ARTIFACT keeps glucose noise at STEADY levels and opens only the
     *    artifact state, so an excursion it wins is explained away from glucose.
     *  - DRIFT opens only sensitivity and offset, an order of magnitude above
     *    their baseline, and nothing else.
     *
     * The sensor-state noises are chosen so that each state's stationary spread
     * under its own relaxation time constant matches its initial prior — ~7% on
     * sensitivity, ~0.25 mmol/L on offset. Picking them independently is what
     * makes a drift model quietly collapse toward zero over a few days, or
     * conversely wander off; matching them keeps the prior meaningful for the
     * whole wear.
     */
    private val PROCESS_NOISE: Array<DoubleArray> = arrayOf(
        // STEADY
        noise(glucose = 2.0e-5, rate = 3.0e-7, acceleration = 3.0e-8,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 1.0e-6),
        // DYNAMIC
        noise(glucose = 6.0e-5, rate = 1.2e-3, acceleration = 3.0e-6,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 1.0e-6),
        // ARTIFACT
        noise(glucose = 2.0e-5, rate = 3.0e-7, acceleration = 3.0e-8,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 2.5e-2),
        // DRIFT
        noise(glucose = 2.0e-5, rate = 5.0e-7, acceleration = 5.0e-8,
            logSensitivity = 3.0e-6, bias = 5.0e-5, artifact = 1.0e-6),
    )

    private fun noise(
        glucose: Double,
        rate: Double,
        acceleration: Double,
        logSensitivity: Double,
        bias: Double,
        artifact: Double,
    ): DoubleArray = DoubleArray(V2.N).also {
        it[V2.B] = glucose
        // Interstitial glucose has no independent driving noise: it is a
        // filtered version of blood glucose. A small floor keeps the row from
        // becoming singular.
        it[V2.I] = glucose * 0.25
        it[V2.V] = rate
        it[V2.ACC] = acceleration
        it[V2.LOG_S] = logSensitivity
        it[V2.BIAS] = bias
        it[V2.ARTIFACT] = artifact
    }

    /**
     * Diagonal process noise for a step of [dtMinutes].
     *
     * The sensor states ([V2.LOG_S], [V2.BIAS], [V2.ARTIFACT]) are independent
     * random walks, so scaling their variance by dt is exact. The glucose block
     * is *not* independent — noise entering acceleration propagates into rate
     * and position within the same step — so a diagonal `q*dt` understates the
     * resulting covariance and produces cross-terms it cannot represent at all.
     * [glucoseBlock] supplies the correct coupled covariance; this function
     * fills only the diagonal that callers add directly.
     */
    fun processNoise(mode: AdaptiveV2Mode, dtMinutes: Double, out: DoubleArray) {
        val base = PROCESS_NOISE[mode.ordinal]
        for (i in 0 until V2.N) out[i] = base[i] * dtMinutes
    }

    /**
     * Coupled covariance for the [V2.B]/[V2.V]/[V2.ACC] block under a
     * continuous white-noise-jerk model with spectral density taken from the
     * mode's acceleration noise.
     *
     * Standard result:
     * ```
     *   Q = q * [ dt^5/20  dt^4/8  dt^3/6
     *             dt^4/8   dt^3/3  dt^2/2
     *             dt^3/6   dt^2/2  dt     ]
     * ```
     * Written into [out] as a 3x3 in row-major order.
     */
    fun glucoseBlock(mode: AdaptiveV2Mode, dtMinutes: Double, out: DoubleArray) {
        val q = PROCESS_NOISE[mode.ordinal][V2.ACC]
        val dt2 = dtMinutes * dtMinutes
        val dt3 = dt2 * dtMinutes
        val dt4 = dt3 * dtMinutes
        val dt5 = dt4 * dtMinutes
        out[0] = q * dt5 / 20.0
        out[1] = q * dt4 / 8.0
        out[2] = q * dt3 / 6.0
        out[3] = out[1]
        out[4] = q * dt3 / 3.0
        out[5] = q * dt2 / 2.0
        out[6] = out[2]
        out[7] = out[5]
        out[8] = q * dtMinutes
    }

    /**
     * Mode-transition matrix, row = from, column = to, per one-minute step.
     *
     * The diagonal is deliberately heavy. Mode identity that flips every minute
     * is not a hypothesis, it is noise; persistence is what makes "this has been
     * an artifact for six minutes" a statement worth acting on.
     */
    private val TRANSITION: Array<DoubleArray> = arrayOf(
        //             steady  dynamic artifact drift
        doubleArrayOf(0.975, 0.015, 0.006, 0.004),
        doubleArrayOf(0.100, 0.880, 0.015, 0.005),
        doubleArrayOf(0.150, 0.040, 0.800, 0.010),
        doubleArrayOf(0.080, 0.015, 0.005, 0.900),
    )

    /**
     * Telemetry does not add glucose offsets. It moves the *prior* over which
     * hypothesis is true, which is the only place a resistance reading has any
     * business acting.
     *
     * @param impedanceDisturbance normalised |Δimpedance| in [0,1].
     * @param vendorArtifactHint front-end quality flags suggesting a bad sample.
     */
    /**
     * @param dtMinutes elapsed time for this step. The matrix is defined per
     *   minute; a shorter substep is interpolated toward the identity so mode
     *   persistence scales with real time instead of with call count.
     */
    fun transition(
        from: AdaptiveV2Mode,
        impedanceDisturbance: Float,
        vendorArtifactHint: Float,
        dtMinutes: Double,
        out: DoubleArray,
    ) {
        val base = TRANSITION[from.ordinal]
        base.copyInto(out)
        if (dtMinutes < 1.0) {
            val alpha = dtMinutes.coerceIn(0.0, 1.0)
            for (i in out.indices) {
                val identity = if (i == from.ordinal) 1.0 else 0.0
                out[i] = identity + alpha * (out[i] - identity)
            }
        }
        val boost = artifactPrior
            .artifactEvidence(impedanceDisturbance, vendorArtifactHint)
            .coerceIn(0f, 1f)
            .toDouble()
        if (boost <= 0.0) return
        // Move mass into the artifact column, taken proportionally from the
        // others so the row stays a distribution.
        val artifactIndex = AdaptiveV2Mode.ARTIFACT.ordinal
        val available = 1.0 - out[artifactIndex]
        val transferred = min(boost, available * MAX_TRANSFER_FRACTION)
        if (transferred <= 0.0) return
        val scale = (available - transferred) / max(available, 1e-9)
        for (i in out.indices) {
            if (i != artifactIndex) out[i] *= scale
        }
        out[artifactIndex] += transferred
    }

    private const val MAX_TRANSFER_FRACTION = 0.85
}
