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

    private var lastSequenceId: Int? = null

    fun route(actions: List<InstaraSession.Action>) {
        val history = mutableListOf<InstaraReading>()
        actions.forEach { action ->
            if (action !is InstaraSession.Action.PublishReading) return@forEach
            if (lastSequenceId == action.sequenceId) return@forEach
            lastSequenceId = action.sequenceId
            val reading = InstaraReading(action.raw, action.sequenceId, action.timestampMs)
            if (action.isHistory) history += reading else sink.live(reading)
        }
        if (history.isNotEmpty()) sink.history(history)
    }
}
