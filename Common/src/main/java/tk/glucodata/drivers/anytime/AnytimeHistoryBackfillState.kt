package tk.glucodata.drivers.anytime

import tk.glucodata.drivers.VirtualGlucoseSensorBridge

internal class AnytimeHistoryCaughtUpCooldown(
    private val cooldownMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var caughtUpNextRequestId: Int = -1
    private var caughtUpAtMs: Long = 0L

    @Synchronized
    fun markCaughtUp(nextRequestId: Int) {
        caughtUpNextRequestId = nextRequestId.coerceAtLeast(0)
        caughtUpAtMs = nowMs()
    }

    @Synchronized
    fun clearIfNewerData(glucoseId: Int) {
        val caughtUpNext = caughtUpNextRequestId
        if (caughtUpNext >= 0 && glucoseId >= caughtUpNext) {
            clear()
        }
    }

    @Synchronized
    fun clear() {
        caughtUpNextRequestId = -1
        caughtUpAtMs = 0L
    }

    @Synchronized
    fun shouldSuppressBackfill(
        startId: Int,
        stopBeforeId: Int,
        reason: String,
        lastGlucoseId: Int,
    ): Boolean {
        if (reason.startsWith("user-requested")) return false
        val caughtUpNext = caughtUpNextRequestId
        if (caughtUpNext < 0 || stopBeforeId != Int.MAX_VALUE) return false
        if (startId < caughtUpNext) return false
        if (lastGlucoseId >= caughtUpNext) return false
        val elapsed = nowMs() - caughtUpAtMs
        return elapsed in 0 until cooldownMs
    }
}

internal fun sanitizeRestoredGlucoseId(
    persistedLastId: Int,
    cachedRawMaxId: Int,
    rollbackThreshold: Int,
): Int {
    if (persistedLastId < 0) return persistedLastId
    if (cachedRawMaxId < 0) return persistedLastId
    return if (liveIdLooksRolledBack(
            liveId = cachedRawMaxId,
            previousMaxId = persistedLastId,
            rollbackThreshold = rollbackThreshold,
        )
    ) {
        cachedRawMaxId
    } else {
        persistedLastId
    }
}

internal fun liveIdLooksRolledBack(
    liveId: Int,
    previousMaxId: Int,
    rollbackThreshold: Int,
): Boolean =
    liveId >= 0 &&
            previousMaxId >= 0 &&
            liveId + rollbackThreshold.coerceAtLeast(0) < previousMaxId

internal data class AnytimePendingHistoryRoomImport(
    val glucoseId: Int,
    val source: AnytimeAlgorithm.Source,
    val priority: Int,
    val rawMgdl: Float,
    val temperatureC: Float,
    val reading: VirtualGlucoseSensorBridge.Reading,
)

internal class AnytimeHistoryRoomImportBuffer {
    private val pending = LinkedHashMap<Int, AnytimePendingHistoryRoomImport>()
    private val seenPriorities = HashMap<Int, Int>()

    @Synchronized
    fun queue(sampleMs: Long, result: AnytimeAlgorithm.Result): Boolean {
        val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
        val priority = sourcePriority(result.source)
        return queueInternal(
            sampleMs = sampleMs,
            glucoseId = result.glucoseId,
            source = result.source,
            priority = priority,
            glucoseMgdl = result.mgdl,
            rawMgdl = raw,
            temperatureC = result.temperatureC,
        )
    }

    @Synchronized
    fun queueRawOnly(sampleMs: Long, result: AnytimeAlgorithm.Result): Boolean {
        val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
        if (!raw.isFinite() || raw <= 0f) return false
        return queueInternal(
            sampleMs = sampleMs,
            glucoseId = result.glucoseId,
            source = result.source,
            priority = RAW_ONLY_PRIORITY,
            glucoseMgdl = Float.NaN,
            rawMgdl = raw,
            temperatureC = result.temperatureC,
        )
    }

    private fun queueInternal(
        sampleMs: Long,
        glucoseId: Int,
        source: AnytimeAlgorithm.Source,
        priority: Int,
        glucoseMgdl: Float,
        rawMgdl: Float,
        temperatureC: Float,
    ): Boolean {
        val seenPriority = seenPriorities[glucoseId]
        val pendingPriority = pending[glucoseId]?.priority
        val bestKnownPriority = maxOf(seenPriority ?: NO_PRIORITY, pendingPriority ?: NO_PRIORITY)
        if (bestKnownPriority >= priority) return false

        pending[glucoseId] = AnytimePendingHistoryRoomImport(
            glucoseId = glucoseId,
            source = source,
            priority = priority,
            rawMgdl = rawMgdl,
            temperatureC = temperatureC,
            reading = VirtualGlucoseSensorBridge.Reading(
                timestampMs = sampleMs,
                glucoseMgdl = glucoseMgdl,
                rawMgdl = rawMgdl,
            ),
        )
        return true
    }

