package tk.glucodata.alerts

internal class ReadingTimestampBarrier {
    private var suppressThroughTimeMs = 0L

    fun suppressThrough(readingTimeMs: Long) {
        if (readingTimeMs > suppressThroughTimeMs) {
            suppressThroughTimeMs = readingTimeMs
        }
    }

    fun blocks(readingTimeMs: Long): Boolean {
        return suppressThroughTimeMs > 0L && readingTimeMs in 1..suppressThroughTimeMs
    }
}
