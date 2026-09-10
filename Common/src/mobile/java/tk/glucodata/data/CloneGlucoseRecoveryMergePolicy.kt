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

    /**
     * Keeps only the recorded main values whose minute this store actually has a
     * reading for.
     *
     * Matched on the **minute**, not on an exact reading timestamp: a recorded
     * main value is keyed by the minute it describes (see [ReadingDisplay]),
     * while a reading carries the millisecond it arrived. Comparing the two
     * directly matches almost nothing, which would silently drop every imported
     * record instead of guarding against orphans.
     */
    fun displayToInsert(
        rows: List<ReadingDisplay>,
        existingReadingMinutes: Set<Long>,
    ): List<ReadingDisplay> = rows.filter {
        ReadingDisplay.minuteOf(it.timestamp) in existingReadingMinutes
    }
}
