package tk.glucodata

/**
 * What a live reading is made of, so [CurrentDisplaySource] never has to guess
 * whether the number it is handed was already calibrated.
 *
 * The BLE path publishes a value it has already run through the user's
 * calibration. Handed on as a bare float, the display source calibrated it a
 * second time whenever the reading had not reached history yet: #431, a +42
 * mg/dL offset raised HIGH alarms at 176 while the screen read 134.
 *
 * [stock] carries the sensor's own lanes, before this app's calibration; the
 * display source calibrates them once, exactly like the history it merges them
 * with. [resolved] carries a value an earlier step already took through the
 * display pipeline; it is final and is not calibrated again.
 *
 * All values are in display units; NaN where a lane is absent.
 */
class LiveReadingLanes private constructor(
    val stockAuto: Float,
    val stockRaw: Float,
    val resolvedValue: Float,
) {
    val isResolved: Boolean get() = isValue(resolvedValue)

    val hasValue: Boolean get() = isResolved || isValue(stockAuto) || isValue(stockRaw)

    companion object {
        @JvmStatic
        fun stock(auto: Float, raw: Float): LiveReadingLanes = LiveReadingLanes(auto, raw, Float.NaN)

        @JvmStatic
        fun resolved(value: Float): LiveReadingLanes = LiveReadingLanes(Float.NaN, Float.NaN, value)

        private fun isValue(value: Float): Boolean = value.isFinite() && value > 0.1f
    }
}
