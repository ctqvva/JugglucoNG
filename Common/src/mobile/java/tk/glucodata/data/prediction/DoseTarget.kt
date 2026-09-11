package tk.glucodata.data.prediction

/**
 * What a correction aims at, stored in mg/dL. Deliberately not derived from the in-range
 * band: that band is a display range and far too wide to dose against.
 */
object DoseTarget {
    /** 90 mg/dL ≈ 5.0 mmol/L. */
    const val DEFAULT_MGDL = 90f
    const val MIN_MGDL = 70f
    const val MAX_MGDL = 180f
}
