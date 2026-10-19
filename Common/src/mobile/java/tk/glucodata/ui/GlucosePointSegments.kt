package tk.glucodata.ui

import tk.glucodata.SensorIdentity

internal object GlucosePointSegments {
    private const val DEFAULT_GAP_THRESHOLD_MS = ChartGap.THRESHOLD_MS

    fun split(
        points: List<GlucosePoint>,
        gapThresholdMs: Long = DEFAULT_GAP_THRESHOLD_MS
    ): List<List<GlucosePoint>> {
        if (points.isEmpty()) return emptyList()

        val segments = ArrayList<List<GlucosePoint>>()
        var current = ArrayList<GlucosePoint>()
        var lastTimestamp = Long.MIN_VALUE
        // The last serial that actually identifies a sensor, which is not always
        // the previous point's: a row no sensor owns is transparent here, so the
        // run it lands in keeps comparing against the sensor either side of it.
        // Comparing with the immediate predecessor instead would let one unowned
        // row hide a genuine swap, since neither of its two boundaries differs.
        var lastOwnedSerial: String? = null

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                segments.add(current)
                current = ArrayList()
            }
        }

        for (point in points) {
            val sensorChanged = current.isNotEmpty() && sensorChanged(lastOwnedSerial, point.sensorSerial)
            val gapExceeded = current.isNotEmpty() &&
                lastTimestamp != Long.MIN_VALUE &&
                (point.timestamp - lastTimestamp) > gapThresholdMs

            if (sensorChanged || gapExceeded) {
                flushCurrent()
                lastOwnedSerial = null
            }

            current.add(point)
            lastTimestamp = point.timestamp
            normalize(point.sensorSerial)
                ?.takeUnless(::isUnowned)
                ?.let { lastOwnedSerial = it }
        }

        flushCurrent()
        return segments
    }

    /**
     * Whether the line should break between two readings because they came from
     * different sensors.
     *
     * A row that no sensor owns — an `unknown` serial from before the serial was
     * known, an imported one — is not a different sensor, and treating it as one
     * is visibly wrong. The merge deliberately keeps such rows where they fill a
     * hole in the live sensor's stream (see
     * `HistoryDisplayMergeTests.mergeReadings_keepsImportedRowsInsidePreferredCoverageWhenTheyFillGaps`),
     * and the chart then broke the line on both sides of each one. Since it only
     * breaks on elapsed time after 17 minutes, a one-minute filler rendered as
     * two holes with an orphaned fragment between them: missing data drawn at
     * precisely the point where the data is complete.
     *
     * So an unowned row joins whatever run it lands in, which is the same rule
     * [RowDeltaIndex] already applies to them — rows the app cannot attribute
     * belong to every sensor's bucket rather than to one of their own.
     */
    private fun sensorChanged(previous: String?, current: String?): Boolean {
        val previousNormalized = normalize(previous)
        val currentNormalized = normalize(current)
        if (previousNormalized == null && currentNormalized == null) return false
        // No owned serial yet in this run, or an unowned row arriving: nothing to
        // disagree with, so the run continues.
        if (previousNormalized == null) return false
        if (currentNormalized == null) return true
        if (isUnowned(currentNormalized)) return false
        return !SensorIdentity.matches(previousNormalized, currentNormalized)
    }

    private fun isUnowned(sensorSerial: String): Boolean =
        tk.glucodata.data.HistoryRepository.isImportedHistorySerial(sensorSerial)

    private fun normalize(sensorSerial: String?): String? {
        return sensorSerial?.trim()?.takeIf { it.isNotEmpty() }
    }
}
