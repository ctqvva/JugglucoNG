package tk.glucodata

/**
 * Registration seam for [HistoryRepositoryBridge] (plan P1/Q1).
 *
 * Shared/main display and export code used to find `tk.glucodata.data.HistoryRepository`
 * by name. The keep rule was a hand-copy of the reflective names and nothing
 * checked the two agreed; once they did not, and the Ottai driver re-pulled whole
 * histories in silence (713631f9). Explicit registration leaves ordinary
 * interface calls behind.
 *
 * Every method keeps the old reflective failure contract: a failure degrades to
 * the default instead of reaching the shared caller.
 */
object HistoryRepositoryAccess {
    private const val TAG = "HistoryRepoAccess"

    @Volatile
    private var bridge: HistoryRepositoryBridge? = null

    @JvmStatic
    fun register(bridge: HistoryRepositoryBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun resetBackfillFlag() {
        runCatching { bridge?.resetBackfillFlag() }
            .onFailure { Log.stack(TAG, "resetBackfillFlag failed", it) }
    }

    @JvmStatic
    fun storeAidexReadingAsync(timestamp: Long, valueMmol: Float, source: Int) {
        runCatching { bridge?.storeAidexReadingAsync(timestamp, valueMmol, source) }
            .onFailure { Log.stack(TAG, "storeAidexReadingAsync failed", it) }
    }

    @JvmStatic
    fun storeCurrentReadingAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
    ) {
        runCatching { bridge?.storeCurrentReadingAsync(timestamp, valueMgdl, rawValueMgdl, rate, sensorSerial) }
            .onFailure { Log.stack(TAG, "storeCurrentReadingAsync failed", it) }
    }

    @JvmStatic
    fun storeCurrentReadingWithSourceAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
        source: String,
    ) {
        runCatching {
            bridge?.storeCurrentReadingWithSourceAsync(
                timestamp,
                valueMgdl,
                rawValueMgdl,
                rate,
                sensorSerial,
                source,
            )
        }.onFailure { Log.stack(TAG, "storeCurrentReadingWithSourceAsync failed", it) }
    }

    @JvmStatic
    fun storeHistoryBatchAsync(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ) {
        runCatching { bridge?.storeHistoryBatchAsync(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl) }
            .onFailure { Log.stack(TAG, "storeHistoryBatchAsync failed", it) }
    }

    @JvmStatic
    fun storeHistoryBatchBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ): Boolean =
        runCatching { bridge?.storeHistoryBatchBlocking(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl) }
            .onFailure { Log.stack(TAG, "storeHistoryBatchBlocking failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun storeHistoryBatchWithSourceBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        source: String,
    ): Boolean =
        // No retry without the source on failure: that would store a follower's
        // readings under the sensor default, which the old bridge never did.
        runCatching {
            bridge?.storeHistoryBatchWithSourceBlocking(
                sensorSerial,
                timestamps,
                valuesMgdl,
                rawValuesMgdl,
                source,
            )
        }.onFailure { Log.stack(TAG, "storeHistoryBatchWithSourceBlocking failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun getLatestTimestampForSensorBlocking(sensorSerial: String): Long =
        runCatching { bridge?.getLatestTimestampForSensorBlocking(sensorSerial) }
            .onFailure { Log.stack(TAG, "getLatestTimestampForSensorBlocking failed", it) }
            .getOrNull() ?: 0L

    /**
     * Timestamps already stored, or null when the question could not be asked at
     * all. Callers diff this against what a sensor claims to hold; collapsing
     * "could not ask" into an empty array made the Ottai driver re-pull a whole
     * history on every reconnect (713631f9).
     */
    @JvmStatic
    fun getHistoryTimestampsForSensorOrNull(
        sensorSerial: String,
        startTime: Long,
        endTime: Long,
    ): LongArray? =
        runCatching { bridge?.getHistoryTimestampsForSensorBlocking(sensorSerial, startTime, endTime) }
            .onFailure { Log.stack(TAG, "getHistoryTimestampsForSensorOrNull failed", it) }
            .getOrNull()

    @JvmStatic
    fun getHistoryTimestampsForSensor(
        sensorSerial: String,
        startTime: Long,
        endTime: Long,
    ): LongArray = getHistoryTimestampsForSensorOrNull(sensorSerial, startTime, endTime) ?: LongArray(0)

    @JvmStatic
    fun deleteReadingsForSensorAfterBlocking(sensorSerial: String, timestampExclusive: Long): Int =
        runCatching { bridge?.deleteReadingsForSensorAfterBlocking(sensorSerial, timestampExclusive) }
            .onFailure { Log.stack(TAG, "deleteReadingsForSensorAfterBlocking failed", it) }
            .getOrNull() ?: 0

    @JvmStatic
    fun getMainSensorOwnership(startTimeMs: Long): tk.glucodata.chart.MainSensorOwnership =
        runCatching { bridge?.getMainSensorOwnershipForNotification(startTimeMs) }
            .onFailure { Log.stack(TAG, "getMainSensorOwnership failed", it) }
            .getOrNull() ?: tk.glucodata.chart.MainSensorOwnership.NONE

    @JvmStatic
    fun getHistoryForSensor(
        sensorSerial: String?,
        startTimeMs: Long,
        isMmol: Boolean,
    ): List<GlucosePoint>? {
        val resolvedSerial = sensorSerial?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { bridge?.getHistoryForNotificationForSensor(resolvedSerial, startTimeMs, isMmol) }
            .onFailure { Log.stack(TAG, "getHistoryForSensor failed", it) }
            .getOrNull()
    }
}
