package tk.glucodata.ui

import tk.glucodata.GlucoseReadingSource
import tk.glucodata.GlucoseUncertainty

/**
 * One point on the glucose timeline.
 *
 * [value] and [rawValue] are what the sensor produced. [sealedAutoLaneValue]
 * and [sealedRawLaneValue] are what the app actually showed as the main value
 * for this minute, once that stopped being re-derivable — one per main line,
 * because the auto line and the raw line show different numbers for the same
 * minute (see `tk.glucodata.data.ReadingDisplay`). Each is null for a minute
 * still settling, for one never shown in that lane, for readings stored before
 * the record existed, and whenever the user has turned the freeze off. A
 * display path takes the one for the lane it draws ([sealedValueFor]) and
 * prefers it over recomputing, so that changing a calibration cannot silently
 * move a line the user already read.
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
    val source: String = GlucoseReadingSource.SENSOR,
    val uncertainty: GlucoseUncertainty? = null,
    /** The main value recorded for this minute on the auto line (view modes 0/2). */
    val sealedAutoLaneValue: Float? = null,
    /** The main value recorded for this minute on the raw line (view modes 1/3). */
    val sealedRawLaneValue: Float? = null,
) {
    /**
     * The recorded main value of the lane being drawn, or null. A record never
     * stands in for the other lane: a minute shown on the raw line says
     * nothing about what the auto line showed.
     */
    fun sealedValueFor(isRawMode: Boolean): Float? =
        if (isRawMode) sealedRawLaneValue else sealedAutoLaneValue
}
