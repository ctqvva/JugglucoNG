// AnytimeCalibrator.kt — Pure-Kotlin glucose model for Anytime / Yuwell CT3/CT4.
//
// Reference: docs/MK4_FINAL_SUMMARY.md (reverse-engineered native chain
// `algorithmMain -> yqidui_PX4 -> wntrulThgs_NG4`). Reference App (apex-re-refapp)
// implements the same model in pure Dart, so this is the portable equivalent
// for JugglucoNG where `libalgorithm-jni.so` is deliberately not packaged.
// See also docs/anytime-reference-extract.md for the Reference App mechanisms this
// file does not yet cover (calibrator state persistence, trend gates, etc.).
//
// Chain (per glucose id, in order):
//   1. base       = Iw / K_BASE          , K_BASE = 1.2 * K0           (QR K)
//   2. temp       : stateful smoothing of the raw sensor temperature:
//                     T_eff = T,                          if T <= 30
//                     T_eff = 0.25*T + 0.75*T_prev        otherwise
//                     T_prev <- T_eff
//      MK4_FINAL_SUMMARY.md §6 is ambiguous on whether `T_prev` updates in the
//      `T <= 30` branch too (see docs/ct-driver-plan.md open question #2 —
//      unresolved without a real cold-temperature log). This file updates it
//      in both branches (`T_prev <- T`, effectively) — unverified, not changed
//      without new data since it affects real users' displayed glucose.
//      temp_mul   = 1 + (T_eff - 32) * (-0.04593)
//   3. Glu_raw    = base * temp_mul
//   4. step limiter (wntrulThgs_NG4) against previous filtered value g_prev and
//      the per-id auto-sensitivity K_AUTO:
//                     K_AUTO = K_BASE * (0.9 + glucoseId / warmupRecords)
//      (pinned to K_BASE once glucoseId >= warmupRecords)
//   5. output Glu_AI == g_t  (filtered glucose, mmol/L)
//
// This is the native MK4/CT4 shape. Family-specific warmup constant lives in
// FamilyEntry; CT4 uses 480 (per the RE notes), CT3 stays 480 until a CT3 log
// says otherwise (the chain is shared, only the warmup horizon differs).

package tk.glucodata.drivers.anytime

import tk.glucodata.Log

/**
 * Stateful, per-session glucose reconstructor.
 *
 * Deterministic replay: feed records in ascending glucoseId order via
 * [computeNext]. Hold one instance per sensor session (not per record) so the
 * temperature smoother and the step limiter carry their state across samples.
 */
