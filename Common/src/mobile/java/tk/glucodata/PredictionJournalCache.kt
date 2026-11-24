package tk.glucodata

import java.util.concurrent.atomic.AtomicBoolean
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
    private val load: suspend (startMillis: Long, endMillis: Long) -> PredictionJournal
) {
    private class Loaded(
        val journal: PredictionJournal,
        val loadedAt: Long,
        val revision: Long,
        val startMillis: Long,
        val endMillis: Long
    )

    @Volatile
    private var loaded: Loaded? = null
    private val missed = AtomicBoolean()
    private val loadingForMiss = AtomicBoolean()

    /** Loaded within the last 30 s for this range, from the journal as it is now. */
    private fun fresh(startMillis: Long, endMillis: Long): PredictionJournal? = loaded?.takeIf {
        it.revision == revision() && clock() - it.loadedAt <= MAX_AGE_MS &&
            it.startMillis <= startMillis && it.endMillis >= endMillis
    }?.journal

    suspend fun get(startMillis: Long, endMillis: Long): PredictionJournal =
        fresh(startMillis, endMillis) ?: loadAndStore(startMillis, endMillis) ?: PredictionJournal.EMPTY

    fun getWithoutWaiting(startMillis: Long, endMillis: Long): PredictionJournal? {
        // An older load still holds every entry while the journal has not changed.
        fresh(startMillis, endMillis)?.let { return it }
        loaded?.takeIf {
            it.revision == revision() && clock() - it.loadedAt in 0..MAX_AGE_WITHOUT_WAITING_MS
        }?.let { return it.journal }

        missed.set(true)
        if (loadingForMiss.compareAndSet(false, true)) {
            scope.launch {
                val stored = try {
                    loadAndStore(startMillis, endMillis, report = false) != null
                } finally {
                    loadingForMiss.set(false)
                }
                // After the flag: a miss from here on starts a load of its own.
                if (stored) reportMissedLoad()
            }
        }
        return null
    }

    private suspend fun loadAndStore(
        startMillis: Long,
        endMillis: Long,
        report: Boolean = true
    ): PredictionJournal? {
        val now = clock()
        val atRevision = revision()
        val journal = runCatching { load(startMillis, endMillis) }
            .onFailure { Log.stack(TAG, "load", it) }
            .getOrNull() ?: return null
        loaded = Loaded(journal, now, atRevision, startMillis, endMillis)
        if (report) reportMissedLoad()
        return journal
    }

    /**
     * Reported even when the journal changed again during the load: the redraw then misses and
     * loads once more, instead of keeping the forecast it drew without a journal.
     */
    private fun reportMissedLoad() {
        if (missed.getAndSet(false)) onMissedLoadReady()
    }

    companion object {
        private const val TAG = "PredictionJournalCache"
        const val MAX_AGE_MS = 30_000L
        const val MAX_AGE_WITHOUT_WAITING_MS = 10L * 60L * 1000L
    }
}
