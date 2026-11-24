package tk.glucodata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tk.glucodata.HistoryWriteListener
import tk.glucodata.HistoryWriteOutcome
import tk.glucodata.Log

/**
 * Writes history batches behind the caller's back, without losing any.
 *
 * Backfill arrives one BLE notification at a time — roughly sixteen readings each,
 * ten times a second on a fast sensor. Writing a Room transaction per notification
 * meant a delete-range plus an insert ten times a second for the whole length of the
 * connection. Batches are therefore gathered per sensor and written once the burst
 * pauses, or once enough readings have piled up that waiting no longer pays.
 *
 *  - A reading stays here, and in [HistoryBatchJournal], until the write that stored
 *    it has returned. A failed write keeps it for the next attempt; attempts back off
 *    and are limited, so a broken database is neither spun on nor retried for ever.
 *  - A reading accepted by a process that then dies is written by the next one.
 *  - Per sensor and minute the latest submission wins, as it does in the table. The
 *    writes that do not come through here ([exclusive]) take their place in the same
 *    order: what they store is not overwritten by an older reading still waiting
 *    ([supersede]), and what they delete does not come back ([discard]).
 *
 * Lock order: [exclusive], then the database transaction, then [lock]. Listeners and
 * [onStored] run outside all three, on [scope].
 */
