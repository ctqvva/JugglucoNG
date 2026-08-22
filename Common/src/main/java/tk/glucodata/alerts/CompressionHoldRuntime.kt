package tk.glucodata.alerts

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Applic
import tk.glucodata.CompressionHoldNotifier
import tk.glucodata.JournalIobAccess
import tk.glucodata.Log
import tk.glucodata.NotificationHistorySource
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.logic.CompressionLowDetector

/**
 * The Android-facing owner of the compression-low hold (EXPERIMENTAL, opt-in, default
 * off): assembles the live suspicion (recent trace, IOB, ISF, dose peak), drives the
 * pure [CompressionHoldState], persists the [CompressionHoldLog], and speaks to the
 * user — the turn-over cue at hold start, the plain receipt afterwards, the notice when
 * the mode disables itself.
 *
 * Opting in is an informed trade the settings text spells out: the detector cannot tell
 * a pressure artifact from a journal omission (a forgotten bolus fakes the fall,
 * unlogged rescue carbs fake the rebound) — whoever enables this vouches for their
 * journal. Every rail is user-overridable by deliberate product decision, floor
 * included; the defaults are the safe configuration and the UI carries the warnings.
 *
 * Called under the alert runtime lock on its scheduler thread. The expensive suspicion
 * assembly (Room history, reflection into the journal) runs only on the tick that would
 * fire a LOW alarm and only while no hold is running — every other tick touches nothing
 * but prefs and arithmetic.
 */
internal object CompressionHoldRuntime {
    private const val LOG_ID = "CompressionHold"
    private const val PREFS_NAME = "tk.glucodata.compression_hold"

    const val PREF_ENABLED = "compression_hold_enabled"
    const val PREF_MAX_HOLD_MINUTES = "compression_hold_max_minutes"
    const val PREF_FLOOR_MODE = "compression_hold_floor_mode"
    const val PREF_FLOOR_CUSTOM_MGDL = "compression_hold_floor_custom_mgdl"
    const val PREF_SELF_DISABLE_LIMIT = "compression_hold_self_disable_limit"
    private const val PREF_SELF_DISABLED = "compression_hold_self_disabled"
    private const val PREF_LOG = "compression_hold_log"
    private const val PREF_TUNING_PREFIX = "compression_hold_tuning_"

    const val FLOOR_MODE_VERY_LOW = "verylow"
    const val FLOOR_MODE_CUSTOM = "custom"
    const val FLOOR_MODE_OFF = "off"

    const val DEFAULT_MAX_HOLD_MINUTES = 10
    /** Beyond this the settings UI warns; it does not stop anyone. */
    const val RECOMMENDED_MAX_HOLD_MINUTES = 15
    const val DEFAULT_SELF_DISABLE_LIMIT = 2
    const val DEFAULT_FLOOR_MGDL = 55f

    private const val MINUTE_MS = 60_000L
    private const val HISTORY_LOOKBACK_MS = 60 * MINUTE_MS
    private const val MGDL_PER_MMOL = 18.0182f

    private val holdState = CompressionHoldState()
    private var lastSuspect: CompressionLowDetector.OngoingSuspect? = null

    // --- settings surface (read by the alert settings screen) ---

    fun isOptedIn(): Boolean = prefs()?.getBoolean(PREF_ENABLED, false) == true

    fun isSelfDisabled(): Boolean = prefs()?.getBoolean(PREF_SELF_DISABLED, false) == true

