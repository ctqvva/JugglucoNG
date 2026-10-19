package tk.glucodata.sms

import androidx.annotation.Keep

/**
 * Rolling send ledger backing [SmsPolicy.maxPerHour] / [SmsPolicy.maxPerDay].
 *
 * A stuck sensor, a flapping mobile connection or a mis-set threshold must never
 * be able to empty someone's prepaid balance, so every send — escalation, relay
 * and test alike — is counted here first.
 *
 * Immutable and clock-free so the caps are testable; [SmsWatchdog] owns
 * persistence.
 */
@Keep
data class SmsBudget(val sendsAtMs: List<Long> = emptyList()) {

    fun pruned(nowMs: Long): SmsBudget {
        val cutoff = nowMs - DAY_MS
        val kept = sendsAtMs.filter { it > cutoff }
        return if (kept.size == sendsAtMs.size) this else SmsBudget(kept)
    }

    fun countInLastHour(nowMs: Long): Int = sendsAtMs.count { it > nowMs - HOUR_MS }

    fun countInLastDay(nowMs: Long): Int = sendsAtMs.count { it > nowMs - DAY_MS }

    fun record(nowMs: Long, count: Int): SmsBudget {
        if (count <= 0) return this
        val next = ArrayList<Long>(sendsAtMs.size + count)
        next += sendsAtMs
        repeat(count) { next += nowMs }
        return SmsBudget(next).pruned(nowMs).capped()
    }

    /** Keeps the stored list bounded even if someone sets absurd caps. */
    private fun capped(): SmsBudget =
        if (sendsAtMs.size <= MAX_TRACKED) this
        else SmsBudget(sendsAtMs.takeLast(MAX_TRACKED))

    fun encode(): String = sendsAtMs.joinToString(",")

    @Keep
    companion object {
        const val HOUR_MS = 60 * 60_000L
        const val DAY_MS = 24 * HOUR_MS
        private const val MAX_TRACKED = 500

        fun decode(raw: String?): SmsBudget {
            if (raw.isNullOrBlank()) return SmsBudget()
            val values = raw.split(',').mapNotNull { it.trim().toLongOrNull() }
            return SmsBudget(values)
        }
    }
}
