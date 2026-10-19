package tk.glucodata.drivers.aidex.native.ble

internal object AiDexHistoryPolicy {
    private const val OFFSET_TIMESTAMP_FUTURE_SLACK_MS = 5L * 60_000L
    private const val POST_RESET_HISTORY_RESIDUE_GRACE_MINUTES = 30

    enum class InitialAction {
        COMPLETE_EMPTY,
        REQUEST_RAW,
        REQUEST_BRIEF,
        COMPLETE_ALREADY_CAUGHT_UP,
    }

    data class DownloadPlan(
        val rawNextIndex: Int,
        val briefNextIndex: Int,
        val downloadStartIndex: Int,
        val action: InitialAction,
        val requestOffset: Int? = null,
    )

    fun planInitialDownload(
        briefStart: Int,
        rawStart: Int,
        newest: Int,
        persistedRawNextIndex: Int,
        persistedBriefNextIndex: Int,
        sourceRepairOffset: Int? = null,
    ): DownloadPlan {
        if (briefStart == 0 && rawStart == 0 && newest == 0) {
            return DownloadPlan(
                rawNextIndex = 0,
                briefNextIndex = 0,
                downloadStartIndex = 0,
                action = InitialAction.COMPLETE_EMPTY,
            )
        }

        val repair = sourceRepairOffset?.takeIf { it >= maxOf(1, rawStart, briefStart) && it <= newest }
        val rawCursor = repair?.let { minOf(it, persistedRawNextIndex) } ?: persistedRawNextIndex
        val briefCursor = repair?.let { minOf(it, persistedBriefNextIndex) } ?: persistedBriefNextIndex
        val effectiveRawNextIndex = if (
            rawCursor <= newest &&
            briefCursor > 0 &&
            rawCursor > briefCursor
        ) {
            briefCursor
        } else {
            rawCursor
        }

        val rawNextIndex = normalizePersistedIndex(
            persistedIndex = effectiveRawNextIndex,
            startIndex = rawStart,
            newest = newest,
        )
        val briefNextIndex = normalizePersistedIndex(
            persistedIndex = briefCursor,
            startIndex = briefStart,
            newest = newest,
        )

        return when {
            rawNextIndex <= newest -> DownloadPlan(
                rawNextIndex = rawNextIndex,
                briefNextIndex = briefNextIndex,
                downloadStartIndex = rawNextIndex,
                action = InitialAction.REQUEST_RAW,
                requestOffset = rawNextIndex,
            )
            briefNextIndex <= newest -> DownloadPlan(
                rawNextIndex = rawNextIndex,
                briefNextIndex = briefNextIndex,
                downloadStartIndex = rawNextIndex,
                action = InitialAction.REQUEST_BRIEF,
                requestOffset = briefNextIndex,
            )
            else -> DownloadPlan(
                rawNextIndex = rawNextIndex,
                briefNextIndex = briefNextIndex,
                downloadStartIndex = rawNextIndex,
                action = InitialAction.COMPLETE_ALREADY_CAUGHT_UP,
            )
        }
    }

    /** Match Room/journal wall-clock minutes, including activation's seconds. */
    fun sourceRepairOffset(timeMs: Long, sensorStartMs: Long, briefStart: Int, rawStart: Int, newest: Int): Int? {
        if (sensorStartMs <= 0L || timeMs <= 0L) return null
        val offset = timeMs / 60_000L - sensorStartMs / 60_000L
        return offset.takeIf { it >= maxOf(1, briefStart, rawStart) && it <= newest.toLong() }?.toInt()
    }

    fun shouldEmitCatchUpBroadcast(
        lastHistoryNewestGlucose: Float,
        lastHistoryNewestOffset: Int,
        liveOffsetCutoff: Int,
    ): Boolean {
        return lastHistoryNewestGlucose > 0f &&
            lastHistoryNewestOffset > 0 &&
            (liveOffsetCutoff == 0 || lastHistoryNewestOffset > liveOffsetCutoff)
    }

    fun shouldSkipHistoryEntryForLiveDedupe(
        entryOffsetMinutes: Int,
        liveOffsetCutoff: Int,
    ): Boolean {
        if (liveOffsetCutoff <= 0) return false

        // The current connection logic only guarantees that the live pipeline
        // has already stored the exact first live minute seen before history
        // starts. Reconnect history can legitimately contain newer offsets that
        // were not yet emitted as direct F003, so skipping >= cutoff drops
        // real backfill rows. Only skip the exact live-backed offset.
        return entryOffsetMinutes == liveOffsetCutoff
    }

    fun wearDurationMinutes(wearDays: Int?): Long? {
        if (wearDays == null || wearDays <= 0) return null
        return wearDays.toLong() * 24L * 60L
    }

    fun isWithinWearDuration(
        offsetMinutes: Int,
        wearDays: Int?,
    ): Boolean {
        if (offsetMinutes < 0) return false
        val wearMinutes = wearDurationMinutes(wearDays) ?: return true
        return offsetMinutes.toLong() < wearMinutes
    }

    fun shouldQuarantinePostResetHistoryRange(
        newestOffsetMinutes: Int,
        resetRequestedAtMs: Long,
        nowMs: Long,
        graceMinutes: Int = POST_RESET_HISTORY_RESIDUE_GRACE_MINUTES,
    ): Boolean {
        if (newestOffsetMinutes <= 0 || resetRequestedAtMs <= 0L || nowMs <= 0L) return false
        val elapsedMinutes = ((nowMs - resetRequestedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        return newestOffsetMinutes > elapsedMinutes + graceMinutes.coerceAtLeast(0)
    }

    fun resolveOffsetBackedTimestampMs(
        observedAtMs: Long,
        sensorStartMs: Long,
        offsetMinutes: Int?,
    ): Long {
        if (offsetMinutes == null || offsetMinutes <= 0 || sensorStartMs <= 0L) {
            return observedAtMs
        }
        val sampleTimeMs = sensorStartMs + (offsetMinutes.toLong() * 60_000L)
        return if (sampleTimeMs > 0L && sampleTimeMs <= observedAtMs + OFFSET_TIMESTAMP_FUTURE_SLACK_MS) {
            sampleTimeMs
        } else {
            observedAtMs
        }
    }

    fun shouldAcceptRealtimeTimestamp(
        candidateTimeMs: Long,
        latestAcceptedTimeMs: Long,
    ): Boolean {
        if (candidateTimeMs <= 0L) return false
        return latestAcceptedTimeMs <= 0L || candidateTimeMs >= latestAcceptedTimeMs
    }

    private fun normalizePersistedIndex(
        persistedIndex: Int,
        startIndex: Int,
        newest: Int,
    ): Int {
        var normalized = maxOf(persistedIndex, startIndex)
        if (normalized > newest + 10) {
            normalized = startIndex
        }
        return normalized
    }
}
