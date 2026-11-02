package tk.glucodata

import android.content.Context
import kotlin.math.abs

/** Speak the value and direction of the same resolved reading, without arrow glyphs. */
object GlucoseSpeech {
    @JvmStatic
    fun format(context: Context, reading: CurrentDisplaySource.Snapshot): String =
        format(context, reading.speechPrimaryStr, reading.rate)

    /** [rateMgdlPerMinute] stays in mg/dL/min even when the value is spoken in mmol/L. */
    @JvmStatic
    fun format(context: Context, value: String, rateMgdlPerMinute: Float): String {
        // An unavailable trend must not be announced as stable.
        if (!rateMgdlPerMinute.isFinite()) return value
        val direction = when {
            abs(rateMgdlPerMinute) <= TrendArrowAngle.FLAT_BELOW_RATE -> R.string.tts_trend_stable
            rateMgdlPerMinute >= 2f -> R.string.tts_trend_rising_quickly
            rateMgdlPerMinute <= -2f -> R.string.tts_trend_falling_quickly
            rateMgdlPerMinute > 0f -> R.string.tts_trend_increasing
            else -> R.string.tts_trend_decreasing
        }
        return "$value, ${context.getString(direction)}"
    }
}
