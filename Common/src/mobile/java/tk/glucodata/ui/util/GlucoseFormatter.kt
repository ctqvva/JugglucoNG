package tk.glucodata.ui.util

import java.util.Locale
import tk.glucodata.ui.GlucosePoint

object GlucoseFormatter {

    const val MGDL_TO_MMOL = 0.0555f

    /**
     * mg/dL ↔ mmol/L exact factor — used as the multiplier when going from
     * mmol/L to mg/dL (the inverse direction of [MGDL_TO_MMOL]).
     *
     * Note: this is intentionally not `1 / MGDL_TO_MMOL` — both factors are
     * pinned to their decimal forms so existing rounded outputs stay stable.
     */
    const val MGDL_PER_MMOL = 18.0182f

    /**
     * Format a glucose value based on the unit type.
     * mg/dL -> No decimals (e.g. "100")
     * mmol/L -> One decimal (e.g. "5.5")
     */
    fun format(value: Float, isMmol: Boolean): String {
        return if (isMmol) {
            String.format(Locale.getDefault(), "%.1f", value)
        } else {
            String.format(Locale.getDefault(), "%.0f", value)
        }
    }

    /**
     * Convert mg/dL to mmol/L
     */
    fun mgToMmol(mgDl: Float): Float = mgDl * MGDL_TO_MMOL

    /**
     * Convert mmol/L to mg/dL
     */
    fun mmolToMg(mmol: Float): Float = mmol / MGDL_TO_MMOL

    /**
     * Convert an internal mg/dL value to the currently displayed unit value.
     */
    fun displayFromMgDl(valueMgDl: Float, isMmol: Boolean): Float {
        return if (isMmol) mgToMmol(valueMgDl) else valueMgDl
    }

    /**
     * Format a value stored in mg/dL to the selected display unit.
     */
    fun formatFromMgDl(valueMgDl: Float, isMmol: Boolean): String {
        return format(displayFromMgDl(valueMgDl, isMmol), isMmol)
    }

    /**
     * Format for CSV (Always US Locale / Dot separator)
     */
    fun formatCsv(value: Float, unit: String): String {
        val isMmol = isMmol(unit)
        return if (isMmol) {
            String.format(Locale.US, "%.1f", value)
        } else {
            String.format(Locale.US, "%.0f", value)
        }
    }

    /**
     * Check if unit string implies mmol/L.
     */
    fun isMmol(unit: String?): Boolean {
        return unit?.contains("mmol", ignoreCase = true) == true
    }

    /**
     * Check global app state for mmol/L preference.
     */
    fun isMmolApp(): Boolean {
        return tk.glucodata.Applic.unit == 1
    }
}

/**
 * Convert this raw mg/dL [GlucosePoint] to the requested display unit. Returns
 * the receiver unchanged for mg/dL.
 */
fun GlucosePoint.inDisplayUnit(unit: String): GlucosePoint =
    inDisplayUnit(GlucoseFormatter.isMmol(unit))

fun GlucosePoint.inDisplayUnit(isMmol: Boolean): GlucosePoint {
    if (!isMmol) return this
    return copy(
        value = GlucoseFormatter.mgToMmol(value),
        rawValue = GlucoseFormatter.mgToMmol(rawValue)
    )
}

/**
 * Convert a raw mg/dL [GlucosePoint] list to the requested display unit. Returns
 * the receiver unchanged for mg/dL — the common case — so it's always safe to
 * call. Caller is responsible for offloading the work to a background
 * dispatcher when the list is large enough to matter (long histories, stats
 * windows).
 */
fun List<GlucosePoint>.inDisplayUnit(unit: String): List<GlucosePoint> {
    if (!GlucoseFormatter.isMmol(unit)) return this
    return map { it.inDisplayUnit(unit) }
}

/**
 * Same as [List.inDisplayUnit] but resolved against the boolean `isMmol` flag
 * already in the caller's hand. Useful inside hot loops that compute `isMmol`
 * once per emission.
 */
fun List<GlucosePoint>.inDisplayUnit(isMmol: Boolean): List<GlucosePoint> {
    if (!isMmol) return this
    return map { it.inDisplayUnit(true) }
}
