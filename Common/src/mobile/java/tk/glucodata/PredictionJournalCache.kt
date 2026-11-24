package tk.glucodata

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset

internal data class PredictionJournal(
    val entries: List<JournalEntry>,
    val presetsById: Map<Long, JournalInsulinPreset>
) {
    companion object {
        val EMPTY = PredictionJournal(emptyList(), emptyMap())
    }
}

/**
 * The journal a forecast outside the app is computed from. Background callers load it; the main
 * thread must not wait for Room, so a miss there returns null, loads in [scope] and reports
 * through [onMissedLoadReady] once the journal is there to draw with.
 */
internal class PredictionJournalCache(
    private val scope: CoroutineScope,
    private val revision: () -> Long,
    private val clock: () -> Long,
    private val onMissedLoadReady: () -> Unit,
    private val loadJournal: suspend (startMillis: Long, endMillis: Long) -> PredictionJournal
) {
    private class Loaded(
        val journal: PredictionJournal,
        val loadedAt: Long,
        val revision: Long,
        /** Order in which loads of one revision started. */
        val sequence: Long,
        val startMillis: Long,
        val endMillis: Long
    )

    @Volatile
    private var loaded: Loaded? = null
    private val publishLock = Any()
    private val sequences = AtomicLong()
    private val missed = AtomicBoolean()
    private val loadingForMiss = AtomicBoolean()

    /** The journal as it is now, with every entry of this range. */
    private fun current(startMillis: Long, endMillis: Long, maxAgeMs: Long): PredictionJournal? =
        loaded?.takeIf {
            it.revision == revision() && clock() - it.loadedAt in 0..maxAgeMs &&
                it.startMillis <= startMillis && it.endMillis >= endMillis
        }?.journal

    suspend fun get(startMillis: Long, endMillis: Long): PredictionJournal =
        current(startMillis, endMillis, MAX_AGE_MS)
            ?: load(startMillis, endMillis)?.let { load ->
                if (publish(load)) {
                    reportMissedLoad()
                    load.journal
                } else {
                    // Overtaken while it was read; draw with what overtook it if that will do.
                    current(startMillis, endMillis, MAX_AGE_MS) ?: load.journal
                }
            }
            ?: PredictionJournal.EMPTY

    fun getWithoutWaiting(startMillis: Long, endMillis: Long): PredictionJournal? {
        // The main thread makes do with an older load: the journal has not changed since.
        current(startMillis, endMillis, MAX_AGE_WITHOUT_WAITING_MS)?.let { return it }

        missed.set(true)
        if (loadingForMiss.compareAndSet(false, true)) {
            scope.launch {
                val load = try {
                    load(startMillis, endMillis)?.also { publish(it) }
                } finally {
                    loadingForMiss.set(false)
                }
                // After the flag: a miss from here on starts a load of its own. Also when
                // this one was overtaken by a journal change: the redraw then misses and loads
                // again, instead of keeping the forecast it drew without a journal.
                if (load != null) reportMissedLoad()
            }
        }
        return null
    }

    /** Null when the journal could not be read; the last good load stays. */
    private suspend fun load(startMillis: Long, endMillis: Long): Loaded? {
        val sequence = sequences.incrementAndGet()
        val atRevision = revision()
        val now = clock()
        val start = startMillis - RANGE_PADDING_MS
        val end = endMillis + RANGE_PADDING_MS
        return runCatching { loadJournal(start, end) }
            .onFailure { Log.stack(TAG, "load", it) }
            .getOrNull()
            ?.let { Loaded(it, now, atRevision, sequence, start, end) }
    }

    /**
     * Loads overlap (notification and overlay) and end in any order: one read before a journal
     * change, or before the load already published, must not replace what is newer.
     */
    private fun publish(load: Loaded): Boolean = synchronized(publishLock) {
        val published = loaded
        val overtaken = load.revision != revision() || published != null &&
            published.revision == load.revision && published.sequence > load.sequence
        if (!overtaken) loaded = load
        !overtaken
    }

    private fun reportMissedLoad() {
        if (missed.getAndSet(false)) onMissedLoadReady()
    }

    companion object {
        private const val TAG = "PredictionJournalCache"
        const val MAX_AGE_MS = 30_000L

        /**
         * Read around the range asked for, so that forecasts sharing the cache reuse it: their
         * baselines lie within the 3 h chart, and the horizon is at most 6 h. The forecast
         * itself leaves out entries beyond its own range.
         */
        const val RANGE_PADDING_MS = 6L * 60L * 60L * 1000L
        const val MAX_AGE_WITHOUT_WAITING_MS = 10L * 60L * 1000L
    }
}
