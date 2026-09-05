package tk.glucodata.drivers.icanhealth

/**
 * A hole between two accepted live sequence numbers.
 *
 * [endSequenceExclusive] is the newer live sequence, which is already stored and must remain an
 * overlap. Only cadence-aligned records inside the window are eligible for gap repair.
 */
internal data class ICanHealthSequenceGap(
    val startSequence: Int,
    val endSequenceExclusive: Int,
    val intervalMinutes: Int,
) {
    val readingCount: Int
        get() = (endSequenceExclusive - startSequence) / intervalMinutes

    fun contains(sequenceNumber: Int): Boolean =
        sequenceNumber >= startSequence &&
            sequenceNumber < endSequenceExclusive &&
            (sequenceNumber - startSequence) % intervalMinutes == 0

    fun isFullyCoveredBy(sequenceNumbers: Set<Int>): Boolean {
        var sequence = startSequence
        while (sequence < endSequenceExclusive) {
            if (sequence !in sequenceNumbers) return false
            sequence += intervalMinutes
        }
        return true
    }

    fun mergedWith(other: ICanHealthSequenceGap): ICanHealthSequenceGap {
        if (intervalMinutes != other.intervalMinutes) return other
        return ICanHealthSequenceGap(
            startSequence = minOf(startSequence, other.startSequence),
            endSequenceExclusive = maxOf(endSequenceExclusive, other.endSequenceExclusive),
            intervalMinutes = intervalMinutes,
        )
    }
}

/** Pure history decisions shared by the BLE manager and JVM regression tests. */
internal object ICanHealthHistoryPolicy {
    fun liveGapBetween(
        previousSequence: Int,
        currentSequence: Int,
        readingIntervalMinutes: Int,
    ): ICanHealthSequenceGap? {
        val missedReadings = ICanHealthConstants.missedReadingsBetween(
            previousSequence = previousSequence,
            currentSequence = currentSequence,
            readingIntervalMinutes = readingIntervalMinutes,
        )
        if (missedReadings < 1) return null
        return ICanHealthSequenceGap(
            startSequence = previousSequence + readingIntervalMinutes,
            endSequenceExclusive = currentSequence,
            intervalMinutes = readingIntervalMinutes,
        )
    }

    fun automaticGlucoseHistoryStartSequence(
        readingIntervalMinutes: Int,
        currentSequence: Int,
        latestKnownSequence: Int,
        pendingLiveGap: ICanHealthSequenceGap?,
    ): Int? {
        if (pendingLiveGap != null &&
            pendingLiveGap.startSequence >= readingIntervalMinutes &&
            pendingLiveGap.startSequence < currentSequence
        ) {
            return pendingLiveGap.startSequence
        }
        if (latestKnownSequence >= readingIntervalMinutes) {
            val nextMissingSequence = latestKnownSequence + readingIntervalMinutes
            return nextMissingSequence.takeIf { currentSequence >= it }
        }
        return readingIntervalMinutes
    }

    fun shouldSkipHistoryOverlap(
        sequenceNumber: Int,
        sampleTimeMs: Long,
        coveredSequence: Int,
        coveredTimestampMs: Long,
        pendingLiveGap: ICanHealthSequenceGap?,
        ignoreCoveredSequence: Boolean,
    ): Boolean {
        // A scalar tail cannot describe an interior hole. Prefer the explicitly observed hole
        // before applying either edge-based overlap check.
        if (pendingLiveGap?.contains(sequenceNumber) == true) return false
        if (!ignoreCoveredSequence &&
            coveredSequence >= 0 &&
            sequenceNumber >= 0 &&
            sequenceNumber <= coveredSequence
        ) {
            return true
        }
        return coveredTimestampMs > 0L && sampleTimeMs > 0L && sampleTimeMs <= coveredTimestampMs
    }
}
