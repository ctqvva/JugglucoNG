package tk.glucodata.data

import tk.glucodata.GlucoseReadingSource
import tk.glucodata.SensorIdentity
import kotlin.math.abs

internal object HistoryDisplayMerge {
    private const val SENSOR_MINUTE_BUCKET_MS = 60_000L
    private const val OVERLAP_PADDING_MS = 5L * 60L * 1000L
    private const val COVERAGE_SEGMENT_GAP_MS = 15L * 60L * 1000L
    private const val REPLICA_TIMESTAMP_TOLERANCE_MS = 30_000L
    private const val REPLICA_VALUE_TOLERANCE_MGDL = 1f


    private data class LogicalSensorBucket(
        val sensorId: String,
        val bucket: Long
    )

    private data class StableDeliveryCollapse(
        val readings: List<HistoryReading>,
        val winners: Set<HistoryReading>,
    )

    private class PreferredMatchResolver(preferredSerial: String?) {
        private val canonicalPreferred = SensorIdentity.resolveAppSensorId(preferredSerial)
        private val matchCache = HashMap<String, Boolean>()

        /**
         * Whether a preferred sensor was named at all — not whether it has any
         * readings here. The distinction matters: a selected main that has gone
         * quiet still names a preference, and ranking the other sensors against
         * each other is exactly what that case needs.
         */
        val hasPreferred: Boolean get() = canonicalPreferred != null

        fun matches(sensorSerial: String?): Boolean {
            val preferred = canonicalPreferred ?: return false
            val raw = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return matchCache.getOrPut(raw) {
                SensorIdentity.matches(raw, preferred)
            }
        }
    }

    private class LogicalSensorResolver {
        private val cache = HashMap<String, String?>()

        fun resolve(sensorSerial: String?): String? {
            val raw = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return cache.getOrPut(raw) {
                SensorIdentity.resolveRoomStorageSensorId(raw)
                    ?: SensorIdentity.resolveAppSensorId(raw)
                    ?: raw
            }
        }
    }

    /**
     * [readings] must be the **whole** stored timeline, not a slice of it.
     *
     * [applyPreferredOverlapDominance] decides which sensor wins by building the
     * preferred sensor's coverage segments out of the rows it is handed. Given a
     * window that contains none of that sensor's rows — any span older than the
     * current sensor — it suppresses nothing and every other sensor draws raw;
     * given a window that clips those rows, the segments are truncated and rows
     * are dropped at the seam. Both were shipped once, as a bounded dashboard
     * query, and showed up as a foreign sensor's line on the chart and a hole in
     * the middle of it.
     *
     * Slice after merging, never before.
     */
    fun mergeReadings(
        readings: List<HistoryReading>,
        preferredSerial: String?
    ): List<HistoryReading> {
        if (readings.isEmpty()) return emptyList()

        val resolver = PreferredMatchResolver(preferredSerial)
        val logicalResolver = LogicalSensorResolver()
        if (hasSingleStoredSensor(readings)) {
            return collapseSingleLogicalSensorBuckets(readings, resolver)
        }
        singleLogicalSensorId(readings, logicalResolver)?.let {
            return collapseSingleLogicalSensorBuckets(readings, resolver)
        }

        val coalesced = collapseLogicalSensorBuckets(readings, resolver, logicalResolver)
        // A Nightscout pull and Clone can persist the same physical reading under
        // different virtual sensors. Pick its first delivery before applying the
        // currently selected sensor's coverage, otherwise changing receivers
        // retroactively relabels the entire visible timeline.
        val stableDeliveries = collapseEquivalentDeliveries(coalesced, resolver)
        val filtered = applyPreferredOverlapDominance(
            stableDeliveries.readings,
            resolver,
            logicalResolver,
            stableDeliveries.winners,
        )
        val merged = ArrayList<HistoryReading>(filtered.size)
        var currentTimestamp = Long.MIN_VALUE
        var currentBest: HistoryReading? = null

        for (reading in filtered) {
            if (currentBest == null || reading.timestamp != currentTimestamp) {
                currentBest?.let(merged::add)
                currentTimestamp = reading.timestamp
                currentBest = reading
            } else {
                currentBest = choosePreferred(currentBest, reading, resolver)
            }
        }

        currentBest?.let(merged::add)
        return merged
    }

