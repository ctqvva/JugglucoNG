package tk.glucodata.ui

import tk.glucodata.GlucoseUncertainty

/**
 * One point on the glucose timeline.
 *
 * [value] and [rawValue] are what the sensor produced. [sealedDisplayValue] is
 * what the app actually showed for this reading, once that stopped being
 * re-derivable — see `tk.glucodata.data.ReadingDisplay`. It is null for readings
 * that are still settling, for readings stored before the record existed, and
 * whenever the user has turned the freeze off; a display path that finds one
 * should prefer it over recomputing, so that changing a calibration cannot
 * silently move a line the user already read.
 *
 * [uncertainty] is optional and sensor-agnostic: estimators that model a
 * credible interval attach one, everything else leaves it null and renders
 * exactly as before. Its bounds are in the same unit as [value].
 */
data class GlucosePoint(
    val value: Float,
    val time: String,
    val timestamp: Long = 0L,
    val rawValue: Float = 0f,
    val rate: Float? = null,
    val sensorSerial: String? = null,
    val uncertainty: GlucoseUncertainty? = null,
    val sealedDisplayValue: Float? = null,
)
