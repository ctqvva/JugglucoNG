package tk.glucodata.NovoPen

import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The lifetime of the result notification shown after an unattended pen read.
 * Zero deliberately means no notification; the caller still shows the result toast.
 */
object PenImportNotificationPolicy {
    const val DEFAULT_DURATION_MINUTES = 1

    val durationOptionsMinutes = listOf(0, 1, 5, 15, 30, 60, 180, 720)

    fun normalizeDurationMinutes(minutes: Int): Int =
        durationOptionsMinutes.minByOrNull { kotlin.math.abs(it.toLong() - minutes.toLong()) }
            ?: DEFAULT_DURATION_MINUTES

    fun sliderIndexForDuration(minutes: Int): Float =
        durationOptionsMinutes.indexOf(normalizeDurationMinutes(minutes)).toFloat()

    fun durationMinutesForSliderIndex(index: Float): Int =
        durationOptionsMinutes[index.roundToInt().coerceIn(durationOptionsMinutes.indices)]

    fun timeoutMillis(minutes: Int): Long? =
        normalizeDurationMinutes(minutes)
            .takeIf { it > 0 }
            ?.let { TimeUnit.MINUTES.toMillis(it.toLong()) }
}