    @Synchronized
    fun clear() {
        pending.clear()
        seenPriorities.clear()
    }

    @Synchronized
    fun drain(): List<AnytimePendingHistoryRoomImport> {
        if (pending.isEmpty()) return emptyList()
        return pending.values.toList().also {
            pending.clear()
        }
    }

    @Synchronized
    fun markImported(imports: List<AnytimePendingHistoryRoomImport>) {
        imports.forEach { item ->
            val previousPriority = seenPriorities[item.glucoseId] ?: 0
            if (item.priority > previousPriority) {
                seenPriorities[item.glucoseId] = item.priority
            }
        }
    }

    private fun sourcePriority(source: AnytimeAlgorithm.Source): Int = when (source) {
        AnytimeAlgorithm.Source.NATIVE -> 2
        AnytimeAlgorithm.Source.LINEAR -> 1
    }

    private companion object {
        private const val NO_PRIORITY = -1
        private const val RAW_ONLY_PRIORITY = 0
    }
}

// ---- CT5-specific history state ----
//
// CT5 Auto glucose is computed by the transmitter, so CT5 history exists only to
// fill real gaps in the stored series. It has nothing to do with the contiguous
// raw prefix that the CT3/CT4 vendor JNI algorithm needs, and must never trigger
// a 0..current replay just because `rawAlgorithmWindow` is empty.

/**
 * Tag carried by history write frames.
 *
 * Built and matched through these helpers on purpose: the tag once gained a
 * `,count=N` suffix while the guards still tested `startsWith("pullGlucose(backfill)")`,
 * so a failed history write stopped being recognised as optional and tore down a
 * healthy GATT instead. Keep construction and matching in one place.
 */
internal const val ANYTIME_BACKFILL_WRITE_TAG_PREFIX = "pullGlucose(backfill"

internal fun anytimeBackfillWriteTag(count: Int): String = "pullGlucose(backfill,count=$count)"

internal fun isAnytimeBackfillWriteTag(tag: String): Boolean =
    tag.startsWith(ANYTIME_BACKFILL_WRITE_TAG_PREFIX)

/**
 * True when a CT5 history request may go out: the streaming session has held
 * together long enough to be worth risking an optional write on, and the single
 * GATT write slot is free.
 */
internal fun isCt5HistoryLinkSettled(
    streamingSinceMs: Long,
    nowMs: Long,
    settleMs: Long,
    writeInFlight: Boolean,
): Boolean {
    if (writeInFlight) return false
    if (streamingSinceMs <= 0L) return false
    return nowMs - streamingSinceMs >= settleMs
}

/** Inclusive-start / exclusive-end glucose id range. */
internal data class AnytimeIdRange(val fromId: Int, val stopBeforeId: Int) {
    val count: Int get() = (stopBeforeId - fromId).coerceAtLeast(0)
    val isEmpty: Boolean get() = count <= 0

    override fun toString(): String =
        if (isEmpty) "<empty>" else "$fromId..${stopBeforeId - 1}"
}

/**
 * Missing ids between the newest CT5 record we already hold and a newly arrived
 * live id. Returns null when nothing is missing (the live id is the expected
 * next one, a repeat, or older).
 *
 *   last stored/live = 288, next live = 291  ->  289..290
 *
 * `maxRecords` caps one repair so a very long outage cannot turn a reconnect
 * into a full-session replay; the newest part of the gap wins, and anything
 * older than the cap simply stays a gap.
 */
internal fun ct5ReconnectGap(
    highestKnownId: Int,
    liveId: Int,
    maxRecords: Int,
): AnytimeIdRange? {
    if (highestKnownId < 0 || liveId <= 0) return null
    if (liveId <= highestKnownId + 1) return null
    val stopBeforeId = liveId
    val fromId = maxOf(highestKnownId + 1, stopBeforeId - maxRecords.coerceAtLeast(1))
    val range = AnytimeIdRange(fromId, stopBeforeId)
    return range.takeUnless { it.isEmpty }
}

/**
 * What is still owed from a persisted gap.
 *
 * Only ids **inside** the range count as progress. This is the whole point:
 * a newer live id says nothing about whether the hole was filled. Measuring
 * progress with the global "highest id seen" watermark silently discarded every
 * gap the instant the next 3-minute push arrived — a hole was detected, recorded,
 * held for the settle window, and then dropped without ever being requested.
 *
 * @param highestImportedInRange highest id imported that lies inside the gap,
 *        or -1 when none of it has been filled yet.
 */
internal fun ct5RemainingGap(
    pendingFromId: Int,
    pendingStopBeforeId: Int,
    highestImportedInRange: Int,
): AnytimeIdRange? {
    if (pendingFromId < 0 || pendingStopBeforeId <= pendingFromId) return null
    val resumeFrom = if (highestImportedInRange in pendingFromId until pendingStopBeforeId) {
        highestImportedInRange + 1
    } else {
        pendingFromId
    }
    if (resumeFrom >= pendingStopBeforeId) return null
    return AnytimeIdRange(resumeFrom, pendingStopBeforeId)
}