    private fun hasSingleStoredSensor(readings: List<HistoryReading>): Boolean {
        if (readings.size < 2) return true
        val firstSensor = readings.first().sensorSerial
        for (index in 1 until readings.size) {
            if (readings[index].sensorSerial != firstSensor) return false
        }
        return true
    }

    private fun singleLogicalSensorId(
        readings: List<HistoryReading>,
        logicalResolver: LogicalSensorResolver
    ): String? {
        var firstSensorId: String? = null
        for (reading in readings) {
            val sensorId = logicalResolver.resolve(reading.sensorSerial) ?: return null
            if (firstSensorId == null) {
                firstSensorId = sensorId
            } else if (sensorId != firstSensorId) {
                return null
            }
        }
        return firstSensorId
    }

    private fun collapseSingleLogicalSensorBuckets(
        readings: List<HistoryReading>,
        resolver: PreferredMatchResolver
    ): List<HistoryReading> {
        if (readings.size < 2) return readings
        if (!hasAdjacentMinuteBucketDuplicates(readings)) return readings

        val collapsed = ArrayList<HistoryReading>(readings.size)
        var currentBucket = Long.MIN_VALUE
        var currentBest: HistoryReading? = null

        for (reading in readings) {
            val bucket = reading.timestamp / SENSOR_MINUTE_BUCKET_MS
            if (currentBest == null || bucket != currentBucket) {
                currentBest?.let(collapsed::add)
                currentBucket = bucket
                currentBest = reading
            } else {
                currentBest = choosePreferred(currentBest, reading, resolver)
            }
        }

        currentBest?.let(collapsed::add)
        return collapsed
    }

    private fun hasAdjacentMinuteBucketDuplicates(readings: List<HistoryReading>): Boolean {
        var previousBucket = readings.first().timestamp / SENSOR_MINUTE_BUCKET_MS
        for (index in 1 until readings.size) {
            val bucket = readings[index].timestamp / SENSOR_MINUTE_BUCKET_MS
            if (bucket == previousBucket) {
                return true
            }
            previousBucket = bucket
        }
        return false
    }

    private fun collapseLogicalSensorBuckets(
        readings: List<HistoryReading>,
        resolver: PreferredMatchResolver,
        logicalResolver: LogicalSensorResolver
    ): List<HistoryReading> {
        val byBucket = LinkedHashMap<LogicalSensorBucket, HistoryReading>(readings.size)
        for (reading in readings) {
            val sensorSerial = reading.sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val resolvedSensorId = logicalResolver.resolve(sensorSerial) ?: continue
            val key = LogicalSensorBucket(
                sensorId = resolvedSensorId,
                bucket = reading.timestamp / SENSOR_MINUTE_BUCKET_MS
            )
            val existing = byBucket[key]
            byBucket[key] = if (existing == null) reading else choosePreferred(existing, reading, resolver)
        }
        return byBucket.values.sortedBy { it.timestamp }
    }

    private fun collapseEquivalentDeliveries(
        readings: List<HistoryReading>,
        resolver: PreferredMatchResolver,
    ): StableDeliveryCollapse {
        if (readings.size < 2) return StableDeliveryCollapse(readings, emptySet())

        val collapsed = ArrayList<HistoryReading>(readings.size)
        val winners = LinkedHashSet<HistoryReading>()
        for (candidate in readings) {
            var equivalentIndex = -1
            for (index in collapsed.lastIndex downTo 0) {
                val existing = collapsed[index]
                if (candidate.timestamp - existing.timestamp > REPLICA_TIMESTAMP_TOLERANCE_MS) break
                if (sameReplicatedDelivery(existing, candidate)) {
                    equivalentIndex = index
                    break
                }
            }
            if (equivalentIndex < 0) {
                collapsed.add(candidate)
                continue
            }
            val existing = collapsed[equivalentIndex]
            val winner = chooseFirstDelivery(existing, candidate, resolver)
            collapsed[equivalentIndex] = winner
            winners.remove(existing)
            winners.remove(candidate)
            winners.add(winner)
        }
        return StableDeliveryCollapse(collapsed.sortedBy(HistoryReading::timestamp), winners)
    }

