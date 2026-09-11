package tk.glucodata.alerts

/**
 * Rolling record of compression-hold episodes: what was held, for how long, and how it
 * ended. This is what makes the feature auditable — the summary ("3 held, 2 resolved as
 * pressure, 1 escalated after 6 min") turns the mode from a belief into a statistic, and
 * the escalation count drives the self-disable check. Day and night episodes are the
 * same thing here; nothing in this log knows the clock beyond the timestamps it stores.
 *
 * Immutable and clock-free so the arithmetic is testable; the runtime owns persistence
 * (one string in the alerts prefs file). Modeled on [tk.glucodata.sms.SmsBudget].
 */
internal data class CompressionHoldLog(val entries: List<Entry>) {

    enum class Outcome {
        /** The low cleared during the hold: pressure artifact, alarm never needed. */
        RESOLVED,

        /** The hold ended and the LOW alarm fired: hard floor, expiry, or no upturn. */
        ESCALATED
    }

    data class Entry(
        val startMs: Long,
        val endMs: Long,
        val outcome: Outcome,
        /** The escalation reason or "resolved"; kebab-case, for the report line. */
        val reason: String
    )

    fun record(entry: Entry): CompressionHoldLog =
        CompressionHoldLog((entries + entry).takeLast(MAX_TRACKED))

    fun pruned(nowMs: Long): CompressionHoldLog =
        CompressionHoldLog(entries.filter { nowMs - it.endMs <= RETENTION_MS })

    fun resolvedCount(): Int = entries.count { it.outcome == Outcome.RESOLVED }

    fun escalatedCount(): Int = entries.count { it.outcome == Outcome.ESCALATED }

    /**
     * Whether the mode owes the user a self-disable: [limit] escalations since the log
     * was last cleared. A heuristic that keeps escalating is wrong for this body and
     * must switch itself off rather than wait to be babysat; re-enabling clears the log
     * and starts the count fresh.
     */
    fun selfDisableDue(limit: Int): Boolean = limit > 0 && escalatedCount() >= limit

    fun encode(): String = entries.joinToString(SEPARATOR) {
        "${it.startMs}$FIELD${it.endMs}$FIELD${it.outcome.name}$FIELD${it.reason}"
    }

    companion object {
        val EMPTY = CompressionHoldLog(emptyList())

        const val MAX_TRACKED = 200
        const val RETENTION_MS = 14L * 24 * 60 * 60_000L

        private const val SEPARATOR = ";"
        private const val FIELD = ","

        /** Tolerant of garbage: an unreadable entry is dropped, never a crash. */
        fun decode(raw: String?): CompressionHoldLog {
            if (raw.isNullOrBlank()) return EMPTY
            val entries = raw.split(SEPARATOR).mapNotNull { chunk ->
                val fields = chunk.split(FIELD)
                if (fields.size != 4) return@mapNotNull null
                val start = fields[0].toLongOrNull() ?: return@mapNotNull null
                val end = fields[1].toLongOrNull() ?: return@mapNotNull null
                val outcome = runCatching { Outcome.valueOf(fields[2]) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(start, end, outcome, fields[3])
            }
            return CompressionHoldLog(entries.takeLast(MAX_TRACKED))
        }
    }
}
