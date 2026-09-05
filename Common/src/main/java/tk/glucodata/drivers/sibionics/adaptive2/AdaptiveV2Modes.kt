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
     * Per-mode driving-noise spectral densities.
     *
     * Rationale for the shape rather than the exact digits:
     *  - STEADY keeps velocity and jerk noise low so quiet stretches produce a
     *    narrow interval instead of tracking minute noise.
     *  - DYNAMIC opens *velocity* by four orders of magnitude. That is both
     *    what lets a genuine rapid excursion be followed without lag, and what
     *    makes its predicted variance differ enough from STEADY's for the
     *    likelihood to separate them at all — see [glucoseBlock].
     *  - ARTIFACT keeps glucose noise at STEADY levels and opens only the
     *    artifact state, so an excursion it wins is explained away from glucose.
     *  - DRIFT opens only sensitivity and offset, and nothing else.
     *
     * The sensor-state noises are deliberately tiny, and that is the whole
     * point. Without an external reference, residual sensitivity and glucose
     * level are not separable from one scalar observation per minute: any
     * constant fraction can be attributed to either. Measured on an eleven-day
     * recorded trace, an earlier setting (3e-6 on log-sensitivity) let the
     * residual wander to 1.11 — the model had decided the manufacturer's
     * sensitivity was 11% wrong — which biased every reported value about 10%
     * low and showed up on device as "V2 reads low". Nothing in the data
     * supported that; it was the slow state quietly absorbing level.
     *
     * So free-running drift is priced out of reach, and external references are
     * what unlock it: a fingerstick moves these states through the Kalman
     * update and then moves their shrinkage targets, which is the only evidence
     * that can actually identify them.
     */
    private val PROCESS_NOISE: Array<DoubleArray> = arrayOf(
        // STEADY
        noise(velocity = 3.0e-7, jerk = 3.0e-8, interstitial = 1.0e-1,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 1.0e-6),
        // DYNAMIC
        noise(velocity = 1.2e-3, jerk = 3.0e-6, interstitial = 1.0e-1,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 1.0e-6),
        // ARTIFACT
        noise(velocity = 3.0e-7, jerk = 3.0e-8, interstitial = 1.0e-1,
            logSensitivity = 1.0e-9, bias = 2.0e-8, artifact = 2.5e-2),
        // DRIFT
        noise(velocity = 5.0e-7, jerk = 5.0e-8, interstitial = 1.0e-1,
            logSensitivity = 2.0e-8, bias = 1.0e-6, artifact = 1.0e-6),
    )

    /**
     * @param velocity spectral density of the white noise driving [V2.V]
     *   directly: unmodelled changes of glucose rate.
     * @param jerk spectral density of the white noise driving [V2.ACC]:
     *   unmodelled curvature.
     *
     * @param interstitial driving noise on [V2.I] — the state the observation
     *   actually reads, and therefore the one that sets tracking bandwidth.
     *
     *   This is the knob that decides how fast the estimate can follow the
     *   sensor. It is uniform across modes on purpose, and that was measured
     *   rather than assumed: raising it — whether uniformly or only for
     *   DYNAMIC — bought about 0.2 min of cross-correlation lag and cost the
     *   mode separation that makes the IMM worth having, dropping pDynamic on a
     *   real excursion from 0.47 to 0.22 while widening the interval. Tracking
     *   bandwidth belongs to the velocity drive and the lag inversion; letting
     *   the observed state chase the sensor directly just makes STEADY able to
     *   explain movement, which is the one thing it must not do.
     *
     * There is deliberately no separate entry for [V2.B]. Blood glucose has no
     * independent driving noise — it is the integral of velocity — and an entry
     * here would be read by nothing, which is exactly how the previous version
     * came to have three configured values of which only one was used.
     */
    private fun noise(
        velocity: Double,
        jerk: Double,
        interstitial: Double,
        logSensitivity: Double,
        bias: Double,
        artifact: Double,
    ): DoubleArray = DoubleArray(V2.N).also {
        it[V2.V] = velocity
        it[V2.ACC] = jerk
        it[V2.I] = interstitial
        it[V2.LOG_S] = logSensitivity
        it[V2.BIAS] = bias
        it[V2.ARTIFACT] = artifact
    }

    /**
     * Diagonal process noise for the states *outside* the glucose block.
     *
     * The sensor states ([V2.LOG_S], [V2.BIAS], [V2.ARTIFACT]) are independent
     * random walks, so scaling their variance by dt is exact. [V2.B], [V2.V]
     * and [V2.ACC] are excluded and supplied by [glucoseBlock] instead: they
     * are coupled, and a diagonal both understates the resulting covariance and
     * cannot represent the cross terms at all.
     */
    fun processNoise(mode: AdaptiveV2Mode, dtMinutes: Double, out: DoubleArray) {
        val base = PROCESS_NOISE[mode.ordinal]
        for (i in 0 until V2.N) {
            out[i] = if (i == V2.B || i == V2.V || i == V2.ACC) 0.0 else base[i] * dtMinutes
        }
    }

    /**
     * Coupled covariance for the [V2.B]/[V2.V]/[V2.ACC] block, row-major 3x3.
     *
     * The block is driven by two independent continuous white noises, which is
     * the stochastic model this state actually follows:
     *
     *  - `q_v` entering velocity (continuous white-noise-acceleration):
     *    `[dt³/3, dt²/2, 0; dt²/2, dt, 0; 0, 0, 0] * q_v`
     *  - `q_a` entering acceleration (continuous white-noise-jerk):
     *    `[dt⁵/20, dt⁴/8, dt³/6; dt⁴/8, dt³/3, dt²/2; dt³/6, dt²/2, dt] * q_a`
     *
     * Their contributions add because the sources are independent. That is not
     * the same as adding a per-state diagonal on top of a block, which would
     * double count the same noise.
     *
     * The velocity term is what makes DYNAMIC's rate freedom real. An earlier
     * version derived the whole block from the jerk density alone, leaving the
     * configured rate variance unread: DYNAMIC's predicted variance stayed
     * orders of magnitude below the measurement noise, its likelihood never
     * separated from STEADY's, and the mode probabilities simply tracked the
     * transition prior. The IMM was four filters computing the same answer.
     *
     * The mild mean reversion [AdaptiveV2Transition] applies to acceleration is
     * not modelled here; at one-minute substeps against a 30-minute time
     * constant it changes the jerk term by about 3%, and omitting it slightly
     * over-states covariance, which is the safe direction.
     */
    fun glucoseBlock(mode: AdaptiveV2Mode, dtMinutes: Double, out: DoubleArray) {
        val qv = PROCESS_NOISE[mode.ordinal][V2.V]
        val qa = PROCESS_NOISE[mode.ordinal][V2.ACC]
        // Level drive on the state the observation actually reads.
        //
        // predict() adds the diagonal q only for states outside the B/V/ACC
        // block, so the level entry never reached B at all: B grew about 1e-7
        // per minute from the velocity term alone and was far too stiff for the
        // observation to move it, which showed up as a same-minute step
        // response of 0.49. Tracking bandwidth has to live on whichever state
        // the observation constrains, and that is now B.
        val qLevel = PROCESS_NOISE[mode.ordinal][V2.I]
        val dt2 = dtMinutes * dtMinutes
        val dt3 = dt2 * dtMinutes
        val dt4 = dt3 * dtMinutes
        val dt5 = dt4 * dtMinutes

        out[0] = qv * dt3 / 3.0 + qa * dt5 / 20.0 + qLevel * dtMinutes
        out[1] = qv * dt2 / 2.0 + qa * dt4 / 8.0
        out[2] = qa * dt3 / 6.0
        out[3] = out[1]
        out[4] = qv * dtMinutes + qa * dt3 / 3.0
        out[5] = qa * dt2 / 2.0
        out[6] = out[2]
        out[7] = out[5]
        out[8] = qa * dtMinutes
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
    /** Artifact evidence for one sample as a scalar, for consumers outside the mode prior. */
    fun artifactEvidenceFor(impedanceDisturbance: Float, vendorArtifactHint: Float): Float =
        artifactPrior.artifactEvidence(impedanceDisturbance, vendorArtifactHint).coerceIn(0f, 1f)

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
