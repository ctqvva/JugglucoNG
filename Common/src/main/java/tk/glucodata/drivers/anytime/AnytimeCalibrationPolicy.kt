package tk.glucodata.drivers.anytime

object AnytimeCalibrationPolicy {
    /** Reference App/original-compatible rule: manual BG calibration is unavailable in the first 24h. */
    const val MANUAL_CALIBRATION_MIN_AGE_HOURS = 24

    /** Five-step trend classifier; also feeds the dashboard trend arrow for Anytime. */
    enum class Trend { RAPID_RISE, MODERATE_RISE, STABLE, MODERATE_FALL, RAPID_FALL }

    /**
     * Stable/moderate slope boundary, mmol/L per minute — RE-confirmed against
     * Reference App's trend classifier (docs/anytime-reference-extract.md,
     * `pp+0x111f8..0x11248`).
     */
    const val TREND_STABLE_THRESHOLD_MMOL_PER_MIN = 0.06f

    /**
     * Moderate/rapid slope boundary, mmol/L per minute — same source as
     * [TREND_STABLE_THRESHOLD_MMOL_PER_MIN].
     */
    const val TREND_RAPID_THRESHOLD_MMOL_PER_MIN = 0.11f

    /**
     * Max noise (mmol/L) accepted for manual calibration — RE-confirmed
     * against Reference App (docs/anytime-reference-extract.md, `pp+0x12d50`).
     */
    const val MAX_CALIBRATION_NOISE_MMOL = 0.2f

    /** Official Yuwell native status ids. Unknown means the loaded native path did not report one. */
    const val CALIBRATION_STATUS_UNKNOWN = -1
    const val CALIBRATION_STATUS_NOT = 0
    const val CALIBRATION_STATUS_CAN = 1
    const val CALIBRATION_STATUS_HOPE = 2
    const val CALIBRATION_STATUS_INIT = 3
    const val CALIBRATION_STATUS_MUST = 4

    fun canAcceptManualCalibration(sensorAgeHours: Int): Boolean =
        sensorAgeHours >= MANUAL_CALIBRATION_MIN_AGE_HOURS

    fun canAcceptAlgorithmCalibrationStatus(status: Int): Boolean =
        status == CALIBRATION_STATUS_UNKNOWN || status != CALIBRATION_STATUS_NOT

    fun classifyTrend(slopeMmolPerMin: Float): Trend = when {
        slopeMmolPerMin >= TREND_RAPID_THRESHOLD_MMOL_PER_MIN -> Trend.RAPID_RISE
        slopeMmolPerMin >= TREND_STABLE_THRESHOLD_MMOL_PER_MIN -> Trend.MODERATE_RISE
        slopeMmolPerMin <= -TREND_RAPID_THRESHOLD_MMOL_PER_MIN -> Trend.RAPID_FALL
        slopeMmolPerMin <= -TREND_STABLE_THRESHOLD_MMOL_PER_MIN -> Trend.MODERATE_FALL
        else -> Trend.STABLE
    }

    /** Reference App requires a "→" (stable) trend before accepting a calibration point. */
    fun canAcceptTrendForCalibration(slopeMmolPerMin: Float): Boolean =
        classifyTrend(slopeMmolPerMin) == Trend.STABLE

    fun canAcceptNoiseForCalibration(noiseMmol: Float): Boolean =
        noiseMmol < MAX_CALIBRATION_NOISE_MMOL

    fun calibrationStatusName(status: Int): String = when (status) {
        CALIBRATION_STATUS_UNKNOWN -> "UNKNOWN"
        CALIBRATION_STATUS_NOT -> "NOT"
        CALIBRATION_STATUS_CAN -> "CAN"
        CALIBRATION_STATUS_HOPE -> "HOPE"
        CALIBRATION_STATUS_INIT -> "INIT"
        CALIBRATION_STATUS_MUST -> "MUST"
        else -> "UNKNOWN($status)"
    }
}
