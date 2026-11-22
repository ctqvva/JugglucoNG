package tk.glucodata.drivers.icanhealth

import android.content.Context

/**
 * Per-sensor `SdkCgmDeviceInfo.calFactor` equivalent.
 *
 * The vendor keeps one JSON `AlgParameterBean` per sensor and carries its coefficients from one
 * calibration to the next (RU SDK `l0.a`): a missing/blank bean falls back to Kotlin defaults,
 * `planCalib` replaces it with its result, and `startCalib` is fed that same persisted bean.
 * JNG stores the six fields as a locale-independent pipe-separated string in its existing
 * preferences file, keyed by the canonical sensor id.
 */
object ICanHealthCalibrationStore {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_PREFIX = ICanHealthConstants.PREF_CAL_FACTOR_PREFIX

    fun load(context: Context, sensorId: String): ICanHealthCeCalibrationResult? {
        if (sensorId.isBlank()) return null
        val stored = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + sensorId, null)
        }.getOrNull() ?: return null
        return decode(stored)
    }

    fun save(context: Context, sensorId: String, result: ICanHealthCeCalibrationResult) {
        if (sensorId.isBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREFIX + sensorId, encode(result))
                .apply()
        }
    }

    fun remove(context: Context, sensorId: String) {
        if (sensorId.isBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_PREFIX + sensorId).apply()
        }
    }

    internal fun encode(result: ICanHealthCeCalibrationResult): String = listOf(
        result.caliK.toString(),
        if (result.caliAdjustBool) "1" else "0",
        result.caliAdjustNo.toString(),
        result.calibBg.toString(),
        result.calik0.toString(),
        result.calikM.toString(),
    ).joinToString("|")

    internal fun decode(value: String): ICanHealthCeCalibrationResult? {
        val parts = value.split("|")
        if (parts.size != 6 || parts[1] !in setOf("0", "1")) return null
        return runCatching {
            val caliK = parts[0].toFloat()
            val calibBg = parts[3].toFloat()
            val calik0 = parts[4].toFloat()
            val calikM = parts[5].toFloat()
            if (!caliK.isFinite() || !calibBg.isFinite() || !calik0.isFinite() || !calikM.isFinite()) {
                return null
            }
            ICanHealthCeCalibrationResult(
                caliK = caliK,
                caliAdjustBool = parts[1] == "1",
                caliAdjustNo = parts[2].toInt(),
                calibBg = calibBg,
                calik0 = calik0,
                calikM = calikM,
            )
        }.getOrNull()
    }
}
