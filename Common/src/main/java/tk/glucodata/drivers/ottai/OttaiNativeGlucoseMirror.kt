package tk.glucodata.drivers.ottai

internal class OttaiNativeGlucoseMirror(
    private val writeNative: (Long, Float, Float, String) -> Boolean,
    private val wakeNightscout: (String, Long) -> Unit,
    private val writeNativeBatch: ((LongArray, FloatArray, FloatArray, String) -> Int)? = null,
) {
    fun mirrorLive(
        sensorId: String,
        timestampMs: Long,
        glucoseMgdl: Float,
        temperatureC: Float,
    ): Boolean {
        val stored = writeNative(
            timestampMs / 1000L,
            glucoseMgdl / 10f,
            temperatureC,
            sensorId,
        )
        if (stored) wakeNightscout("ottai", timestampMs)
        return stored
    }

    /**
     * Backfill read off the sensor, mirrored the way every other managed driver does it
     * (Sibionics writes its whole batch, iCan its rolling window). Ottai was live-only, so
     * Nightscout only ever saw readings from the moment the app happened to be connected —
     * everything the sensor had stored was written to Room and nowhere the uploader looks.
     *
     * One wake for the batch, not one per row: waking per row is what turned an earlier
     * attempt at this into a repeating multi-row resend.
     */
    fun mirrorHistory(
        sensorId: String,
        timestampsMs: LongArray,
        glucoseMgdl: FloatArray,
        temperaturesC: FloatArray,
    ): Int {
        require(timestampsMs.size == glucoseMgdl.size)
        require(timestampsMs.size == temperaturesC.size)

        writeNativeBatch?.let { writeBatch ->
            val timestampsSec = LongArray(timestampsMs.size) { timestampsMs[it] / 1000L }
            val nativeGlucose = FloatArray(glucoseMgdl.size) { glucoseMgdl[it] / 10f }
            val stored = writeBatch(timestampsSec, nativeGlucose, temperaturesC, sensorId)
            if (stored > 0) {
                timestampsMs.asSequence().filter { it > 0L }.maxOrNull()?.let {
                    wakeNightscout("ottai-history", it)
                }
            }
            return stored
        }

        var storedCount = 0
        var newestStoredMs = 0L
        timestampsMs.indices.forEach { index ->
            val timestampMs = timestampsMs[index]
            if (timestampMs <= 0L) return@forEach
            val stored = writeNative(
                timestampMs / 1000L,
                glucoseMgdl[index] / 10f,
                temperaturesC[index],
                sensorId,
            )
            if (stored) {
                storedCount++
                if (timestampMs > newestStoredMs) newestStoredMs = timestampMs
            }
        }
        if (newestStoredMs > 0L) wakeNightscout("ottai-history", newestStoredMs)
        return storedCount
    }
}
