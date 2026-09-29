package tk.glucodata.drivers.aidex.native.ble

import tk.glucodata.drivers.aidex.native.data.HistoryMerge
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes

internal object AiDexHistoryPolicy {
    // Also the ceiling for how far ahead of wall clock a stored reading may legitimately sit,
    // which AiDexStreamingPolicy uses to bound its no-stream watchdog stretch.
    internal const val OFFSET_TIMESTAMP_FUTURE_SLACK_MS = 5L * 60_000L
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
        rawCacheEmpty: Boolean = false,
        wearDays: Int? = null,
    ): DownloadPlan {
        if (briefStart == 0 && rawStart == 0 && newest == 0) {
            return DownloadPlan(
                rawNextIndex = 0,
                briefNextIndex = 0,
                downloadStartIndex = 0,
                action = InitialAction.COMPLETE_EMPTY,
            )
        }

        // The raw cursor is persisted on every 0x23 page, the brief one only once 0x24 starts, so
        // a download cut short during 0x23 leaves raw > 0 with brief == 0. That 0 is a real
        // cursor — normalizePersistedIndex turns it into briefStart — and not a "never set"
        // sentinel: treating it as one sent 0x24 over rows whose raw half was never cached here,
        // every one of them merged to glucose 0 and dropped by the range filter, while the brief
        // cursor moved past them for good.
        // The second case: 0x23 already caught up (raw == newest + 1), but its rows are no longer
        // in this process's cache — a teardown or a restart cleared it — while 0x24 still has to
        // merge them. Without the rewind every brief row merges to glucose 0 and is dropped, and the
        // brief cursor moves past those minutes for good. The wear-duration guard keeps an expired
        // sensor's brief tail (5b88ece7) out of it.
        val effectiveRawNextIndex = if (
            persistedRawNextIndex > persistedBriefNextIndex && (
                persistedRawNextIndex <= newest ||
                    (rawCacheEmpty && persistedBriefNextIndex <= newest &&
                        isWithinWearDuration(persistedBriefNextIndex, wearDays))
                )
        ) {
            persistedBriefNextIndex
        } else {
            persistedRawNextIndex
        }

        val rawNextIndex = normalizePersistedIndex(
            persistedIndex = effectiveRawNextIndex,
            startIndex = rawStart,
            newest = newest,
        )
        val normalizedBriefNextIndex = normalizePersistedIndex(
            persistedIndex = persistedBriefNextIndex,
            startIndex = briefStart,
            newest = newest,
        )
        // A persisted brief cursor ahead of a raw one that still has 0x23 pages to fetch: the old
        // empty-0x23-page path ran 0x24 past minutes 0x23 never delivered. Left there, 0x24 would
        // restart past the rows this 0x23 pass caches, and those minutes would never be merged.
        // Not below briefStart: 0x24 has no rows there, and normalizePersistedIndex never goes below it.
        val briefNextIndex = if (rawNextIndex <= newest && normalizedBriefNextIndex > rawNextIndex) {
            maxOf(rawNextIndex, briefStart)
        } else {
            normalizedBriefNextIndex
        }

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

    enum class EmptyRawPageAction {
        RETRY_RAW,
        START_BRIEF,
        COMPLETE,
    }

    /**
     * The first minute 0x24 must not reach. A 0x24 row is merged against the 0x23 row cached for
     * the same minute, so the brief cursor stays below the raw one and never passes newest + 1.
     * With newest <= 0 there is no raw bound, and the cap is HEAD's `brief <= newest`.
     */
    fun briefCursorCap(rawNextIndex: Int, newestOffset: Int): Int =
        if (newestOffset > 0) minOf(rawNextIndex, newestOffset + 1) else newestOffset + 1

    /**
     * An empty 0x23 page. HEAD started 0x24 whenever brief <= newest, so the brief cursor ran
     * past minutes 0x23 never delivered: rows after the last match took the carried-forward
     * glucose, rows before any match merged to 0 and were dropped. Now the same raw page is asked
     * once more; after that, 0x24 runs only up to [briefCursorCap].
     */
    fun decideEmptyRawPage(
        rawNextIndex: Int,
        briefNextIndex: Int,
        newestOffset: Int,
        emptyPageRetried: Boolean,
    ): EmptyRawPageAction = when {
        newestOffset > 0 && rawNextIndex <= newestOffset && !emptyPageRetried -> EmptyRawPageAction.RETRY_RAW
        briefNextIndex < briefCursorCap(rawNextIndex, newestOffset) -> EmptyRawPageAction.START_BRIEF
        else -> EmptyRawPageAction.COMPLETE
    }

    /** The brief cursor after a 0x24 page. HEAD used `lastRowOffset + 1`, which could pass the cap. */
    fun nextBriefCursor(lastRowOffset: Int, cap: Int): Int = minOf(lastRowOffset + 1, cap)

    /**
     * The 0x24 rows below [cap], in page order. Applied before the merge, so a row 0x23 has not
     * delivered is neither stored nor used as the fallback for a later row.
     */
    fun <T> rowsBelowCap(rows: List<T>, cap: Int, offsetOf: (T) -> Int): List<T> =
        rows.filter { offsetOf(it) < cap }

    enum class HistoryStoreRejection {
        INVALID,
        NONPOSITIVE_OFFSET,
        OFFSET_TOO_LARGE,
        PAST_WEAR,
        PAST_NEWEST,
        LIVE_DEDUPE,
        SENTINEL,
        OUT_OF_RANGE,
        FUTURE,
    }

    /**
     * Why storeHistoryEntries drops a merged history row, or null to store it. The checks and
     * their order are those of storeHistoryEntries' loop. There is no warmup gate: a valid
     * reading in the first minutes is stored.
     */
    fun historyStoreRejection(
        offsetMinutes: Int,
        glucoseMgDl: Float,
        isValid: Boolean,
        sensorStartMs: Long,
        nowMs: Long,
        wearDays: Int?,
        historyNewestOffset: Int,
        liveOffsetCutoff: Int,
    ): HistoryStoreRejection? {
        if (!isValid) return HistoryStoreRejection.INVALID
        if (offsetMinutes <= 0) return HistoryStoreRejection.NONPOSITIVE_OFFSET
        if (offsetMinutes.toLong() > HistoryMerge.MAX_OFFSET_DAYS * 24L * 60L) {
            return HistoryStoreRejection.OFFSET_TOO_LARGE
        }
        if (!isWithinWearDuration(offsetMinutes, wearDays)) return HistoryStoreRejection.PAST_WEAR
        // Past the ring's write head: uninitialized or corrupt rows.
        if (historyNewestOffset > 0 && offsetMinutes > historyNewestOffset) {
            return HistoryStoreRejection.PAST_NEWEST
        }
        if (shouldSkipHistoryEntryForLiveDedupe(offsetMinutes, liveOffsetCutoff)) {
            return HistoryStoreRejection.LIVE_DEDUPE
        }
        val glucoseInt = glucoseMgDl.toInt()
        if (glucoseInt >= AiDexOpcodes.SENTINEL_GLUCOSE && glucoseMgDl > 0f) return HistoryStoreRejection.SENTINEL
        if (glucoseInt !in HistoryMerge.MIN_VALID_GLUCOSE_MGDL..HistoryMerge.MAX_VALID_GLUCOSE_MGDL) {
            return HistoryStoreRejection.OUT_OF_RANGE
        }
        // 2 minutes of slack, not OFFSET_TIMESTAMP_FUTURE_SLACK_MS.
        if (sensorStartMs + offsetMinutes.toLong() * 60_000L > nowMs + 120_000L) {
            return HistoryStoreRejection.FUTURE
        }
        return null
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

    fun shouldStampDirectLiveForDedupe(trustedOffsetMinutes: Int?): Boolean {
        return trustedOffsetMinutes != null && trustedOffsetMinutes > 0
    }

    /**
     * Never snap [liveOffsetCutoff] to the sensor's newest history offset just because
     * some live frame was shown. An untrusted live sits on wall clock and does not
     * cover [newest]; snapping would drop that history minute. Cutoff comes only from
     * a trusted live offset.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldSnapLiveCutoffToNewest(
        liveOffsetCutoff: Int,
        lastDirectLiveReadingTimeMs: Long,
        newest: Int,
        trustedOffsetWasUsed: Boolean,
    ): Boolean = false

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
        // Clock stepped behind the barrier. Clamping elapsed to 0 made any ring older than the
        // grace look like pre-reset residue, and the caller then persisted the cursors past
        // minutes it never stored.
        if (nowMs < resetRequestedAtMs) return false
        val elapsedMinutes = ((nowMs - resetRequestedAtMs) / 60_000L).toInt()
        return newestOffsetMinutes > elapsedMinutes + graceMinutes.coerceAtLeast(0)
    }

    /**
     * Cursor after a 0x23 page. A page whose first minute is past the offset we asked for does
     * not cover that hole: the first such reply leaves the cursor where it was so the same
     * offset is asked again. A second reply that still starts later is the sensor's real page,
     * and [proposedNext] (one past the page) is accepted so the download cannot spin.
     *
     * @return next cursor, and whether this reply was the first unaligned one (retry still owed).
     */
    fun rawCursorAfterUnalignedPage(
        requestedOffset: Int,
        firstEntryOffset: Int,
        proposedNext: Int,
        gapAlreadyRetried: Boolean,
    ): Pair<Int, Boolean> {
        if (firstEntryOffset <= requestedOffset) return proposedNext to false
        if (!gapAlreadyRetried) return requestedOffset to true
        return proposedNext to false
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
