package tk.glucodata.drivers.sibionics.adaptive2

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.max

/**
 * Adaptive V2 latent state layout.
 *
 * The separation is the point: glucose dynamics ([B], [I], [V], [ACC]), slow
 * sensor behaviour ([LOG_S], [BIAS]) and transient sensor artifact ([ARTIFACT])
 * are three different things and must not be collapsed into one generic
 * process-noise term. If they were, every sensor event would be explained as
 * glucose movement — which is precisely the failure this model exists to avoid.
 *
 * The blood↔interstitial lag τ is deliberately *not* a state. With a single
 * scalar observation per minute, τ, sensitivity and offset can all partially
 * mimic one another and a long glucose shift; giving τ its own free state makes
 * it the dumping ground for every modelling error. It is instead a bounded,
 * strongly-prior-constrained parameter adapted slowly by
 * [AdaptiveV2LagEstimator], which keeps the transition matrix linear and the
 * whole filter exact and deterministic.
 */
internal object V2 {
    /** Blood-equivalent glucose, mmol/L. */
    const val B = 0

    /** Interstitial glucose, mmol/L — what the sensor actually sees. */
    const val I = 1

    /** Glucose rate dB/dt, mmol/L/min. */
    const val V = 2

    /** Glucose acceleration d²B/dt², mmol/L/min². */
    const val ACC = 3

    /** log sensor sensitivity, 0 = factory value. Log-parameterised so it stays positive. */
    const val LOG_S = 4

    /** Slow sensor offset, mmol/L. */
    const val BIAS = 5

    /** Transient sensor artifact, mmol/L. Decays; never absorbed into glucose. */
    const val ARTIFACT = 6

    const val N = 7
}

/**
 * A mode-conditioned Gaussian: mean vector and covariance for one IMM mode.
 *
 * Covariance math runs in double precision. The cost is irrelevant at one
 * update per minute and it keeps a 7×7 covariance conditioned across days of
 * continuous operation, where float accumulation visibly degrades.
 */
internal class AdaptiveV2Gaussian {
    val x = DoubleArray(V2.N)
    val p = DoubleArray(V2.N * V2.N)

    private val scratchA = DoubleArray(V2.N * V2.N)
    private val scratchB = DoubleArray(V2.N * V2.N)
    private val gain = DoubleArray(V2.N)
    private val jacobian = DoubleArray(V2.N)

    fun at(row: Int, column: Int): Double = p[row * V2.N + column]

    fun setAt(row: Int, column: Int, value: Double) {
        p[row * V2.N + column] = value
    }

    fun copyFrom(other: AdaptiveV2Gaussian) {
        other.x.copyInto(x)
        other.p.copyInto(p)
    }

    fun reset() {
        x.fill(0.0)
        p.fill(0.0)
    }

    fun isFinite(): Boolean {
        for (value in x) if (!value.isFinite()) return false
        for (value in p) if (!value.isFinite()) return false
        return true
    }

    /**
     * x ← F x ; P ← F P Fᵀ + Q.
     *
     * @param q diagonal process noise for the independent states.
     * @param glucoseBlock coupled 3x3 covariance for B/v/a in row-major order,
     *   which replaces their diagonal entries — see
     *   [AdaptiveV2ModeModel.glucoseBlock].
     */
    fun predict(
        f: DoubleArray,
        q: DoubleArray,
        glucoseBlock: DoubleArray,
        control: DoubleArray,
    ) {
        // x ← F x + u. The control term carries the shrinkage targets for the
        // slow sensor states: relaxing toward a non-zero prior is affine, not
        // linear, so it cannot live in F.
        val newX = DoubleArray(V2.N)
        for (row in 0 until V2.N) {
            var sum = control[row]
            for (column in 0 until V2.N) sum += f[row * V2.N + column] * x[column]
            newX[row] = sum
        }
        newX.copyInto(x)

        // scratchA ← F P
        multiply(f, p, scratchA)
        // P ← scratchA Fᵀ
        multiplyTransposed(scratchA, f, p)
        for (i in 0 until V2.N) {
            if (i != V2.B && i != V2.V && i != V2.ACC) p[i * V2.N + i] += q[i]
        }
        val block = intArrayOf(V2.B, V2.V, V2.ACC)
        for (row in block.indices) {
            for (column in block.indices) {
                p[block[row] * V2.N + block[column]] += glucoseBlock[row * 3 + column]
            }
        }
        symmetrize()
    }

