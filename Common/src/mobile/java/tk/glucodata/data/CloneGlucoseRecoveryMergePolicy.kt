package tk.glucodata.data

import tk.glucodata.CloneRecoveryMode

internal object CloneGlucoseRecoveryMergePolicy {
    fun deletedReadingsToInsert(
        mode: CloneRecoveryMode,
        rows: List<DeletedHistoryReading>,
        existingReadingKeys: Set<Pair<String, Long>>,
    ): List<DeletedHistoryReading> = if (mode == CloneRecoveryMode.FULL_HISTORY) {
        rows
    } else {
        rows.filterNot { (it.sensorSerial to it.timestamp) in existingReadingKeys }
    }

    fun readingsToInsert(
        rows: List<HistoryReading>,
        deletedReadingKeys: Set<Pair<String, Long>>,
        recoverySource: String,
    ): List<HistoryReading> = rows
        .filterNot { (it.sensorSerial to it.timestamp) in deletedReadingKeys }
        .map { it.copy(source = recoverySource) }

    fun uncertaintyToInsert(
        rows: List<ReadingUncertainty>,
        existingReadingMinuteKeys: Set<Pair<String, Long>>,
    ): List<ReadingUncertainty> = rows.filter {
        (it.sensorSerial to it.timestamp) in existingReadingMinuteKeys
    }

    fun displayToInsert(
        rows: List<ReadingDisplay>,
        existingReadingKeys: Set<Pair<String, Long>>,
    ): List<ReadingDisplay> = rows.filter {
        (it.sensorSerial to it.timestamp) in existingReadingKeys
    }
}