internal class HistoryBatchWriter(
    private val scope: CoroutineScope,
    /** Null while there is nowhere to keep it yet; asked again on the next use. */
    private val openJournal: () -> HistoryBatchJournal?,
    private val coalesceMs: Long = 400L,
    private val maxBuffered: Int = 512,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Four weeks of a one-minute sensor, and more than any backfill holds at once. */
    private val maxRetainedPerSensor: Int = 45_000,
    /** Writes one sensor's readings in one transaction. Throwing counts as a failed write. */
    private val store: suspend (sensorSerial: String, readings: List<HistoryReading>) -> Stored,
    private val onStored: suspend (Stored) -> Unit,
) {
    class Stored(
        val outcome: HistoryRepository.BucketReplaceOutcome,
        /** The rows as written, with their stored provenance. */
        val rows: List<HistoryReading> = emptyList(),
    )

    /** One submission, waiting for all of its minutes. */
    private class Ack(val listener: HistoryWriteListener) {
        var open = 0
        var anyPersisted = false
        var anyFailed = false
    }

    private class Entry(val reading: HistoryReading, val ticket: Long, var acks: List<Ack>, val recovered: Boolean) {
        var attempts = 0

        /** Waited longer than a healthy write ever does, so live writes may have passed it. */
        val retained get() = recovered || attempts > 0
    }

    private class SensorQueue {
        /** By minute. Holds a reading until its write returned, also while that is running. */
        val entries = LinkedHashMap<Long, Entry>()
        val nudge = Channel<Unit>(Channel.CONFLATED)
        var worker: Job? = null
        var failures = 0
    }

    private val writeOrder = Mutex()
    private val lock = Any()
    private val queues = HashMap<String, SensorQueue>()
    private var journal: HistoryBatchJournal? = null
    private var journalStale = false
    private var lastTicket = 0L

    /**
     * Accepts [readings], all of [sensorSerial]. They are in the journal when this
     * returns, so a caller may move its own cursor past them; only [listener] says
     * they are stored.
     */
    fun submit(
        sensorSerial: String,
        readings: List<HistoryReading>,
        listener: HistoryWriteListener? = null,
    ): HistoryWriteOutcome {
        val batch = HistoryBucketReplacement.collapseReadings(readings, MINUTE_MS)
        if (sensorSerial.isBlank() || batch.isEmpty()) return HistoryWriteOutcome.SKIPPED
        val settled = Settlements()
        synchronized(lock) {
            openJournalLocked()
            val ticket = ++lastTicket
            val ack = listener?.let(::Ack)
            journalLocked(batch)
            val queue = queues.getOrPut(sensorSerial) { SensorQueue() }
            for (reading in batch) {
                ack?.let { it.open++ }
                val minute = minuteOf(reading)
                val replaced = queue.entries.remove(minute)
                // Whoever waits for the reading this one replaces now waits for this one.
                val acks = listOfNotNull(ack) + replaced?.takeAcks().orEmpty()
                queue.entries[minute] = Entry(reading, ticket, acks, recovered = false)
            }
            dropOverflowLocked(sensorSerial, queue, settled)
            compactJournalLocked()
            ensureWorkerLocked(sensorSerial, queue)
        }
        settled.announceLater()
        return HistoryWriteOutcome.QUEUED
    }

    /** Picks up what an earlier process accepted and did not store. */
    fun resume() = synchronized(lock) { openJournalLocked() }

    /**
     * A point in the submission order. A direct write takes it when it is asked for,
     * before it waits for anything, and hands it to [supersede] once it has committed.
     */
    fun ticket(): Long = synchronized(lock) {
        openJournalLocked()
        lastTicket
    }

    /** Runs a direct write (or delete) with no queued write running. Not reentrant, and never inside a transaction. */
    suspend fun <T> exclusive(block: suspend () -> T): T = writeOrder.withLock { block() }

    /**
     * A direct write has stored [timestamps] of [sensorSerial]: readings submitted before
     * [ticket] for those minutes are older than what is in the table now. Call inside [exclusive].
     */
    fun supersede(sensorSerial: String, timestamps: Collection<Long>, ticket: Long, retainedOnly: Boolean = false) {
        val settled = Settlements()
        synchronized(lock) {
            val queue = queues[sensorSerial]?.takeIf { it.entries.isNotEmpty() } ?: return
            for (timestamp in timestamps) {
                val minute = timestamp / MINUTE_MS
                val entry = queue.entries[minute] ?: continue
                if (entry.ticket > ticket || (retainedOnly && !entry.retained)) continue
                queue.entries.remove(minute)
                settled.add(entry, HistoryWriteOutcome.PERSISTED)
            }
            if (settled.entries > 0) syncJournalLocked()
        }
        settled.announceLater()
    }

    /**
     * Drops waiting readings whose rows were just deleted: those submitted up to [ticket], of
     * [sensorSerials] (null for every sensor), that [matches]. Call inside [exclusive].
     */
    fun discard(
        sensorSerials: Collection<String>?,
        ticket: Long,
        matches: (HistoryReading) -> Boolean = { true },
    ) {
        val settled = Settlements()
        synchronized(lock) {
            for ((serial, queue) in queues) {
                if (sensorSerials != null && serial !in sensorSerials) continue
                val entries = queue.entries.values.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (entry.ticket > ticket || !matches(entry.reading)) continue
                    entries.remove()
                    settled.add(entry, HistoryWriteOutcome.SKIPPED)
                }
            }
            if (settled.entries > 0) syncJournalLocked()
        }
        settled.announceLater()
    }

    /** Tries every waiting reading once, now. Not inside [exclusive]. */
    suspend fun flushPending() {
        val sensors = synchronized(lock) { queues.entries.map { it.key to it.value } }
        for ((serial, queue) in sensors) flushRound(serial, queue)
    }

    internal fun pendingCount(sensorSerial: String): Int =
        synchronized(lock) { queues[sensorSerial]?.entries?.size ?: 0 }

    private fun ensureWorkerLocked(sensorSerial: String, queue: SensorQueue) {
        if (queue.worker?.isActive == true) {
            if (queue.failures == 0 && queue.entries.size >= maxBuffered) queue.nudge.trySend(Unit)
            return
        }
        queue.worker = scope.launch { drain(sensorSerial, queue) }
    }

    private suspend fun drain(sensorSerial: String, queue: SensorQueue) {
        while (true) {
            val waitMs = synchronized(lock) {
                if (queue.entries.isEmpty()) {
                    queue.worker = null
                    return
                }
                when {
                    queue.failures > 0 -> retryDelaysMs[(queue.failures - 1).coerceAtMost(retryDelaysMs.lastIndex)]
                    queue.entries.size >= maxBuffered -> 0L
                    else -> coalesceMs
                }
            }
            withTimeoutOrNull(waitMs) { queue.nudge.receive() }
            flushRound(sensorSerial, queue)
        }
    }

    /** Writes what was waiting when it was called, stopping at the first failed write. */
    private suspend fun flushRound(sensorSerial: String, queue: SensorQueue) {
        val until = synchronized(lock) { lastTicket }
        try {
            while (true) {
                val settled = Settlements()
                val stored = writeOrder.withLock {
                    // Chosen only once the write may start, so it cannot include a reading
                    // a direct write replaced or deleted in the meantime.
                    val chunk = synchronized(lock) { nextChunkLocked(queue, until) }
                    if (chunk.isEmpty()) return
                    val stored = try {
                        store(sensorSerial, chunk.map { it.reading })
                    } catch (e: CancellationException) {
                        // Cancelled ourselves: the readings stay where they are. Otherwise it
                        // was only the write that was cancelled, and that has failed.
                        currentCoroutineContext().ensureActive()
                        Log.stack(TAG, "store", e)
                        Stored(HistoryRepository.BucketReplaceOutcome.FAILED)
                    } catch (e: Exception) {
                        Log.stack(TAG, "store", e)
                        Stored(HistoryRepository.BucketReplaceOutcome.FAILED)
                    }
                    synchronized(lock) { settleLocked(sensorSerial, queue, chunk, stored.outcome, settled) }
                    stored
                }
                settled.announce()
                if (stored.outcome == HistoryRepository.BucketReplaceOutcome.STORED) {
                    // The rows are in the table whatever happens here.
                    try {
                        onStored(stored)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.stack(TAG, "onStored", e)
                    }
                }
                if (stored.outcome == HistoryRepository.BucketReplaceOutcome.FAILED) return
            }
        } finally {
            synchronized(lock) { if (journalStale) syncJournalLocked() }
        }
    }

    /** The least tried first and by themselves, so readings that keep failing do not hold up the rest. */
    private fun nextChunkLocked(queue: SensorQueue, until: Long): List<Entry> {
        val due = queue.entries.values.filter { it.ticket <= until }
        val fewest = due.minOfOrNull { it.attempts } ?: return emptyList()
        return due.filter { it.attempts == fewest }.sortedBy { it.reading.timestamp }.take(WRITE_CHUNK)
    }

    private fun settleLocked(
        sensorSerial: String,
        queue: SensorQueue,
        chunk: List<Entry>,
        outcome: HistoryRepository.BucketReplaceOutcome,
        settled: Settlements,
    ) {
        val failed = outcome == HistoryRepository.BucketReplaceOutcome.FAILED
        queue.failures = if (failed) queue.failures + 1 else 0
        var givenUp = 0
        for (entry in chunk) {
            val minute = minuteOf(entry.reading)
            // Submitted again while this write ran: the newer reading is still to be written.
            if (queue.entries[minute] !== entry) continue
            if (failed) {
                if (++entry.attempts < maxAttempts) continue
                givenUp++
            }
            queue.entries.remove(minute)
            journalStale = true
            settled.add(
                entry,
                when (outcome) {
                    HistoryRepository.BucketReplaceOutcome.STORED -> HistoryWriteOutcome.PERSISTED
                    HistoryRepository.BucketReplaceOutcome.NOTHING_TO_STORE -> HistoryWriteOutcome.SKIPPED
                    HistoryRepository.BucketReplaceOutcome.FAILED -> HistoryWriteOutcome.FAILED
                },
            )
        }
        if (failed) {
            Log.w(
                TAG,
                "write of ${chunk.size} history readings failed for $sensorSerial " +
                    "(${queue.failures} in a row); ${queue.entries.size} kept, $givenUp given up"
            )
        }
    }

    private fun dropOverflowLocked(sensorSerial: String, queue: SensorQueue, settled: Settlements) {
        val excess = queue.entries.size - maxRetainedPerSensor
        if (excess <= 0) return
        val oldest = queue.entries.entries.iterator()
        repeat(excess) {
            settled.add(oldest.next().value, HistoryWriteOutcome.FAILED)
            oldest.remove()
        }
        journalStale = true
        Log.e(TAG, "too many unwritten history readings for $sensorSerial; dropped the $excess oldest")
    }

    private fun openJournalLocked() {
        if (journal != null) return
        val opened = runCatching(openJournal).getOrNull() ?: return
        journal = opened
        val recovered = opened.load()
        if (recovered.isEmpty()) {
            // Also clears away a file nothing readable was left in.
            if (opened.lineCount > 0) opened.replaceWith(emptyList())
            return
        }
        Log.i(TAG, "resuming ${recovered.size} history readings an earlier run did not store")
        val ticket = ++lastTicket
        for ((serial, readings) in recovered.groupBy { it.sensorSerial }) {
            val queue = queues.getOrPut(serial) { SensorQueue() }
            // In file order, so the latest submission of a minute wins here too.
            val latest = LinkedHashMap<Long, HistoryReading>()
            for (reading in readings) {
                latest.remove(minuteOf(reading))
                latest[minuteOf(reading)] = reading
            }
            for ((minute, reading) in latest) {
                if (minute !in queue.entries) {
                    queue.entries[minute] = Entry(reading, ticket, emptyList(), recovered = true)
                }
            }
            ensureWorkerLocked(serial, queue)
        }
    }

    private fun journalLocked(readings: List<HistoryReading>) {
        val journal = journal ?: return
        if (!journal.append(readings)) {
            journalStale = true
            Log.w(TAG, "${readings.size} history readings are only held in memory until they are stored")
        }
    }

    /** Resubmitting minutes that cannot be written must not grow the file without end. */
    private fun compactJournalLocked() {
        val journal = journal ?: return
        if (journal.lineCount <= JOURNAL_SLACK_LINES) return
        val waiting = queues.values.sumOf { it.entries.size }
        if (journal.lineCount > 2 * waiting + JOURNAL_SLACK_LINES) syncJournalLocked()
    }

    private fun syncJournalLocked() {
        val journal = journal ?: return
        journalStale = !journal.replaceWith(queues.values.flatMap { queue -> queue.entries.values.map { it.reading } })
    }

    private fun Entry.takeAcks(): List<Ack> = acks.also { acks = emptyList() }

    /** Outcomes collected under the lock, announced after it is released. */
    private inner class Settlements {
        private val done = ArrayList<Ack>()
        var entries = 0
            private set

        fun add(entry: Entry, outcome: HistoryWriteOutcome) {
            entries++
            for (ack in entry.takeAcks()) {
                when (outcome) {
                    HistoryWriteOutcome.PERSISTED -> ack.anyPersisted = true
                    HistoryWriteOutcome.FAILED -> ack.anyFailed = true
                    else -> Unit
                }
                if (--ack.open == 0) done += ack
            }
        }

        /** For a caller that may be holding [exclusive], or locks a listener wants. */
        fun announceLater() {
            if (done.isNotEmpty()) scope.launch { announce() }
        }

        fun announce() {
            for (ack in done) {
                val outcome = when {
                    ack.anyFailed -> HistoryWriteOutcome.FAILED
                    ack.anyPersisted -> HistoryWriteOutcome.PERSISTED
                    else -> HistoryWriteOutcome.SKIPPED
                }
                runCatching { ack.listener.onHistoryWriteSettled(outcome) }
                    .onFailure { Log.stack(TAG, "listener", it) }
            }
        }
    }

    companion object {
        private const val TAG = "HistoryBatchWriter"
        private const val MINUTE_MS = 60_000L
        private const val WRITE_CHUNK = 1_000
        private const val JOURNAL_SLACK_LINES = 4_000

        val DEFAULT_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L, 60_000L, 300_000L)

        /** About eight hours at the longest delay: outlasts a full disk someone clears the same day. */
        const val DEFAULT_MAX_ATTEMPTS = 100

        private fun minuteOf(reading: HistoryReading): Long = reading.timestamp / MINUTE_MS
    }
}
