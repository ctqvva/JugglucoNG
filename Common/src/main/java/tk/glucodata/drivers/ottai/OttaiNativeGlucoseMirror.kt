package tk.glucodata.drivers.ottai

internal class OttaiNativeGlucoseMirror(
    private val writeNative: (Long, Float, Float, String) -> Boolean,
    private val wakeNightscout: (String, Long) -> Unit,
) {
    fun mirror(
        sensorId: String,
        timestampsMs: LongArray,
        glucoseMgdl: FloatArray,
        temperaturesC: FloatArray,
        live: Boolean,
    ): Int {
        require(timestampsMs.size == glucoseMgdl.size)
        require(timestampsMs.size == temperaturesC.size)

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
                if (live && timestampMs > newestStoredLiveTimestampMs) {
                    newestStoredLiveTimestampMs = timestampMs
                }
            }
        }
        if (newestStoredLiveTimestampMs > 0L) {
            wakeNightscout("ottai", newestStoredLiveTimestampMs)
        }
        return storedCount
    }
}
