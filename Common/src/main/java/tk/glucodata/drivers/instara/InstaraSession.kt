package tk.glucodata.drivers.instara

class InstaraSession(
    private val sn: String,
    private val activationMs: Long,
    private val intervalMs: Long,
    private val historyCursor: Int = 0,
) {
    private var samplingIntervalMs: Long = intervalMs
    sealed class Action {
        data class Send(val bytes: ByteArray, val delayMs: Long = 0) : Action()
        data class PublishReading(
            val raw: Float,
            val sequenceId: Int,
            val timestampMs: Long,
            val isHistory: Boolean = false,
        ) : Action()
        data class PublishBattery(val percent: Int) : Action()
        data class StatusChanged(val text: String) : Action()
        object HistoryComplete : Action()
    }

    var isAuthorized: Boolean = false
        private set
    var historyComplete: Boolean = false
        private set
    var currentSequenceId: Int = 0
        private set

    private var expectedFrames = 0
    private var receivedFrames = 0

    fun onConnected(): List<Action> {
        isAuthorized = false
        historyComplete = false
        currentSequenceId = 0
        expectedFrames = 0
        receivedFrames = 0
        return emptyList()
    }

    fun onPacket(packet: InstaraProtocol.Packet): List<Action> = when (packet) {
        is InstaraProtocol.Packet.Handshake -> listOf(
            Action.Send(InstaraProtocol.ack(InstaraProtocol.RSP_HANDSHAKE)),
            Action.Send(
                InstaraProtocol.frame(InstaraProtocol.CMD_SN, sn.toByteArray(Charsets.US_ASCII)),
                delayMs = 100L,
            ),
        )

        is InstaraProtocol.Packet.SnAuth -> {
            if (!packet.ok) {
                isAuthorized = false
                listOf(Action.StatusChanged("SN rejected"))
            } else {
                isAuthorized = true
                val minutes = (samplingIntervalMs / 60_000L).coerceIn(1L, 255L).toInt()
                listOf(
                    Action.Send(InstaraProtocol.frame(InstaraProtocol.CMD_USE_TIME, byteArrayOf(DEFAULT_USE_TIME_DAYS.toByte()))),
                    Action.Send(InstaraProtocol.frame(InstaraProtocol.CMD_SET_DURATION, u16Bytes(minutes))),
                    Action.Send(InstaraProtocol.frame(InstaraProtocol.CMD_SEQUENCE, u16Bytes(historyCursor))),
                )
            }
        }

        is InstaraProtocol.Packet.Realtime -> {
            if (!isAuthorized) return emptyList()
            currentSequenceId = packet.sequenceId
            listOf(
                Action.Send(InstaraProtocol.ack(InstaraProtocol.RSP_BLOOD)),
                Action.PublishReading(packet.glucoseRaw, packet.sequenceId, System.currentTimeMillis()),
            )
        }

        is InstaraProtocol.Packet.HistoryHeader -> {
            expectedFrames = packet.frameCount
            receivedFrames = 0
            historyComplete = false
            emptyList()
        }

        is InstaraProtocol.Packet.HistoryPayload -> {
            if (!isAuthorized) return emptyList()
            val nowMs = System.currentTimeMillis()
            val actions = mutableListOf<Action>(Action.Send(InstaraProtocol.ack(InstaraProtocol.RSP_HISTORY)))
            packet.values.forEachIndexed { index, value ->
                val seq = packet.startSequenceId + index
                actions += Action.PublishReading(
                    value,
                    seq,
                    timestampFor(activationMs, seq, samplingIntervalMs, nowMs),
                    isHistory = true,
                )
            }
            currentSequenceId = packet.startSequenceId + packet.values.size - 1
            receivedFrames++
            if (expectedFrames > 0 && receivedFrames >= expectedFrames) {
                historyComplete = true
                actions += Action.HistoryComplete
            }
            actions
        }

        is InstaraProtocol.Packet.Duration -> {
            if (packet.minutes in 1..255) samplingIntervalMs = packet.minutes.toLong() * 60_000L
            emptyList()
        }

        is InstaraProtocol.Packet.Battery -> listOf(
            Action.Send(InstaraProtocol.ack(InstaraProtocol.RSP_BATTERY)),
            Action.PublishBattery(packet.percent),
        )

        else -> emptyList()
    }

    companion object {
        const val DEFAULT_INTERVAL_MIN = 5
        const val MGDL_PER_MMOL = 18.0182f
        private const val DEFAULT_USE_TIME_DAYS = 14

        fun mmolToMgdl(mmol: Float): Float = mmol * MGDL_PER_MMOL

        fun timestampFor(activationMs: Long, sequenceId: Int, intervalMs: Long, nowMs: Long): Long {
            if (activationMs <= 0L) return nowMs
            val start = activationMs.coerceAtMost(nowMs)
            return (start + sequenceId.toLong() * intervalMs).coerceIn(start, nowMs)
        }

        fun dedupBySequenceId(readings: List<Pair<Int, Float>>): List<Pair<Int, Float>> {
            val seen = HashSet<Int>()
            return readings.filter { seen.add(it.first) }
        }

        private fun u16Bytes(value: Int): ByteArray =
            byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    }
}