    private fun sameReplicatedDelivery(left: HistoryReading, right: HistoryReading): Boolean {
        if (!isNightscoutClonePair(left.source, right.source)) return false
        if (abs(left.timestamp - right.timestamp) > REPLICA_TIMESTAMP_TOLERANCE_MS) return false
        val leftValue = left.value.takeIf { it.isFinite() && it > 0f }
            ?: left.rawValue.takeIf { it.isFinite() && it > 0f }
        val rightValue = right.value.takeIf { it.isFinite() && it > 0f }
            ?: right.rawValue.takeIf { it.isFinite() && it > 0f }
        return leftValue != null && rightValue != null &&
            abs(leftValue - rightValue) <= REPLICA_VALUE_TOLERANCE_MGDL
    }

    private fun isNightscoutClonePair(leftSource: String, rightSource: String): Boolean {
        val leftNightscout = leftSource == GlucoseReadingSource.NIGHTSCOUT
        val rightNightscout = rightSource == GlucoseReadingSource.NIGHTSCOUT
        val leftClone = GlucoseReadingSource.cloneTransport(leftSource) != null
        val rightClone = GlucoseReadingSource.cloneTransport(rightSource) != null
        return (leftNightscout && rightClone) || (rightNightscout && leftClone)
    }

    private fun chooseFirstDelivery(
        current: HistoryReading,
        candidate: HistoryReading,
        resolver: PreferredMatchResolver,
    ): HistoryReading {
        val currentOrder = current.firstStoredAt.takeIf { it > 0L } ?: current.id
        val candidateOrder = candidate.firstStoredAt.takeIf { it > 0L } ?: candidate.id
        if (candidateOrder != currentOrder) {
            return if (candidateOrder < currentOrder) candidate else current
        }
        if (candidate.id != current.id) {
            return if (candidate.id < current.id) candidate else current
        }
        return choosePreferred(current, candidate, resolver)
    }