    /** Re-enabling clears the self-disable latch and starts the escalation count fresh. */
    fun setEnabled(enabled: Boolean) {
        val p = prefs() ?: return
        p.edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) {
            p.edit().remove(PREF_SELF_DISABLED).remove(PREF_LOG).apply()
        } else {
            release(holdState.onDisabled())
        }
    }

    fun maxHoldMinutes(): Int =
        prefs()?.getInt(PREF_MAX_HOLD_MINUTES, DEFAULT_MAX_HOLD_MINUTES)
            ?.coerceIn(1, 60) ?: DEFAULT_MAX_HOLD_MINUTES

    fun setMaxHoldMinutes(minutes: Int) {
        prefs()?.edit()?.putInt(PREF_MAX_HOLD_MINUTES, minutes.coerceIn(1, 60))?.apply()
    }

    fun selfDisableLimit(): Int =
        prefs()?.getInt(PREF_SELF_DISABLE_LIMIT, DEFAULT_SELF_DISABLE_LIMIT)
            ?.coerceIn(0, 20) ?: DEFAULT_SELF_DISABLE_LIMIT

    fun setSelfDisableLimit(limit: Int) {
        prefs()?.edit()?.putInt(PREF_SELF_DISABLE_LIMIT, limit.coerceIn(0, 20))?.apply()
    }

    fun floorMode(): String =
        prefs()?.getString(PREF_FLOOR_MODE, FLOOR_MODE_VERY_LOW) ?: FLOOR_MODE_VERY_LOW

    fun setFloorMode(mode: String) {
        prefs()?.edit()?.putString(PREF_FLOOR_MODE, mode)?.apply()
    }

    fun floorCustomMgdl(): Float =
        prefs()?.getFloat(PREF_FLOOR_CUSTOM_MGDL, DEFAULT_FLOOR_MGDL) ?: DEFAULT_FLOOR_MGDL

    fun setFloorCustomMgdl(mgdl: Float) {
        prefs()?.edit()?.putFloat(PREF_FLOOR_CUSTOM_MGDL, mgdl.coerceIn(30f, 100f))?.apply()
    }

    fun loadTuning(): CompressionLowDetector.Tuning {
        val p = prefs() ?: return CompressionLowDetector.Tuning.DEFAULT
        val d = CompressionLowDetector.Tuning.DEFAULT
        fun f(key: String, def: Float) = p.getFloat(PREF_TUNING_PREFIX + key, def)
        fun l(key: String, def: Long) = p.getLong(PREF_TUNING_PREFIX + key, def)
        return CompressionLowDetector.Tuning(
            suspectDropMgdlPerMinute = f("suspect_rate", d.suspectDropMgdlPerMinute),
            minDropDepthMgdl = f("min_depth", d.minDropDepthMgdl),
            flatWindowMinutes = l("flat_window", d.flatWindowMinutes),
            minFlatSpanMinutes = l("flat_span", d.minFlatSpanMinutes),
            flatMinRateMgdlPerMinute = f("flat_min_rate", d.flatMinRateMgdlPerMinute),
            flatDipToleranceMgdl = f("flat_dip", d.flatDipToleranceMgdl),
            recoveryWindowMinutes = l("recovery_window", d.recoveryWindowMinutes),
            recoveryBandMgdl = f("recovery_band", d.recoveryBandMgdl),
            unexplainedFactor = f("unexplained_factor", d.unexplainedFactor),
            negligibleCarbGrams = f("carb_grams", d.negligibleCarbGrams),
            carbLookbackMinutes = l("carb_lookback", d.carbLookbackMinutes),
            maxGapMinutes = l("max_gap", d.maxGapMinutes)
        ).sanitized()
    }

    fun saveTuning(t: CompressionLowDetector.Tuning) {
        val p = prefs() ?: return
        p.edit()
            .putFloat(PREF_TUNING_PREFIX + "suspect_rate", t.suspectDropMgdlPerMinute)
            .putFloat(PREF_TUNING_PREFIX + "min_depth", t.minDropDepthMgdl)
            .putLong(PREF_TUNING_PREFIX + "flat_window", t.flatWindowMinutes)
            .putLong(PREF_TUNING_PREFIX + "flat_span", t.minFlatSpanMinutes)
            .putFloat(PREF_TUNING_PREFIX + "flat_min_rate", t.flatMinRateMgdlPerMinute)
            .putFloat(PREF_TUNING_PREFIX + "flat_dip", t.flatDipToleranceMgdl)
            .putLong(PREF_TUNING_PREFIX + "recovery_window", t.recoveryWindowMinutes)
            .putFloat(PREF_TUNING_PREFIX + "recovery_band", t.recoveryBandMgdl)
            .putFloat(PREF_TUNING_PREFIX + "unexplained_factor", t.unexplainedFactor)
            .putFloat(PREF_TUNING_PREFIX + "carb_grams", t.negligibleCarbGrams)
            .putLong(PREF_TUNING_PREFIX + "carb_lookback", t.carbLookbackMinutes)
            .putLong(PREF_TUNING_PREFIX + "max_gap", t.maxGapMinutes)
            .apply()
    }

    fun loadLog(): CompressionHoldLog = CompressionHoldLog.decode(prefs()?.getString(PREF_LOG, null))

    // --- runtime surface (called by AlertRuntimeManager under its lock) ---

    /**
     * Gate for an undelivered, unsnoozed LOW alarm. True means "withhold this tick".
     * The turn-over cue and every log line happen in here; the caller only keeps the
     * episode pending so the alarm fires the instant the hold lifts.
     */
    fun gateLow(displayValue: Float, rate: Float): Boolean {
        try {
            val nowMs = System.currentTimeMillis()
            if (!isOptedIn() || isSelfDisabled()) {
                release(holdState.onDisabled())
                return false
            }
            val turnOverConfig = AlertRepository.loadConfig(AlertType.SENSOR_PRESSURE)
            if (!turnOverConfig.enabled) {
                // A hold without its cue would be silence, and silence is forbidden.
                release(holdState.onDisabled())
                return false
            }
            val valueMgdl = toMgdl(displayValue)
            val suspicion = if (!holdState.holding) assessSuspicion(nowMs, valueMgdl) else null
            val action = holdState.onLowActive(
                nowMs = nowMs,
                valueMgdl = valueMgdl,
                hardFloorMgdl = resolveFloorMgdl(),
                maxHoldMs = maxHoldMinutes() * MINUTE_MS,
                suspicionHeld = suspicion != null
            )
            return when (action) {
                is CompressionHoldState.Action.StartHold -> {
                    lastSuspect = suspicion
                    fireTurnOverCue(turnOverConfig, displayValue, rate, suspicion)
                    Log.i(LOG_ID, "Holding LOW: $suspicion")
                    true
                }
                is CompressionHoldState.Action.ContinueHold -> true
                is CompressionHoldState.Action.Escalate -> {
                    recordOutcome(nowMs, CompressionHoldLog.Outcome.ESCALATED, action.reason)
                    false
                }
                else -> false
            }
        } catch (t: Throwable) {
            // Any failure in the hold machinery must never cost the alarm.
            Log.stack(LOG_ID, "gateLow", t)
            release(holdState.onDisabled())
            return false
        }
    }

    /** The LOW condition cleared: a running hold resolved itself. */
    fun onLowCleared() {
        try {
            release(holdState.onLowCleared(System.currentTimeMillis()))
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "onLowCleared", t)
        }
    }

    /** VERY_LOW is firing: the hard floor spoke through its own alert type. */
    fun onVeryLowTakingOver() {
        try {
            release(holdState.forceEscalate("hard-floor"))
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "onVeryLowTakingOver", t)
        }
    }

    // --- internals ---

    private fun release(action: CompressionHoldState.Action) {
        val nowMs = System.currentTimeMillis()
        when (action) {
            is CompressionHoldState.Action.Resolved -> {
                recordOutcome(nowMs, CompressionHoldLog.Outcome.RESOLVED, "resolved",
                    heldMs = action.heldMillis)
            }
            is CompressionHoldState.Action.Escalate -> {
                recordOutcome(nowMs, CompressionHoldLog.Outcome.ESCALATED, action.reason)
            }
            else -> {}
        }
    }

    private fun assessSuspicion(nowMs: Long, valueMgdl: Float): CompressionLowDetector.OngoingSuspect? {
        val history = NotificationHistorySource.getDisplayHistory(nowMs - HISTORY_LOOKBACK_MS, false)
        if (history.isEmpty()) return null
        val samples = history.map { CompressionLowDetector.Sample(it.timestamp, it.value) }
        val iob = JournalIobAccess.snapshot(nowMs)?.getOrNull(0) ?: Float.NaN
        val appPrefs = appPrefs() ?: return null
        val isf = PredictionModelProfileStore.parametersAt(appPrefs, nowMs).insulinSensitivityMgDlPerUnit
        val peakPassed = JournalIobAccess.lastDosePeakPassed(nowMs) == 1
        val suspect = CompressionLowDetector.assessOngoing(
            samples = samples,
            nowMillis = nowMs,
            isfMgdlPerUnit = isf,
            iobUnits = iob,
            dosePeakPassed = peakPassed,
            tuning = loadTuning()
        )
        if (suspect == null) {
            Log.i(LOG_ID, "No suspicion at ${"%.0f".format(valueMgdl)} mg/dL " +
                "(iob=$iob, isf=$isf, peakPassed=$peakPassed, samples=${samples.size})")
        }
        return suspect
    }

    private fun fireTurnOverCue(
        config: AlertConfig,
        displayValue: Float,
        rate: Float,
        suspect: CompressionLowDetector.OngoingSuspect?
    ) {
        val context = Applic.app ?: return
        val base = context.getString(R.string.sensor_pressure_cue_message)
        val detail = suspect?.let {
            " (%.1f mg/dL/min, IOB %.1f U)".format(it.meanDropMgdlPerMinute, iobForDisplay())
        } ?: ""
        Notify.triggerSupplementalGlucoseAlert(
            AlertType.SENSOR_PRESSURE.id, displayValue, rate, base + detail
        )
    }

    private fun iobForDisplay(): Float =
        JournalIobAccess.snapshot(System.currentTimeMillis())?.getOrNull(0) ?: Float.NaN

    private fun recordOutcome(nowMs: Long, outcome: CompressionHoldLog.Outcome, reason: String, heldMs: Long = 0L) {
        Notify.cancelRetrySession(AlertType.SENSOR_PRESSURE.id, "sensor-pressure-hold-$reason")
        val p = prefs() ?: return
        val startMs = nowMs - heldMs
        val log = CompressionHoldLog.decode(p.getString(PREF_LOG, null))
            .pruned(nowMs)
            .record(CompressionHoldLog.Entry(startMs, nowMs, outcome, reason))
        p.edit().putString(PREF_LOG, log.encode()).apply()
        lastSuspect = null

        val context = Applic.app ?: return
        val heldMinutes = (heldMs / MINUTE_MS).toInt().coerceAtLeast(0)
        val summary = context.getString(
            R.string.sensor_pressure_hold_summary_text,
            log.entries.size, log.resolvedCount(), log.escalatedCount()
        )
        when (outcome) {
            CompressionHoldLog.Outcome.RESOLVED ->
                CompressionHoldNotifier.notifyResolved(context, heldMinutes, summary)
            CompressionHoldLog.Outcome.ESCALATED -> {
                CompressionHoldNotifier.notifyEscalated(context, heldMinutes, summary)
                val limit = selfDisableLimit()
                if (log.selfDisableDue(limit)) {
                    p.edit().putBoolean(PREF_SELF_DISABLED, true).apply()
                    CompressionHoldNotifier.notifySelfDisabled(context, log.escalatedCount())
                    Log.i(LOG_ID, "Self-disabled after ${log.escalatedCount()} escalations")
                }
            }
        }
    }

    private fun resolveFloorMgdl(): Float {
        val p = prefs() ?: return DEFAULT_FLOOR_MGDL
        return when (p.getString(PREF_FLOOR_MODE, FLOOR_MODE_VERY_LOW)) {
            FLOOR_MODE_OFF -> Float.NaN
            FLOOR_MODE_CUSTOM -> p.getFloat(PREF_FLOOR_CUSTOM_MGDL, DEFAULT_FLOOR_MGDL)
            else -> {
                val threshold = runCatching {
                    AlertRepository.loadConfig(AlertType.VERY_LOW).threshold
                }.getOrNull()
                threshold?.let { toMgdl(it) } ?: DEFAULT_FLOOR_MGDL
            }
        }
    }

    private fun toMgdl(displayValue: Float): Float =
        if (Applic.unit == 1) displayValue * MGDL_PER_MMOL else displayValue

    private fun prefs(): SharedPreferences? =
        Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appPrefs(): SharedPreferences? =
        Applic.app?.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
}