class AnytimeCalibrator(
    val k0: Float,
) {
    /** The three fields that carry continuity across records; see [snapshot]/[restoreState]. */
    data class State(
        val tempSmoothPrev: Float,
        val filteredPrev: Float,
        val lastGlucoseId: Int,
    )

    companion object {
        private const val TAG = AnytimeConstants.TAG

        /** K_BASE / K0. Native confirms K_BASE = 1.2 * K0 (MK4_FINAL_SUMMARY.md §2). */
        const val K_BASE_FACTOR = 1.2f

        /**
         * Warm-up "settling" floor: K_AUTO / K_BASE at glucoseId == 0
         * (MK4_FINAL_SUMMARY.md §3: "GlucoseID = 0 -> K_AUTO / K_BASE = 0.9").
         */
        const val AUTO_FLOOR = 0.9f

        /**
         * Denom of the pack-in ramp. K_AUTO = K_BASE * (0.9 + glucoseId/4800),
         * reaching K_BASE at glucoseId == 480. The 4800 is a literal scale, not
         * the warmup record count — RE-confirmed (MK4_FINAL_SUMMARY.md §3).
         */
        const val AUTO_RAMP_DENOM = 4800f

        /** Pack-in completion id (K_AUTO reaches K_BASE here; MK4_FINAL_SUMMARY.md §3). */
        const val WARMUP_RECORDS = 480

        /**
         * Temp multiplier slope (per °C) below the 32 °C reference
         * (MK4_FINAL_SUMMARY.md §1: `temp_mul ~= 1 + (T - 32) * (-0.0459)`; the
         * precise `-0.04593` value comes from the §8 worked example, packet 430).
         */
        const val TEMP_MUL_SLOPE = -0.04593f

        /** Reference temperature the multiplier is centred on (MK4_FINAL_SUMMARY.md §1). */
        const val TEMP_REFERENCE_C = 32f

        /** Raw-temperature knee: below this the smoother is bypassed (MK4_FINAL_SUMMARY.md §6). */
        const val TEMP_SMOOTH_KNEE_C = 30f

        /** IIR coefficient for the sensor temperature smoother (MK4_FINAL_SUMMARY.md §6). */
        const val TEMP_SMOOTH_ALPHA = 0.25f

        /**
         * Step-limiter reset / saturation guard (wntrulThgs_NG4, MK4_FINAL_SUMMARY.md §7:
         * start branch seeds `min(x_t, 60)`, reset branch fires on `x_t > 60`).
         */
        const val FILTER_SATURATION_UPPER = 60f

        /** Step-limiter floor guard (MK4_FINAL_SUMMARY.md §7 step 3: `cand < 1 && x_t <= 0`). */
        const val FILTER_FLOOR_LOWER = 1f

        /** Step-limiter high-hold guard (MK4_FINAL_SUMMARY.md §7 step 3: `cand >= 50 && x_t >= 50`). */
        const val FILTER_HIGH_HOLD_THRESHOLD = 50f
    }

    private val kBase = k0 * K_BASE_FACTOR

    private var tempSmoothPrev: Float = Float.NaN
    private var filteredPrev: Float = Float.NaN
    private var lastGlucoseId: Int = -1

    /**
     * Set when the next [computeNext] call must seed a fresh limiter state —
     * a new sensor session, or a restored/never-started one. Named separately
     * from [reset] (the function) to avoid a same-name property/function pair.
     */
    private var pendingReset = true

    /** Per-id auto sensitivity at the pack-in (warm-up) curve. */
    fun autoSensitivity(glucoseId: Int): Float {
        if (glucoseId <= 0) return kBase * AUTO_FLOOR
        val ramp = (AUTO_FLOOR + glucoseId.toFloat() / AUTO_RAMP_DENOM).coerceAtMost(1f)
        return kBase * ramp
    }

    /**
     * Stateful temperature version used to derive `temp_mul`. See the file
     * header for the `T <= 30` branch's unresolved ambiguity around whether
     * `tempSmoothPrev` should update here.
     */
    fun effectiveTemperature(rawT: Float): Float {
        if (rawT <= TEMP_SMOOTH_KNEE_C) {
            tempSmoothPrev = rawT
            return rawT
        }
        val prev = tempSmoothPrev
        val eff = if (prev.isNaN()) {
            rawT
        } else {
            TEMP_SMOOTH_ALPHA * rawT + (1f - TEMP_SMOOTH_ALPHA) * prev
        }
        tempSmoothPrev = eff
        return eff
    }

    /** Temperature multiplier for a raw sensor temperature. */
    fun tempMultiplier(rawT: Float): Float {
        val eff = effectiveTemperature(rawT)
        return 1f + (eff - TEMP_REFERENCE_C) * TEMP_MUL_SLOPE
    }

    /**
     * Advance the model by one raw record and return the filtered glucose
     * (mmol/L) for its id. Records must be supplied in non-decreasing
     * [AnytimeRawRecord.glucoseId] order; out-of-order ids reset the limiter.
     */
    fun computeNext(record: AnytimeRawRecord): Float {
        val id = record.glucoseId
        if (id < lastGlucoseId || pendingReset) {
            pendingReset = false
            filteredPrev = Float.NaN
        }
        lastGlucoseId = id

        val base = record.iwNa / kBase
        val mul = tempMultiplier(record.temperatureC)
        val gluRaw = base * mul
        val kAuto = autoSensitivity(id)

        val filtered = applyStepLimiter(gluRaw, kAuto)
        filteredPrev = filtered
        return filtered
    }

    /**
     * wntrulThgs_NG4 step limiter. See MK4_FINAL_SUMMARY.md §7.
     *
     * §7 step 1 resets on `d_t <= 0 || x_t > 60` (`d_t` = `packet_delta_plus_1`,
     * i.e. a non-advancing or backwards id), returning `x_t` unclamped. This
     * function only checks the `x_t > 60` half — `d_t` itself is never passed
     * in. A backwards id (`id < lastGlucoseId` in [computeNext]) instead clears
     * [filteredPrev] to NaN, which routes here into the *start* branch
     * (`min(x_t, 60)`, §7 step 0) rather than the reset branch (`x_t`
     * unclamped) — a different number for that one sample. A duplicate id
     * (`id == lastGlucoseId`, `d_t` would be `<= 0` too under most readings of
     * the offset) is not caught by either [computeNext]'s check or this
     * function at all; it runs the normal step-limiter path as if the id had
     * advanced. Not changed here: real callers appear to already de-duplicate
     * ids upstream (`AnytimeBleManager`'s `rawAlgorithmWindow`), so this is
     * unverified as a live discrepancy rather than a confirmed bug — see
     * docs/ct-driver-plan.md open question #3.
     */
    private fun applyStepLimiter(x: Float, r: Float): Float {
        val prev = filteredPrev
        // Start: no previous sample yet — seed the filter, no step cut (§7 step 0).
        if (prev.isNaN()) return minOf(x, FILTER_SATURATION_UPPER)

        // Reset: over-range input only — see the `d_t` gap noted above (§7 step 1).
        if (x > FILTER_SATURATION_UPPER) return x

        val delta = x - prev
        val cand = if (delta > r) {
            // Uphill: blend toward the ceiling of one step.
            minOf((x + prev * delta) / (delta + 1f), prev + r)
        } else if (delta < -r) {
            // Downhill: clamp to one step below.
            prev - r
        } else {
            x
        }

        return when {
            cand < FILTER_FLOOR_LOWER && x <= 0f -> x
            cand >= FILTER_HIGH_HOLD_THRESHOLD && x >= FILTER_HIGH_HOLD_THRESHOLD -> prev
            else -> cand
        }
    }

    /** Reset the model state (new session / cleared calibration). */
    fun reset() {
        tempSmoothPrev = Float.NaN
        filteredPrev = Float.NaN
        lastGlucoseId = -1
        pendingReset = true
        Log.d(TAG, "AnytimeCalibrator reset (new session)")
    }

    /** Snapshot the continuity state for persistence (see AnytimeRegistry.saveCalibratorState). */
    fun snapshot(): State = State(tempSmoothPrev, filteredPrev, lastGlucoseId)

    /**
     * Resume a session from a previously [snapshot]ted state, e.g. after an app
     * restart. Unlike [reset], this does not seed a fresh session: the next
     * [computeNext] call continues the limiter/smoother from where it left off.
     */
    fun restoreState(state: State) {
        tempSmoothPrev = state.tempSmoothPrev
        filteredPrev = state.filteredPrev
        lastGlucoseId = state.lastGlucoseId
        pendingReset = false
    }
}
