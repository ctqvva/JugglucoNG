package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.data.HistoryDatabase

/**
 * Supplies the LibreView payload with the journal's food, insulin and note entries.
 *
 * The native LibreView writer sizes its buffer before it fills it, so this works in three
 * steps driven from `net/libreview/libreview.cpp` and `newlibre3.cpp`: [prepare] renders
 * what is pending and reports how many bytes it needs, the three accessors hand over the
 * rendered arrays, and [commit] runs only once the document has actually been accepted.
 * A failed upload leaves every row pending, exactly as the native cursors behave.
 *
 * Entries carry a trailing comma each, which is what the native array writers emit; the
 * caller strips the last one.
 */
@Keep
object LibreviewJournalEntries {
    private const val LOG_ID = "LibreviewJournal"

    /** Mirrors the native LibreView send window: older entries are the cloud's problem. */
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * A cap on one document's worth of journal entries. The native buffer is sized from
     * what [prepare] reports, so this is not a safety limit but a fairness one: a first
     * upload after a long gap should not push a megabyte of notes ahead of the glucose
     * data. Whatever is left goes out on the next pass.
     */
    private const val MAX_ENTRIES = 300

    private class Pending(
        val food: String,
        val insulin: String,
        val generic: String,
        val ids: List<Long>
    )

    @Volatile
    private var pending: Pending? = null

    /**
     * Renders everything not yet delivered to LibreView and returns its size in bytes, or
     * 0 when there is nothing to send. Always paired with [commit] or [discard].
     */
    @JvmStatic
    @Keep
    fun prepare(libre3: Boolean): Int {
        pending = null
        return try {
            if (!Natives.getSendNumbers()) return 0
            val prepared = runBlocking { load(libre3) } ?: return 0
            pending = prepared
            prepared.food.toByteArray(Charsets.UTF_8).size +
                prepared.insulin.toByteArray(Charsets.UTF_8).size +
                prepared.generic.toByteArray(Charsets.UTF_8).size
        } catch (th: Throwable) {
            Log.e(LOG_ID, "prepare failed: ${Log.stackline(th)}")
            pending = null
            0
        }
    }

    @JvmStatic
    @Keep
    fun foodEntries(): String = pending?.food.orEmpty()

    @JvmStatic
    @Keep
    fun insulinEntries(): String = pending?.insulin.orEmpty()

    @JvmStatic
    @Keep
    fun noteEntries(): String = pending?.generic.orEmpty()

    /** Marks the prepared rows delivered. Called only after LibreView accepted the document. */
    @JvmStatic
    @Keep
    fun commit() {
        val sent = pending ?: return
        pending = null
        if (sent.ids.isEmpty()) return
        try {
            val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
            runBlocking { dao.markEntriesUploadedToLibreview(sent.ids, System.currentTimeMillis()) }
        } catch (th: Throwable) {
            // Losing the mark costs one duplicate send under the same record numbers, which
            // LibreView treats as an update of the entry it already has.
            Log.e(LOG_ID, "commit failed: ${Log.stackline(th)}")
        }
    }

    @JvmStatic
    @Keep
    fun discard() {
        pending = null
    }

    private suspend fun load(libre3: Boolean): Pending? {
        val app = Applic.app ?: return null
        val dao = HistoryDatabase.getInstance(app).journalDao()
        val since = System.currentTimeMillis() - LOOKBACK_MILLIS
        val candidates = dao.getEntriesNeedingLibreviewUpload(since)
            .asSequence()
            .filter { it.timestamp > 0L }
            .filter { !LibreviewJournalTransfer.isExternalMirrorSource(it.source) }
            .filter { LibreviewJournalTransfer.isSendable(it) }
            .take(MAX_ENTRIES)
            .toList()
        if (candidates.isEmpty()) return null

        val presets = HashMap<Long, JournalInsulinPresetEntity>()
        for (presetId in candidates.mapNotNull { it.insulinPresetId }.distinct()) {
            dao.getInsulinPresetById(presetId)?.let { presets[presetId] = it }
        }

        val built = LibreviewJournalTransfer.build(candidates, presets, libre3)
        if (built.isEmpty) return null
        return Pending(
            food = join(built.food),
            insulin = join(built.insulin),
            generic = join(built.generic),
            ids = candidates.map { it.id }
        )
    }

    private fun join(entries: List<String>): String =
        if (entries.isEmpty()) "" else entries.joinToString(separator = ",", postfix = ",")
}
