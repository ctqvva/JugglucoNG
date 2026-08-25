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
 * Per-mode process noise and mode-transition behaviour.
 *
 * Every value below is a variance *per minute*; [processNoise] scales them by
 * the elapsed step so a gap in the data widens the posterior at the same rate
 * whether it arrives as one long step or several short ones.
 */
internal object AdaptiveV2ModeModel {
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

    fun processNoise(mode: AdaptiveV2Mode, dtMinutes: Double, out: DoubleArray) {
        val base = PROCESS_NOISE[mode.ordinal]
        for (i in 0 until V2.N) out[i] = base[i] * dtMinutes
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
    fun transition(
        from: AdaptiveV2Mode,
        impedanceDisturbance: Float,
        vendorArtifactHint: Float,
        out: DoubleArray,
    ) {
        val base = TRANSITION[from.ordinal]
        base.copyInto(out)
        val boost = (impedanceDisturbance * IMPEDANCE_ARTIFACT_BOOST +
            vendorArtifactHint * VENDOR_ARTIFACT_BOOST).coerceIn(0.0f, MAX_ARTIFACT_BOOST).toDouble()
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

    private const val IMPEDANCE_ARTIFACT_BOOST = 0.22f
    private const val VENDOR_ARTIFACT_BOOST = 0.30f
    private const val MAX_ARTIFACT_BOOST = 0.40f
    private const val MAX_TRANSFER_FRACTION = 0.85
}