    /**
     * Decides, per stretch of time, which single sensor draws the line.
     *
     * The rule used to have exactly two ranks: the preferred sensor won inside
     * its own coverage, and *everything else* passed through. That is fine while
     * the preferred sensor is the one producing readings, and wrong the moment it
     * is not — which happens whenever the user's selected main is a sensor that
     * has gone quiet while another streams. A trace with three sensors
     * (`liveMain=70D07…, selectedMain=BB368A3`) showed the failure: the preferred
     * sensor had no recent coverage, so it suppressed nothing, and two live
     * sensors' readings interleaved minute by minute. The chart starts a new
     * segment on every sensor change, so an unbroken stream drew as a row of
     * disconnected fragments — holes where there was no missing data at all.
     *
     * So the ranking is now the full list rather than a pair: preferred first,
     * then the remaining sensors by how recently each last read. A reading is
     * kept only where no higher-ranked sensor covers its moment, which means at
     * most one sensor contributes to any stretch and the line cannot alternate.
     * Where the preferred sensor does have coverage this is exactly the old
     * behaviour, since it outranks everything.
     *
     * Rows no sensor owns keep their own rule: they are not a sensor competing
     * for a stretch, they are filler, and they are dropped only where the
     * preferred sensor already has that minute.
     */
    private fun applyPreferredOverlapDominance(
        readings: List<HistoryReading>,
        resolver: PreferredMatchResolver,
        logicalResolver: LogicalSensorResolver,
        stableReplicaWinners: Set<HistoryReading> = emptySet(),
    ): List<HistoryReading> {
        // With no preferred sensor named there is nothing to rank against, and
        // the caller wants the richest reading per timestamp instead — that is
        // the final dedupe's job, so leave the list alone for it.
        if (!resolver.hasPreferred) return readings

        val preferredReadings = readings.filter { resolver.matches(it.sensorSerial) }
        val preferredMinuteBuckets = preferredReadings
            .mapTo(HashSet(preferredReadings.size)) { it.timestamp / SENSOR_MINUTE_BUCKET_MS }

        // Rank the sensors that are not the preferred one, most recently read
        // first: the sensor still streaming should own the recent stretch, and a
        // retired one should own only what predates it.
        val ownedBySensor = LinkedHashMap<String, MutableList<HistoryReading>>()
        for (reading in readings) {
            if (resolver.matches(reading.sensorSerial)) continue
            if (isImportedSerial(reading.sensorSerial)) continue
            val sensorId = logicalResolver.resolve(reading.sensorSerial) ?: continue
            ownedBySensor.getOrPut(sensorId) { ArrayList() }.add(reading)
        }
        val rankedOthers = ownedBySensor.entries
            .sortedByDescending { it.value.last().timestamp }
            .map { it.key to buildCoverageSegments(it.value) }

        val preferredSegments = buildCoverageSegments(preferredReadings)

        val filtered = ArrayList<HistoryReading>(readings.size)
        for (reading in readings) {
            if (resolver.matches(reading.sensorSerial) || reading in stableReplicaWinners) {
                filtered.add(reading)
                continue
            }
            if (isImportedSerial(reading.sensorSerial)) {
                if ((reading.timestamp / SENSOR_MINUTE_BUCKET_MS) !in preferredMinuteBuckets) {
                    filtered.add(reading)
                }
                continue
            }
            if (covers(preferredSegments, reading.timestamp)) continue

            val sensorId = logicalResolver.resolve(reading.sensorSerial)
            var outranked = false
            for ((otherId, otherSegments) in rankedOthers) {
                if (otherId == sensorId) break
                if (covers(otherSegments, reading.timestamp)) {
                    outranked = true
                    break
                }
            }
            if (!outranked) filtered.add(reading)
        }
        return filtered
    }

    /** Whether [segments] reach [timestamp], allowing the handover padding. */
    private fun covers(segments: List<LongRange>, timestamp: Long): Boolean {
        for (segment in segments) {
            if (timestamp < segment.first - OVERLAP_PADDING_MS) return false
            if (timestamp <= segment.last + OVERLAP_PADDING_MS) return true
        }
        return false
    }

    private fun isImportedSerial(sensorSerial: String?): Boolean {
        val raw = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return raw == HistoryRepository.IMPORTED_SENSOR_SERIAL ||
            raw.equals("imported", ignoreCase = true) ||
            raw.equals("unknown", ignoreCase = true)
    }

    private fun buildCoverageSegments(readings: List<HistoryReading>): List<LongRange> {
        if (readings.isEmpty()) return emptyList()

        val segments = ArrayList<LongRange>()
        var segmentStart = readings.first().timestamp
        var segmentEnd = segmentStart

        for (index in 1 until readings.size) {
            val timestamp = readings[index].timestamp
            if ((timestamp - segmentEnd) > COVERAGE_SEGMENT_GAP_MS) {
                segments.add(segmentStart..segmentEnd)
                segmentStart = timestamp
            }
            segmentEnd = timestamp
        }

        segments.add(segmentStart..segmentEnd)
        return segments
    }

    private fun choosePreferred(
        current: HistoryReading,
        candidate: HistoryReading,
        resolver: PreferredMatchResolver
    ): HistoryReading {
        val currentScore = score(current, resolver)
        val candidateScore = score(candidate, resolver)
        if (candidateScore != currentScore) {
            return if (candidateScore > currentScore) candidate else current
        }
        return if (candidate.id > current.id) candidate else current
    }

    private fun score(reading: HistoryReading, resolver: PreferredMatchResolver): Int {
        var score = 0
        if (resolver.matches(reading.sensorSerial)) {
            score += 100
        }
        if (reading.value.isFinite() && reading.value > 0f) {
            score += 10
        }
        if (reading.rawValue.isFinite() && reading.rawValue > 0f) {
            score += 5
        }
        if (reading.rate != null && reading.rate.isFinite()) {
            score += 1
        }
        return score
    }
}
