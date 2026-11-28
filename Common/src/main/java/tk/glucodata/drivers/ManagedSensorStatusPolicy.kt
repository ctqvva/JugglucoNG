package tk.glucodata.drivers

import tk.glucodata.Applic
import tk.glucodata.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

data class ManagedSensorLifecycleSummary(
    val progress: Float,
    val daysText: String,
    val currentDay: Int,
    val remainingHours: Long,
)

object ManagedSensorStatusPolicy {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val HALF_DAY_MS = 12L * HOUR_MS

    /**
     * How long a sensor's wear lasts, in half days. A span within an hour of a whole number of
     * days and a half keeps its half, so a Libre 2's 14 days and 12 hours of Bluetooth reads
     * 14.5 and not 15. Any other span rounds to the nearest whole day, so Ottai's 15 days and
     * 30 minutes stays 15.
     */
    @JvmStatic
    fun wearHalfDays(spanMs: Long): Long {
        val halves = (spanMs + HALF_DAY_MS / 2L) / HALF_DAY_MS
        if (halves % 2L == 1L && halves >= 3L && abs(spanMs - halves * HALF_DAY_MS) <= HOUR_MS) {
            return halves
        }
        return ((spanMs + DAY_MS / 2L) / DAY_MS).coerceAtLeast(1L) * 2L
    }

    /** [wearHalfDays] as the number of days it is: 14, or 14.5. */
    @JvmStatic
    fun wearDaysNumber(halfDays: Long): Number =
        if (halfDays % 2L == 0L) halfDays / 2L else halfDays / 2.0

    /** [wearDaysNumber] in the locale's own digits: "14", "14.5", "14,5". */
    @JvmStatic
    @JvmOverloads
    fun wearDaysText(halfDays: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
            .format(wearDaysNumber(halfDays))

    private val passiveSummaryStatuses = setOf(
        "ready",
        "connected",
        "disconnected",
        "receiving",
        "broadcast",
        "broadcast only",
        "broadcast mode",
        "broadcast mode — receiving",
        "connected (broadcast)",
        "connected (broadcast fallback)",
        "connected (polling)",
        "connected (initializing)",
        "connected, waiting for data...",
        "waiting for data...",
        "reconnecting...",
        "connecting...",
        "pairing...",
        "bonding...",
        "discovering services...",
        "setting up notifications...",
        "handshaking...",
        "scanning...",
        "scanning for broadcasts",
        "scanning for broadcasts...",
        "searching for sensors",
        "waiting for broadcast...",
    )

    private val passiveSummaryStatusResourceIds = intArrayOf(
        R.string.status_connected,
        R.string.status_disconnected,
        R.string.status_waiting_for_data,
        R.string.connecting,
        R.string.syncing,
    )

    private fun localizedPassiveSummaryStatuses(): Set<String> {
        val context = Applic.app ?: return passiveSummaryStatuses
        return buildSet(passiveSummaryStatuses.size + passiveSummaryStatusResourceIds.size) {
            addAll(passiveSummaryStatuses)
            passiveSummaryStatusResourceIds.forEach { resId ->
                runCatching { context.getString(resId).trim().lowercase() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
            }
        }
    }

    @JvmStatic
    fun collapseSummaryStatus(status: String?): String {
        val normalized = status?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return ""
        }
        return if (isPassiveSummaryStatus(normalized)) "" else normalized
    }

    @JvmStatic
    fun isPassiveSummaryStatus(status: String?): Boolean {
        val normalized = status?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) {
            return true
        }
        return normalized in localizedPassiveSummaryStatuses()
    }

    @JvmStatic
    @JvmOverloads
    fun resolveLifecycleSummary(
        startTimeMs: Long,
        officialEndMs: Long,
        expectedEndMs: Long,
        sensorRemainingHours: Int = -1,
        sensorAgeHours: Int = -1,
        fallbackDurationDays: Int = 14,
        allowExpectedEndOverrun: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
    ): ManagedSensorLifecycleSummary {
        if (startTimeMs <= 0L) {
            return ManagedSensorLifecycleSummary(0f, "", 0, 999L)
        }

        val hasExplicitEnd = expectedEndMs > startTimeMs || officialEndMs > startTimeMs
        val fallbackEndMs = startTimeMs + (fallbackDurationDays.toLong() * DAY_MS)
        val endMs = when {
            expectedEndMs > startTimeMs -> expectedEndMs
            officialEndMs > startTimeMs -> officialEndMs
            else -> fallbackEndMs
        }
        val reportedAgeHours = sensorAgeHours.takeIf { it >= 0 }?.toLong()
        val reportedRemainingHours = sensorRemainingHours.takeIf { it >= 0 }?.toLong()?.coerceAtLeast(0L)
        val reportedTotalHours = if (reportedAgeHours != null && reportedRemainingHours != null) {
            (reportedAgeHours + reportedRemainingHours).coerceAtLeast(1L)
        } else {
            null
        }
        // Total lifetime window: an explicit sensor end (expected/official) wins over the
        // driver-reported age+remaining span, which in turn beats the fixed fallback. The
        // driver-reported values still drive remaining hours and current day below, so a
        // 21-day sensor reads "20 / 21" rather than collapsing to the reported-hours span.
        val windowMs = (endMs - startTimeMs).coerceAtLeast(1L)
        val totalMs = when {
            hasExplicitEnd -> windowMs
            reportedTotalHours != null -> reportedTotalHours * HOUR_MS
            else -> windowMs
        }

        val isExpectedEndOverrun = allowExpectedEndOverrun &&
            expectedEndMs > startTimeMs &&
            nowMs >= expectedEndMs
        val remainingHours = if (isExpectedEndOverrun) {
            -1L
        } else {
            reportedRemainingHours ?: ((endMs - nowMs).coerceAtLeast(0L) / HOUR_MS)
        }

        val usedMs = reportedAgeHours
            ?.let { (it * HOUR_MS).coerceIn(0L, totalMs) }
            ?: if (remainingHours >= 0L) {
                (totalMs - (remainingHours * HOUR_MS)).coerceIn(0L, totalMs)
            } else {
                totalMs
            }
        val progress = (usedMs.toFloat() / totalMs).coerceIn(0f, 1f)
        // Round the lifetime to the NEAREST whole day, not up. Some sensors carry a small
        // sub-day overage in their reported end (e.g. Ottai's maxActive is 15d + 30min), and
        // ceiling turned a genuine 15-day sensor into "16". Round-to-nearest shows 15 for
        // 15d+30min while still showing 16 for a real 15.6d, and leaves exact-day sensors
        // (14d/15d/21d) unchanged. A span of whole days and a half keeps its half (a Libre 2's
        // 14.5); the current day then runs to 15, the day that half falls in.
        val totalHalfDays = wearHalfDays(totalMs)
        val totalDays = (totalHalfDays + 1L) / 2L
        val computedCurrentDay = reportedAgeHours
            ?.let { (it / 24L) + 1L }
            ?: ((usedMs / DAY_MS) + 1L)
        val currentDay = if (isExpectedEndOverrun) {
            computedCurrentDay.coerceAtLeast(totalDays + 1L).toInt()
        } else {
            computedCurrentDay.coerceIn(1L, totalDays).toInt()
        }

        return ManagedSensorLifecycleSummary(
            progress = progress,
            daysText = "$currentDay / ${wearDaysText(totalHalfDays, locale)}",
            currentDay = currentDay,
            remainingHours = remainingHours,
        )
    }
}
