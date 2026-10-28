package tk.glucodata.data.journal

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.R
import tk.glucodata.data.journal.AapsStatusLine.Segment

/**
 * Turns the AAPS status lines into TEMP_BASAL journal segments. The running segment is
 * kept in preferences, so a restarted process keeps extending the same row instead of
 * starting a new one at the next line.
 */
object AapsStatusLineImport {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_START = "aaps_status_tbr_start"
    private const val KEY_RATE = "aaps_status_tbr_rate"
    private const val KEY_LAST_SEEN = "aaps_status_tbr_last_seen"

    private val lock = Mutex()

    /** Returns the number of journal rows written. */
    suspend fun handle(context: Context, statusLine: String, nowMillis: Long): Int = lock.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val running = if (prefs.contains(KEY_START)) {
            Segment(
                startMillis = prefs.getLong(KEY_START, 0L),
                unitsPerHour = prefs.getFloat(KEY_RATE, 0f),
                lastSeenMillis = prefs.getLong(KEY_LAST_SEEN, 0L),
            )
        } else {
            null
        }
        val step = AapsStatusLine.advance(running, AapsStatusLine.parse(statusLine), nowMillis)

        val title = context.getString(R.string.journal_type_temp_basal)
        val repository = JournalRepository()
        val written = listOfNotNull(step.closed, step.current).onEach { segment ->
            repository.upsertEntry(segment.toInput(title))
        }.size

        val editor = prefs.edit()
        val current = step.current
        if (current == null) {
            editor.remove(KEY_START).remove(KEY_RATE).remove(KEY_LAST_SEEN)
        } else {
            editor.putLong(KEY_START, current.startMillis)
                .putFloat(KEY_RATE, current.unitsPerHour)
                .putLong(KEY_LAST_SEEN, current.lastSeenMillis)
        }
        editor.apply()
        written
    }

    private fun Segment.toInput(title: String) = JournalEntryInput(
        timestamp = startMillis,
        type = JournalEntryType.TEMP_BASAL,
        title = title,
        amount = unitsPerHour,
        durationMinutes = durationMinutes,
        source = JournalEntrySource.AAPS,
        sourceRecordId = AapsStatusLine.SOURCE_RECORD_PREFIX + startMillis,
    )
}
