package tk.glucodata.data

import tk.glucodata.GlucosePoint
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.chart.MainSensorOwnership

/**
 * Phone implementation of [HistoryRepositoryBridge] (plan P1/Q1). Delegates to
 * the static blocking methods on [HistoryRepository]; kept as its own object so
 * the repository class stays untouched.
 */
object MobileHistoryRepositoryBridge : HistoryRepositoryBridge {
    override fun resetBackfillFlag() = HistoryRepository.resetBackfillFlag()

    override fun storeAidexReadingAsync(timestamp: Long, valueMmol: Float, source: Int) =
        HistoryRepository.storeReadingAsync(timestamp, valueMmol, source)

    override fun storeCurrentReadingAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
    ) = HistoryRepository.storeReadingAsync(timestamp, valueMgdl, rawValueMgdl, rate, sensorSerial)

    override fun storeCurrentReadingWithSourceAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String,
        source: String,
    ) = HistoryRepository.storeReadingWithSourceAsync(
        timestamp,
        valueMgdl,
        rawValueMgdl,
        rate,
        sensorSerial,
        source,
    )

    override fun storeHistoryBatchAsync(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ) = HistoryRepository.storeHistoryBatchAsync(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl)

    override fun storeHistoryBatchBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ): Boolean = HistoryRepository.storeHistoryBatchBlocking(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl)

    override fun storeHistoryBatchWithSourceBlocking(
        sensorSerial: String,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        source: String,
    ): Boolean = HistoryRepository.storeHistoryBatchWithSourceBlocking(
        sensorSerial,
        timestamps,
        valuesMgdl,
        rawValuesMgdl,
        source,
    )

    override fun getLatestTimestampForSensorBlocking(sensorSerial: String): Long =
        HistoryRepository.getLatestTimestampForSensorBlocking(sensorSerial)

    override fun getHistoryTimestampsForSensorBlocking(
        sensorSerial: String,
        startTime: Long,
        endTime: Long,
    ): LongArray? = HistoryRepository.getHistoryTimestampsForSensorBlocking(sensorSerial, startTime, endTime)

    override fun deleteReadingsForSensorAfterBlocking(
        sensorSerial: String,
        timestampExclusive: Long,
    ): Int = HistoryRepository.deleteReadingsForSensorAfterBlocking(sensorSerial, timestampExclusive)

    override fun getMainSensorOwnershipForNotification(startTimeMs: Long): MainSensorOwnership =
        HistoryRepository.getMainSensorOwnershipForNotification(startTimeMs)

    override fun getHistoryForNotificationForSensor(
        sensorSerial: String,
        startTimeMs: Long,
        isMmol: Boolean,
    ): List<GlucosePoint> = HistoryRepository.getHistoryForNotificationForSensor(sensorSerial, startTimeMs, isMmol)
}
