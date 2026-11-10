package tk.glucodata.ui.util

import tk.glucodata.ui.GlucosePoint

/** Carry display facts and Auto uncertainty across the mobile/shared chart boundary. */
internal fun GlucosePoint.toSharedGlucosePoint(): tk.glucodata.GlucosePoint =
    tk.glucodata.GlucosePoint(timestamp, value, rawValue).also {
        it.sealedDisplayValue = sealedDisplayValue ?: Float.NaN
        it.sealedDisplayViewMode = sealedDisplayViewMode ?: -1
        it.sensorSerial = sensorSerial
        it.uncertainty = uncertainty
    }
