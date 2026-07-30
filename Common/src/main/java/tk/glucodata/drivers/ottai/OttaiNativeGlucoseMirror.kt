package tk.glucodata.drivers.ottai

import java.util.TreeMap

internal class OttaiNativeGlucoseMirror(
    private val prepareNative: (String, Long) -> Boolean,
    private val writeNative: (Long, Float, Float, String) -> Boolean,
    private val rewindNightscout: (String) -> Boolean,
    private val wakeNightscout: (String, Long) -> Unit,
) {
    companion object {
        private const val RECORD_INTERVAL_MS = 60_000L
        private const val MAX_PENDING_HISTORY = 30 * 24 * 60
    }

    private val pendingHistory = TreeMap<Int, Pair<Float, Float>>()

    fun mirrorHistory(
        sensorId: String,
        reliableStartMs: Long,
        dataNos: IntArray,
        glucoseMgdl: FloatArray,
        temperaturesC: FloatArray,
    ): Int {
        require(dataNos.size == glucoseMgdl.size)
        require(dataNos.size == temperaturesC.size)

        dataNos.indices.forEach { index ->
            val dataNo = dataNos[index]
            if (dataNo >= 0) {
                pendingHistory[dataNo] = glucoseMgdl[index] to temperaturesC[index]
            }
        }
        while (pendingHistory.size > MAX_PENDING_HISTORY) {
            pendingHistory.pollFirstEntry()
        }

        if (sensorId.isBlank() || reliableStartMs <= 0L) return 0
        if (!prepareNative(sensorId, reliableStartMs)) return 0
        return replayPendingHistory(sensorId, reliableStartMs).storedCount
    }

    fun mirrorLive(
        sensorId: String,
        reliableStartMs: Long,
        timestampsMs: LongArray,
        glucoseMgdl: FloatArray,
        temperaturesC: FloatArray,
    ): Int {
        require(timestampsMs.size == glucoseMgdl.size)
        require(timestampsMs.size == temperaturesC.size)

        if (sensorId.isBlank() || reliableStartMs <= 0L) return 0
        if (!prepareNative(sensorId, reliableStartMs)) return 0

        val historyReplay = replayPendingHistory(sensorId, reliableStartMs)
        if (!historyReplay.cursorReady) return historyReplay.storedCount

        var storedCount = 0
        var newestStoredLiveTimestampMs = 0L
        timestampsMs.indices.forEach { index ->
            val timestampMs = timestampsMs[index]
            val stored = writeNative(
                timestampMs / 1000L,
                glucoseMgdl[index] / 10f,
                temperaturesC[index],
                sensorId,
            )
            if (stored) {
                storedCount++
                if (timestampMs > newestStoredLiveTimestampMs) {
                    newestStoredLiveTimestampMs = timestampMs
                }
            }
        }
        if (newestStoredLiveTimestampMs > 0L) {
            wakeNightscout("ottai", newestStoredLiveTimestampMs)
        }
        return historyReplay.storedCount + storedCount
    }

    private fun replayPendingHistory(
        sensorId: String,
        reliableStartMs: Long,
    ): HistoryReplay {
        if (pendingHistory.isEmpty()) return HistoryReplay(storedCount = 0, cursorReady = true)

        val storedDataNos = mutableListOf<Int>()
        pendingHistory.forEach { (dataNo, reading) ->
            val timestampMs = reliableStartMs + dataNo.toLong() * RECORD_INTERVAL_MS
            val stored = writeNative(
                timestampMs / 1000L,
                reading.first / 10f,
                reading.second,
                sensorId,
            )
            if (stored) storedDataNos += dataNo
        }
        if (storedDataNos.isEmpty()) {
            return HistoryReplay(storedCount = 0, cursorReady = true)
        }

        val cursorReady = rewindNightscout(sensorId)
        if (cursorReady) {
            storedDataNos.forEach(pendingHistory::remove)
        }
        return HistoryReplay(storedCount = storedDataNos.size, cursorReady = cursorReady)
    }

    private data class HistoryReplay(
        val storedCount: Int,
        val cursorReady: Boolean,
    )
}
