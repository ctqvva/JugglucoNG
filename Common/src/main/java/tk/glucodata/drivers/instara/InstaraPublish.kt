package tk.glucodata.drivers.instara

data class InstaraReading(
    val raw: Float,
    val sequenceId: Int,
    val timestampMs: Long,
)

interface InstaraPublishSink {
    fun live(reading: InstaraReading)
    fun history(readings: List<InstaraReading>)
}

/**
 * Turns session actions into storage calls, keeping the timestamp and sequence id the session
 * computed. One instance lives for the driver's lifetime so a retransmitted history frame cannot
 * publish a second copy of the same point after a reconnect.
 */
class InstaraActionRouter(private val sink: InstaraPublishSink) {

    private var lastStoredSequenceId: Int? = null
    private var lastLiveSequenceId: Int? = null

    fun route(actions: List<InstaraSession.Action>) {
        val history = mutableListOf<InstaraReading>()
        actions.forEach { action ->
            if (action !is InstaraSession.Action.PublishReading) return@forEach
            val reading = InstaraReading(action.raw, action.sequenceId, action.timestampMs)
            if (action.isHistory) {
                if (lastStoredSequenceId == action.sequenceId) return@forEach
                lastStoredSequenceId = action.sequenceId
                history += reading
            } else {
                // Storage dedup must never suppress the live alert path: a retransmitted point
                // that history already stored still deserves one live delivery.
                if (lastLiveSequenceId == action.sequenceId) return@forEach
                lastLiveSequenceId = action.sequenceId
                sink.live(reading)
            }
        }
        if (history.isNotEmpty()) sink.history(history)
    }
}
