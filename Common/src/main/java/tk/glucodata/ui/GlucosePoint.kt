package tk.glucodata.ui

import tk.glucodata.GlucoseUncertainty

/**
 * One point on the glucose timeline.
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
)
