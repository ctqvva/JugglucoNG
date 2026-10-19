package tk.glucodata.drivers.nightscout

/**
 * Keeps an unchanged, repeating failure to one line per interval.
 *
 * The follower retries every 30 seconds, far faster than an unreachable or refusing server
 * recovers, so the same stack trace every half minute buries everything else in the trace.
 */
internal class RepeatedFollowerError(private val intervalMillis: Long) {
    private var lastMessage: String? = null
    private var lastLoggedAt = 0L
    private var suppressed = 0

    /**
     * @return how many repeats were swallowed since the last line, or -1 to stay silent. A
     *         changed message always speaks, so a new failure is never hidden behind an older
     *         one's interval, and a clock that moved backwards cannot silence it for good.
     */
    fun suppressedSince(message: String, nowMillis: Long): Int {
        val elapsed = nowMillis - lastLoggedAt
        val due = message != lastMessage || lastLoggedAt == 0L ||
            elapsed >= intervalMillis || elapsed < 0
        if (!due) {
            suppressed++
            return -1
        }
        val repeats = if (message == lastMessage) suppressed else 0
        lastMessage = message
        lastLoggedAt = nowMillis
        suppressed = 0
        return repeats
    }

    /** A success ends the episode, so the next failure reports immediately. */
    fun reset() {
        lastMessage = null
        lastLoggedAt = 0L
        suppressed = 0
    }
}
