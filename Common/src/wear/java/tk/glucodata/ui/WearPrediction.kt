package tk.glucodata.ui

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.WearJournalSync
import tk.glucodata.data.prediction.GlucosePredictionKernel
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.GlucoseTreatmentCurves

/**
 * The watch's predictive simulation.
 *
 * The forward model is the phone's own ([GlucosePredictionKernel]); only the
 * treatment term is built here, from the journal the watch has synced rather
 * than the phone's Room-backed one. Carbs use the shared absorption curve and
 * insulin the preset's own activity curve, which the journal payload carries
 * from v2 — before that the watch had no way to know a dose had been given, and
 * a forecast that ignores insulin on board runs high exactly when it matters.
 *
 * Settings come from the mirrored phone preferences, so the horizon and the
 * ratios are whatever the user set there.
 */
object WearPrediction {
    private const val PREFS = "tk.glucodata_preferences"

    private const val KEY_ENABLED = "dashboard_predictive_simulation_enabled"
    private const val KEY_MOMENTUM = "dashboard_prediction_trend_momentum_enabled"
    private const val KEY_CARB_RATIO = "dashboard_prediction_carb_ratio_g_per_u"
    private const val KEY_SENSITIVITY = "dashboard_prediction_insulin_sensitivity_mgdl_per_u"
    private const val KEY_CARB_ABSORPTION = "dashboard_prediction_carb_absorption_g_per_h"
    private const val KEY_HORIZON = "dashboard_prediction_horizon_minutes"

    private const val CARB_RATIO_DEFAULT = 10f
    private const val SENSITIVITY_DEFAULT = 54f
    private const val CARB_ABSORPTION_DEFAULT = 35f
    private const val HORIZON_DEFAULT = 120
    private const val ENABLED_DEFAULT = true
    private const val STEP_MINUTES = 5

    /** Treatments older than this cannot still be acting. */
    private const val TREATMENT_LOOKBACK_MS = 12L * 60L * 60L * 1000L

    private fun prefs(): android.content.SharedPreferences? =
        Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Defaults track the phone's readers: predictive simulation and momentum
    // are both on there unless the user turned them off.
    fun isEnabled(): Boolean = prefs()?.getBoolean(KEY_ENABLED, ENABLED_DEFAULT) ?: false

    /**
     * The forecast from the end of [history], or empty when the simulation is
     * switched off or there is too little to project from.
     */
    fun forecast(
        history: List<GlucosePoint>,
        isMmol: Boolean,
        /** Project the raw lane instead of the auto one. */
        useRaw: Boolean = false,
    ): List<GlucosePredictionPoint> {
        val preferences = prefs() ?: return emptyList()
        if (!preferences.getBoolean(KEY_ENABLED, ENABLED_DEFAULT)) return emptyList()
        if (history.size < 2) return emptyList()

        val carbRatio = preferences.getFloat(KEY_CARB_RATIO, CARB_RATIO_DEFAULT).coerceAtLeast(1f)
        val sensitivityMgdl = preferences.getFloat(KEY_SENSITIVITY, SENSITIVITY_DEFAULT)
        val absorption = preferences.getFloat(KEY_CARB_ABSORPTION, CARB_ABSORPTION_DEFAULT)
        val horizon = preferences.getInt(KEY_HORIZON, HORIZON_DEFAULT)
        val momentum = preferences.getBoolean(KEY_MOMENTUM, true)
        // The kernel works in display units throughout, so the sensitivity has
        // to be converted once here rather than per treatment.
        val sensitivityDisplay = if (isMmol) sensitivityMgdl / 18.0182f else sensitivityMgdl

        // Each lane is projected from its own readings, as the phone projects
        // every series it draws rather than only the primary one.
        val lane = history.mapNotNull { point ->
            val value = if (useRaw) point.rawValue else point.value
            if (value.isFinite() && value > 0.1f) GlucosePredictionKernel.Sample(point.timestamp, value) else null
        }
        if (lane.size < 2) return emptyList()
        val baselineTime = lane.last().timestamp
        val journal = runCatching { WearJournalSync.cached() }.getOrNull()
        val treatments = journal
            ?.takeIf { it.enabled }
            ?.entries
            ?.filter { it.timestampMs in (baselineTime - TREATMENT_LOOKBACK_MS)..(baselineTime + horizon * 60_000L) }
            .orEmpty()
        val presetsById = journal?.presets.orEmpty().associateBy { it.id }

        fun journalDeltaAt(timestamp: Long): Float {
            var delta = 0f
            treatments.forEach { entry ->
                val amount = entry.amount
                if (!amount.isFinite() || amount <= 0f) return@forEach
                when (entry.type) {
                    WearJournalSync.TYPE_CARBS -> {
                        delta += GlucoseTreatmentCurves.carbRise(
                            grams = amount,
                            startMillis = entry.timestampMs,
                            baselineMillis = baselineTime,
                            atMillis = timestamp,
                            absorptionMinutes = GlucoseTreatmentCurves.carbAbsorptionMinutes(amount, absorption),
                            carbRatioGramsPerUnit = carbRatio,
                            sensitivityDisplay = sensitivityDisplay,
                        )
                    }
                    WearJournalSync.TYPE_INSULIN -> {
                        // No curve means the phone did not send one — an older
                        // payload, or a preset that does not count toward IOB.
                        // Modelling a made-up curve would be worse than none.
                        val preset = presetsById[entry.presetId]
                        val curveMinutes = if (entry.curveMinutes.size >= 2) {
                            entry.curveMinutes
                        } else {
                            preset?.curveMinutes ?: return@forEach
                        }
                        val curveActivity = if (entry.curveActivity.size == curveMinutes.size) {
                            entry.curveActivity
                        } else {
                            preset?.curveActivity ?: return@forEach
                        }
                        if (curveMinutes.size < 2 || curveActivity.size != curveMinutes.size) return@forEach
                        val future = GlucoseTreatmentCurves.cumulativeCurveFraction(
                            curveMinutes, curveActivity, entry.timestampMs, timestamp
                        )
                        val atBaseline = GlucoseTreatmentCurves.cumulativeCurveFraction(
                            curveMinutes, curveActivity, entry.timestampMs, baselineTime
                        )
                        delta -= amount * sensitivityDisplay * (future - atBaseline)
                    }
                }
            }
            return delta
        }

        val thresholdLow = runCatching { Natives.targetlow() }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0f } ?: GlucoseRangeColors.defaultLow(isMmol)
        val thresholdHigh = runCatching { Natives.targethigh() }.getOrNull()
            ?.takeIf { it.isFinite() && it > thresholdLow } ?: GlucoseRangeColors.defaultHigh(isMmol)

        return GlucosePredictionKernel.simulate(
            history = lane,
            isMmol = isMmol,
            trendMomentumEnabled = momentum,
            horizonMinutes = horizon,
            stepMinutes = STEP_MINUTES,
            targetLow = thresholdLow,
            targetHigh = thresholdHigh,
            journalDeltaAt = ::journalDeltaAt,
        )
    }
}
