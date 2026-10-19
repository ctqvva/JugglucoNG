package tk.glucodata

/**
 * Guards the UI against driver diagnostics that arrive in a glucose-shaped
 * field — Ottai reports an electrode current in its raw lane, which reached the
 * watch as "11557,0".
 *
 * Only implausibly LARGE values are rejected for display: a very low reading is
 * still a reading, and dropping those would hide exactly the numbers that
 * matter most. [isPlausibleMgdl] keeps a lower bound as well and is meant for
 * data that is about to be stored or synced, not merely shown.
 */
object GlucoseValuePlausibility {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val MIN_GLUCOSE_MGDL = 20f
    private const val MAX_GLUCOSE_MGDL = 600f

    @JvmStatic
    fun isPlausibleMgdl(value: Float): Boolean =
        value.isFinite() && value in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL

    @JvmStatic
    fun isPlausibleDisplayValue(value: Float, isMmol: Boolean): Boolean {
        if (!value.isFinite() || value <= 0f) return false
        val mgdl = if (isMmol) value * MGDL_PER_MMOL else value
        return mgdl <= MAX_GLUCOSE_MGDL
    }
}