    /**
     * Scalar EKF update in Joseph form.
     *
     * @param h measurement Jacobian row, length [V2.N].
     * @param innovation z − h(x).
     * @param r measurement variance, already inflated by the robust weight.
     * @return the innovation variance S = H P Hᵀ + R.
     */
    fun update(h: DoubleArray, innovation: Double, r: Double): Double {
        h.copyInto(jacobian)
        // ph ← P Hᵀ
        val ph = DoubleArray(V2.N)
        for (row in 0 until V2.N) {
            var sum = 0.0
            for (column in 0 until V2.N) sum += p[row * V2.N + column] * jacobian[column]
            ph[row] = sum
        }
        var s = r
        for (i in 0 until V2.N) s += jacobian[i] * ph[i]
        s = max(s, MIN_INNOVATION_VARIANCE)
        for (i in 0 until V2.N) gain[i] = ph[i] / s
        for (i in 0 until V2.N) x[i] += gain[i] * innovation

        // scratchA ← I − K H
        for (row in 0 until V2.N) {
            for (column in 0 until V2.N) {
                val identity = if (row == column) 1.0 else 0.0
                scratchA[row * V2.N + column] = identity - gain[row] * jacobian[column]
            }
        }
        // P ← (I−KH) P (I−KH)ᵀ + K R Kᵀ
        multiply(scratchA, p, scratchB)
        multiplyTransposed(scratchB, scratchA, p)
        for (row in 0 until V2.N) {
            for (column in 0 until V2.N) {
                p[row * V2.N + column] += gain[row] * r * gain[column]
            }
        }
        symmetrize()
        return s
    }

    /** Guards against slow asymmetry and negative diagonals from round-off. */
    fun symmetrize() {
        for (row in 0 until V2.N) {
            for (column in row + 1 until V2.N) {
                val average = 0.5 * (p[row * V2.N + column] + p[column * V2.N + row])
                p[row * V2.N + column] = average
                p[column * V2.N + row] = average
            }
            val diagonal = p[row * V2.N + row]
            if (!diagonal.isFinite() || diagonal < MIN_DIAGONAL) p[row * V2.N + row] = MIN_DIAGONAL
        }
    }

    fun writeTo(output: DataOutputStream) {
        for (value in x) output.writeDouble(value)
        for (value in p) output.writeDouble(value)
    }

    fun readFrom(input: DataInputStream) {
        for (i in x.indices) x[i] = input.readDouble()
        for (i in p.indices) p[i] = input.readDouble()
    }

    private fun multiply(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        for (row in 0 until V2.N) {
            for (column in 0 until V2.N) {
                var sum = 0.0
                for (k in 0 until V2.N) sum += a[row * V2.N + k] * b[k * V2.N + column]
                out[row * V2.N + column] = sum
            }
        }
    }

    /** out ← a · bᵀ */
    private fun multiplyTransposed(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        for (row in 0 until V2.N) {
            for (column in 0 until V2.N) {
                var sum = 0.0
                for (k in 0 until V2.N) sum += a[row * V2.N + k] * b[column * V2.N + k]
                out[row * V2.N + column] = sum
            }
        }
    }

    companion object {
        private const val MIN_INNOVATION_VARIANCE = 1e-9
        private const val MIN_DIAGONAL = 1e-10
    }
}

/**
 * Builds the linear transition matrix for a step of [dtMinutes].
 *
 * Blood glucose follows a constant-acceleration model with a mean-reverting
 * acceleration; interstitial glucose follows the exact discretisation of
 * dI/dt = (B − I)/τ over the step, evaluated against the mid-interval blood
 * value so a fast ramp is not systematically under-tracked. Sensitivity, offset
 * and artifact each relax toward their prior at their own time constant.
 */
internal object AdaptiveV2Transition {
    fun build(out: DoubleArray, dtMinutes: Double, lagMinutes: Double) {
        out.fill(0.0)
        fun set(row: Int, column: Int, value: Double) {
            out[row * V2.N + column] = value
        }

        val accelerationDecay = exp(-dtMinutes / ACCELERATION_TIME_CONSTANT_MIN)
        set(V2.B, V2.B, 1.0)
        set(V2.B, V2.V, dtMinutes)
        set(V2.B, V2.ACC, 0.5 * dtMinutes * dtMinutes)
        set(V2.V, V2.V, 1.0)
        set(V2.V, V2.ACC, dtMinutes)
        set(V2.ACC, V2.ACC, accelerationDecay)

        val alpha = 1.0 - exp(-dtMinutes / max(lagMinutes, MIN_LAG_MINUTES))
        set(V2.I, V2.I, 1.0 - alpha)
        set(V2.I, V2.B, alpha)
        set(V2.I, V2.V, alpha * dtMinutes * 0.5)

        set(V2.LOG_S, V2.LOG_S, exp(-dtMinutes / SENSITIVITY_TIME_CONSTANT_MIN))
        set(V2.BIAS, V2.BIAS, exp(-dtMinutes / BIAS_TIME_CONSTANT_MIN))
        set(V2.ARTIFACT, V2.ARTIFACT, exp(-dtMinutes / ARTIFACT_TIME_CONSTANT_MIN))
    }

    /** ~30 min: acceleration is a transient, not a standing property of glucose. */
    const val ACCELERATION_TIME_CONSTANT_MIN = 30.0

    /**
     * Sensitivity and offset shrink toward the factory prior over roughly two
     * days. Without this shrinkage the pair drifts freely and slowly steals the
     * glucose level — the classic single-observation identifiability failure.
     */
    const val SENSITIVITY_TIME_CONSTANT_MIN = 2.0 * 24.0 * 60.0
    const val BIAS_TIME_CONSTANT_MIN = 1.5 * 24.0 * 60.0

    /** Compression and contact artifacts resolve over minutes, not hours. */
    const val ARTIFACT_TIME_CONSTANT_MIN = 12.0

    const val MIN_LAG_MINUTES = 1.5
}
