package tk.glucodata.drivers.icanhealth

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One history record as received from the sensor. Immutable, so a batch being written cannot change. */
internal data class ICanHealthHistoryReading(
    val sequenceNumber: Int,
    val timestampMs: Long = 0L,
    val glucoseMgdl: Float = Float.NaN,
    val rawCurrent: Float = Float.NaN,
    val temperatureC: Float = Float.NaN,
) {
    val isStorable: Boolean
        get() = timestampMs > 0L && glucoseMgdl.isFinite() && glucoseMgdl > 0f
}

/**
 * Records on their way from a RACP transfer into the history store.
 *
 * They go through two stages. [incoming][record] belongs to the transfer in progress: an interrupted
 * transfer drops it, because the covered edge has not moved and the next request asks for the same
 * range again. [Unsaved][completeTransfer] records come from a finished transfer and leave only once
 * they are written: live readings move the covered edge on meanwhile, so the sensor would never be
 * asked for them again.
 *
 * A record keeps the timestamp it was first given, so writing it again lands on the same row.
 *
 * The records belong to the sensor the driver is on. A write and what it reports run as one step that
 * [changeSensor] and [clear] wait for, so neither can take the sensor away halfway.
 */
internal class ICanHealthHistoryImport {
    class Batch(
        /** Oldest first; never empty. */
        val records: List<ICanHealthHistoryReading>,
        /** True when an earlier attempt at any of these records failed. */
        val isRetry: Boolean,
        internal val generation: Int,
    )

    /** Held over a write. Taken before this object's own lock, which is never held while waiting for it. */
    private val writing = ReentrantLock()
    private val incoming = LinkedHashMap<Int, ICanHealthHistoryReading>()
    private val unsaved = HashMap<Int, ICanHealthHistoryReading>()
    private var generation = 0
    private var persisting = false
    private var persistAgain = false

    /** Failed attempts since the last write that succeeded. */
    @get:Synchronized
    var consecutiveFailures = 0
        private set

    @get:Synchronized
    val incomingCount: Int
        get() = incoming.size

    @Synchronized
    fun hasUnsaved(): Boolean = unsaved.isNotEmpty()

    @Synchronized
    fun record(
        sequenceNumber: Int,
        sampleTimeMs: Long,
        glucoseMgdl: Float,
        rawCurrent: Float,
        temperatureC: Float,
    ) {
        val known = incoming[sequenceNumber] ?: ICanHealthHistoryReading(sequenceNumber)
        incoming[sequenceNumber] = known.copy(
            timestampMs = if (known.timestampMs > 0L) known.timestampMs else sampleTimeMs,
            glucoseMgdl = glucoseMgdl.takeIf { it.isFinite() && it > 0f } ?: known.glucoseMgdl,
            rawCurrent = rawCurrent.takeIf { it.isFinite() && it > 0f } ?: known.rawCurrent,
            temperatureC = temperatureC.takeIf { it.isFinite() && it > 0f } ?: known.temperatureC,
        )
    }

    @Synchronized
    fun discardIncoming() {
        incoming.clear()
    }

    /** The sensor is gone: once this returns, nothing received from it is being written or will be. */
    fun clear() = writing.withLock { forget() }

    /** Run [change], which moves the driver to another id, while nothing is being written. */
    fun changeSensor(isSameSensor: Boolean, change: () -> Unit) = writing.withLock {
        if (!isSameSensor) forget()
        change()
    }

    @Synchronized
    private fun forget() {
        generation++
        incoming.clear()
        unsaved.clear()
        consecutiveFailures = 0
    }

    /** The transfer finished: what it delivered is now owed to the store. */
    @Synchronized
    fun completeTransfer() {
        for (reading in incoming.values) {
            if (!reading.isStorable) continue
            val owed = unsaved[reading.sequenceNumber]
            unsaved[reading.sequenceNumber] =
                if (owed == null) reading else reading.copy(timestampMs = owed.timestampMs)
        }
        incoming.clear()
    }

    /** The same record arrived live and is stored under [timestampMs]; a later write must hit that row. */
    @Synchronized
    fun alignWithLiveReading(sequenceNumber: Int, timestampMs: Long) {
        val owed = unsaved[sequenceNumber] ?: return
        if (timestampMs > 0L) unsaved[sequenceNumber] = owed.copy(timestampMs = timestampMs)
    }

    /**
     * Write every unsaved record and return how many were stored.
     *
     * Receiving goes on while [write] blocks on the database. [onPersisted] runs after a write that
     * succeeded and never otherwise. A second caller does not wait: it leaves its records to the write
     * already running, which picks them up when it is done.
     */
    fun persist(write: (Batch) -> Boolean, onPersisted: (Batch) -> Unit): Int {
        var savedCount = 0
        while (claim()) {
            var refused = false
            var again = false
            try {
                writing.withLock {
                    // Taken only now: whatever sensor the driver is on, it stays there until the lock is released.
                    val batch = nextBatch() ?: return@withLock
                    val stored = runCatching { write(batch) }.getOrDefault(false)
                    refused = !stored
                    if (finish(batch, stored)) {
                        savedCount += batch.records.size
                        onPersisted(batch)
                    }
                }
            } finally {
                again = release()
            }
            if (refused || !again) break
        }
        return savedCount
    }

    @Synchronized
    private fun claim(): Boolean {
        persistAgain = persisting
        if (persisting) return false
        persisting = true
        return true
    }

    /** True when another caller left records behind meanwhile. */
    @Synchronized
    private fun release(): Boolean {
        persisting = false
        return persistAgain.also { persistAgain = false }
    }

    @Synchronized
    private fun nextBatch(): Batch? {
        if (unsaved.isEmpty()) return null
        return Batch(
            records = unsaved.values.sortedWith(
                compareBy<ICanHealthHistoryReading> { it.timestampMs }.thenBy { it.sequenceNumber }
            ),
            isRetry = consecutiveFailures > 0,
            generation = generation,
        )
    }

    /** True when [batch] was stored and still belongs to this sensor. */
    @Synchronized
    private fun finish(batch: Batch, stored: Boolean): Boolean {
        if (batch.generation != generation) return false
        if (!stored) {
            consecutiveFailures++
            return false
        }
        consecutiveFailures = 0
        // A record replaced while the write ran still has to be written.
        batch.records.forEach { if (unsaved[it.sequenceNumber] === it) unsaved.remove(it.sequenceNumber) }
        return true
    }
}