/**
 * Shrink an outstanding gap as batches land, so a second interruption resumes
 * mid-range instead of restarting the repair. Ids outside the gap are ignored.
 */
internal fun ct5GapAfterBatch(
    pendingFromId: Int,
    pendingStopBeforeId: Int,
    maxImportedId: Int,
): AnytimeIdRange? = ct5RemainingGap(pendingFromId, pendingStopBeforeId, maxImportedId)

/**
 * Merge a newly found gap into the outstanding one. Ranges are merged rather than
 * queued, so a flaky stretch cannot grow an unbounded backlog; `maxRecords` keeps
 * the merged range from turning into a session replay.
 */
internal fun ct5MergeGap(
    currentFromId: Int,
    currentStopBeforeId: Int,
    newFromId: Int,
    newStopBeforeId: Int,
    maxRecords: Int,
): AnytimeIdRange? {
    if (newFromId < 0 || newStopBeforeId <= newFromId) {
        return if (currentFromId < 0 || currentStopBeforeId <= currentFromId) {
            null
        } else {
            AnytimeIdRange(currentFromId, currentStopBeforeId)
        }
    }
    val hasCurrent = currentFromId >= 0 && currentStopBeforeId > currentFromId
    val from = if (hasCurrent) minOf(currentFromId, newFromId) else newFromId
    val stopBefore = if (hasCurrent) maxOf(currentStopBeforeId, newStopBeforeId) else newStopBeforeId
    val capped = maxOf(from, stopBefore - maxRecords.coerceAtLeast(1))
    if (capped >= stopBefore) return null
    return AnytimeIdRange(capped, stopBefore)
}

/**
 * True when a shared loss-of-signal alarm should be ignored because the current
 * streaming session is too young to have received its next scheduled push.
 *
 * The alarm is armed from the previous reading's timestamp, so after a recovery
 * it can fire against a link that is working perfectly and has simply not
 * reached the sensor's next 3-minute slot.
 */
internal fun shouldDeferLossOfSignalReconnect(
    streamingSinceMs: Long,
    nowMs: Long,
    graceMs: Long,
): Boolean {
    if (streamingSinceMs <= 0L) return false
    val age = nowMs - streamingSinceMs
    return age in 0 until graceMs
}

/**
 * Per-GATT-session health of the CT5 0x37 pull.
 *
 * A history timeout is transient. The 2026-08-17 hardware trace timed out on the
 * `60..74` request three seconds before the connection itself died with GATT
 * status 147 — the pull did not become unsupported at id 60, the link was going
 * away. So timeouts get bounded retries within a connection and the whole thing
 * resets on the next successful GATT session; nothing is ever disabled for the
 * lifetime of the manager or the process.
 */
internal class AnytimeCt5HistoryHealth(
    private val maxTimeoutsPerConnection: Int,
    private val retryBackoffMs: Long,
) {
    private var timeoutsThisConnection: Int = 0
    private var pausedThisConnection: Boolean = false

    @Synchronized
    fun onGattSessionStarted() {
        timeoutsThisConnection = 0
        pausedThisConnection = false
    }

    /** Any successful series response proves the pull path still works. */
    @Synchronized
    fun onSeriesReceived() {
        timeoutsThisConnection = 0
    }

    @Synchronized
    fun isPausedForThisConnection(): Boolean = pausedThisConnection

    @Synchronized
    fun timeoutCount(): Int = timeoutsThisConnection

    /**
     * @return backoff before the next attempt, or null when this connection has
     *         used up its retries and history should wait for a new GATT session.
     */
    @Synchronized
    fun onTimeout(): Long? {
        timeoutsThisConnection++
        if (timeoutsThisConnection >= maxTimeoutsPerConnection) {
            pausedThisConnection = true
            return null
        }
        return retryBackoffMs * timeoutsThisConnection
    }
}

/**
 * One-line accounting for a CT5 history batch, so a repair logs
 * "CT5 history 289..290 received: 0 existing, 2 inserted, 0 warm-up" instead of
 * one line per already-present point.
 */
internal class AnytimeCt5HistoryBatchTally {
    var inserted: Int = 0
        private set
    var existing: Int = 0
        private set
    var warmup: Int = 0
        private set
    var liveRace: Int = 0
        private set

    fun countInserted() { inserted++ }
    fun countExisting() { existing++ }
    fun countWarmup() { warmup++ }
    fun countLiveRace() { liveRace++ }

    val total: Int get() = inserted + existing + warmup + liveRace

    fun describe(firstId: Int, lastId: Int): String = buildString {
        append("CT5 history ").append(firstId).append("..").append(lastId)
        append(" received: ").append(existing).append(" existing, ")
        append(inserted).append(" inserted, ")
        append(warmup).append(" warm-up/no-glucose")
        if (liveRace > 0) append(", ").append(liveRace).append(" superseded by live")
    }
}
