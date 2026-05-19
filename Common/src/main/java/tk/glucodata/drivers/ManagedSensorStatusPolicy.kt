package tk.glucodata.drivers

import tk.glucodata.Applic
import tk.glucodata.R

data class ManagedSensorLifecycleSummary(
    val progress: Float,
    val daysText: String,
    val currentDay: Int,
    val remainingHours: Long,
)

object ManagedSensorStatusPolicy {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

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
        nowMs: Long = System.currentTimeMillis(),
    ): ManagedSensorLifecycleSummary {
        if (startTimeMs <= 0L) {
            return ManagedSensorLifecycleSummary(0f, "", 0, 999L)
        }

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
        val totalMs = reportedTotalHours
            ?.let { it * HOUR_MS }
            ?: (endMs - startTimeMs).coerceAtLeast(1L)

        val remainingHours = reportedRemainingHours
            ?: ((endMs - nowMs).coerceAtLeast(0L) / HOUR_MS)

        val usedMs = reportedAgeHours
            ?.let { (it * HOUR_MS).coerceIn(0L, totalMs) }
            ?: (totalMs - (remainingHours * HOUR_MS)).coerceIn(0L, totalMs)
        val progress = (usedMs.toFloat() / totalMs).coerceIn(0f, 1f)
        val totalDays = reportedTotalHours
            ?.let { ((it + 23L) / 24L).coerceAtLeast(1L) }
            ?: ((totalMs + DAY_MS - 1L) / DAY_MS).coerceAtLeast(1L)
        val computedCurrentDay = reportedAgeHours
            ?.let { (it / 24L) + 1L }
            ?: ((usedMs / DAY_MS) + 1L)
        val currentDay = computedCurrentDay.coerceIn(1L, totalDays).toInt()

        return ManagedSensorLifecycleSummary(
            progress = progress,
            daysText = "$currentDay / $totalDays",
            currentDay = currentDay,
            remainingHours = remainingHours,
        )
    }
}
