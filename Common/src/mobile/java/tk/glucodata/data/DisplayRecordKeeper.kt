package tk.glucodata.data

import android.content.Context
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Keeps "Keep displayed values" records for every minute, not only the ones
 * that were on screen: each time readings are stored or the data changes
 * (a calibration, a raw/auto switch), the minutes stored since the last pass
 * and the grace window are recorded as the dashboard draws them
 * ([HistoryRepository.recordDisplayedMinutesSince]). A minute is revised while
 * it settles and seals after [ReadingDisplay.DISPLAY_SEAL_GRACE_MS], so a later
 * calibration or raw/auto switch moves the last hour and nothing older.
 *
 * Which readings are new is read off `firstStoredAt`, so a history backfill
 * hours late is recorded as it lands. The watermark lives in preferences;
 * without one (first run, or the setting just turned on) the whole store is
 * recorded once, as it is shown at that moment.
 */
object DisplayRecordKeeper {
    private const val PREFS_NAME = "reading_display_keeper"
    private const val KEY_STORED_THROUGH = "stored_through"

    /** Rows stamped just before a pass may land after its query; look back this far. */
    private const val STORED_AT_SLACK_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var started = false
    private lateinit var appContext: Context

    /** Watches the readings table of [database]. Called once, when it opens. */
    fun watch(database: HistoryDatabase, context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext ?: context
        }
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("history_readings") {
            override fun onInvalidated(tables: Set<String>) {
                requests.trySend(Unit)
            }
        })
        // A calibration change requests a data refresh, a raw/auto switch a
        // status refresh: revise the grace window even when no reading arrives.
        scope.launch {
            tk.glucodata.UiRefreshBus.events.collect { requests.trySend(Unit) }
        }
        scope.launch {
            for (request in requests) {
                runCatching { recordNewMinutes() }
                    .onFailure { tk.glucodata.Log.e("DisplayRecordKeeper", "recording failed: $it") }
            }
        }
    }

    /** Records the whole store again on the next pass; for turning the setting on. */
    fun recordAll(context: Context? = tk.glucodata.Applic.app) {
        prefs(context)?.edit()?.remove(KEY_STORED_THROUGH)?.apply()
        requests.trySend(Unit)
    }

    private suspend fun recordNewMinutes() {
        val prefs = prefs(appContext) ?: return
        val passStart = System.currentTimeMillis()
        val storedThrough = prefs.getLong(KEY_STORED_THROUGH, 0L)
        val earliestNew = if (storedThrough <= 0L) {
            null
        } else {
            HistoryDatabase.getInstance(appContext).historyDao().getEarliestTimestampStoredSince(storedThrough)
        }
        HistoryRepository(appContext)
            .recordDisplayedMinutesSince(startTimeFor(storedThrough, earliestNew, passStart))
            ?: return
        prefs.edit().putLong(KEY_STORED_THROUGH, passStart - STORED_AT_SLACK_MS).apply()
    }

    /**
     * Where a pass starts: the whole store without a watermark, else the
     * oldest reading stored since it, and never later than the grace window,
     * which a calibration or switch may have changed with no new reading.
     */
    internal fun startTimeFor(storedThrough: Long, earliestStoredSince: Long?, nowMs: Long): Long {
        if (storedThrough <= 0L) return 0L
        val graceStart = nowMs - ReadingDisplay.DISPLAY_SEAL_GRACE_MS - ReadingDisplay.MINUTE_MS
        return minOf(graceStart, earliestStoredSince ?: graceStart)
    }

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
