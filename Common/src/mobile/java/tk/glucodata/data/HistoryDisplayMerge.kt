package tk.glucodata.data

import tk.glucodata.SensorIdentity

internal object HistoryDisplayMerge {
    private const val SENSOR_MINUTE_BUCKET_MS = 60_000L
    private const val OVERLAP_PADDING_MS = 5L * 60L * 1000L
    private const val COVERAGE_SEGMENT_GAP_MS = 15L * 60L * 1000L

    private data class LogicalSensorBucket(
        val sensorId: String,
        val bucket: Long
    )

    private class PreferredMatchResolver(preferredSerial: String?) {
        private val canonicalPreferred = SensorIdentity.resolveAppSensorId(preferredSerial)
        private val matchCache = HashMap<String, Boolean>()

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
        val filtered = applyPreferredOverlapDominance(coalesced, resolver)
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

    private fun applyPreferredOverlapDominance(
        readings: List<HistoryReading>,
        resolver: PreferredMatchResolver
    ): List<HistoryReading> {
        val preferredReadings = readings
            .filter { resolver.matches(it.sensorSerial) }
        if (preferredReadings.isEmpty()) return readings

        val coverageSegments = buildCoverageSegments(preferredReadings)
        val preferredMinuteBuckets = preferredReadings
            .mapTo(HashSet(preferredReadings.size)) { it.timestamp / SENSOR_MINUTE_BUCKET_MS }

        val filtered = ArrayList<HistoryReading>(readings.size)
        var segmentIndex = 0
        for (reading in readings) {
            if (resolver.matches(reading.sensorSerial)) {
                filtered.add(reading)
                continue
            }
            if (isImportedSerial(reading.sensorSerial)) {
                val bucket = reading.timestamp / SENSOR_MINUTE_BUCKET_MS
                if (bucket !in preferredMinuteBuckets) {
                    filtered.add(reading)
                }
                continue
            }
            while (segmentIndex < coverageSegments.size &&
                reading.timestamp > coverageSegments[segmentIndex].last + OVERLAP_PADDING_MS
            ) {
                segmentIndex++
            }
            val covered = segmentIndex < coverageSegments.size &&
                reading.timestamp >= coverageSegments[segmentIndex].start - OVERLAP_PADDING_MS &&
                reading.timestamp <= coverageSegments[segmentIndex].last + OVERLAP_PADDING_MS
            if (!covered) {
                filtered.add(reading)
            }
        }
        return filtered
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
