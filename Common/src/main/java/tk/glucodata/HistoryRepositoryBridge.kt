package tk.glucodata

/**
 * The mobile-only Room history repository, as the shared code needs it
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [HistoryRepositoryAccess] then behaves as the old absent class did.
 */
interface HistoryRepositoryBridge {
    fun resetBackfillFlag()

    fun storeAidexReadingAsync(timestamp: Long, valueMmol: Float, source: Int)

    fun storeCurrentReadingAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
    )

    fun storeCurrentReadingWithSourceAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
        source: String,
    )

    fun storeHistoryBatchAsync(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    )

    fun storeHistoryBatchBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ): Boolean

    fun storeHistoryBatchWithSourceBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        source: String,
    ): Boolean

    fun getLatestTimestampForSensorBlocking(sensorSerial: String): Long

    /**
     * Timestamps already stored, or null when the question could not be asked at
     * all. Null must reach the caller: collapsing it into an empty array made the
     * Ottai driver re-pull a whole history on every reconnect (713631f9).
     */
    fun getHistoryTimestampsForSensorBlocking(
        sensorSerial: String,
        startTime: Long,
        endTime: Long,
    ): LongArray?

    fun deleteReadingsForSensorAfterBlocking(sensorSerial: String, timestampExclusive: Long): Int

    /**
     * Who is the main sensor, minute by minute, since [startTimeMs] — from the
     * same record the dashboard reads.
     */
    fun getMainSensorOwnershipForNotification(startTimeMs: Long): tk.glucodata.chart.MainSensorOwnership

    fun getHistoryForNotificationForSensor(
        sensorSerial: String,
        startTimeMs: Long,
        isMmol: Boolean,
    ): List<GlucosePoint>?
}
