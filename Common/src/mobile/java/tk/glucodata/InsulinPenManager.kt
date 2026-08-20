package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.NovoPen.PenDose
import tk.glucodata.NovoPen.PenDoseParser
import tk.glucodata.NovoPen.PenDuplicateReconciler
import tk.glucodata.NovoPen.PenJournalEntry
import tk.glucodata.NovoPen.PenSourceIds
import tk.glucodata.NovoPen.opennov.OpContext
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository

/** What the app remembers about one pen between scans. */
data class InsulinPen(
    val serial: String,
    val insulinPresetId: Long = 0L,
    val insulinName: String? = null,
    val addedAt: Long = 0L,
    val lastScanAt: Long = 0L,
    /**
     * Newest dose this pen has been imported up to, on the pen's own counter; the
     * "nothing new" gate reads it. Kept in the pen's timeline rather than in epoch
     * seconds, because that is the only one of the two that means the same thing on the
     * next scan.
     */
    val lastImportedDoseRelativeSeconds: Long = 0L,
    val importedDoseCount: Int = 0,
    /** One-shot: ignore the cursor on the next scan and offer the pen's whole log. */
    val fullReadArmed: Boolean = false,
)

/**
 * Owns insulin pen support: whether NFC pens are read at all, which pens are known, and
 * the path from a scanned dose to a journal entry.
 *
 * Doses land in the Kotlin journal rather than the legacy native number store, because the
 * journal is what the app actually shows, counts as IOB and uploads as treatments.
 */
object InsulinPenManager {
    private const val LOG_ID = "InsulinPen"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val ENABLED_KEY = "insulin_pen_enabled"
    private const val PENS_KEY = "insulin_pen_registry"

    /** A newly paired pen only offers this much of its stored log pre-selected. */
    const val FIRST_SCAN_PRESELECT_SECONDS = 24L * 60 * 60

    /** How far back the review sheet lists doses at all. */
    const val REVIEW_WINDOW_SECONDS = 30L * 24 * 60 * 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs
        get() = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pens = MutableStateFlow(loadPens())
    val pens: StateFlow<List<InsulinPen>> = _pens.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED_KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Off until asked for. An NFC reader that reacts to every ISO-DEP card in range —
     * bank cards, transit passes, door badges — is why users saw a pen error they had
     * never gone looking for.
     */
    @JvmStatic
    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun pen(serial: String): InsulinPen? = _pens.value.firstOrNull { it.serial == serial }

    fun setInsulin(serial: String, presetId: Long, presetName: String) {
        update(serial) { it.copy(insulinPresetId = presetId, insulinName = presetName) }
    }

    fun forget(serial: String) {
        _pens.value = _pens.value.filterNot { it.serial == serial }
        persist()
    }

    /** Arms the next scan of this pen to walk its whole log instead of stopping at the cursor. */
    fun armFullRead(serial: String) {
        update(serial) { it.copy(fullReadArmed = true) }
    }

    /**
     * Called from the NFC protocol thread while the pen is still in the field, to stop
     * reading segments once everything they hold is already in the journal.
     *
     * The pen sends newest first, so once a chunk's newest dose is one we already have,
     * everything behind it is older and known too.
     */
    @JvmStatic
    fun isFullyImported(serial: String?, referenceTimeSeconds: Long, raw: ByteArray?): Boolean {
        val known = serial?.let(::pen) ?: return false
        if (known.fullReadArmed) return false
        val cursor = known.lastImportedDoseRelativeSeconds
        if (cursor <= 0L) return false
        val newest = PenDoseParser.parse(referenceTimeSeconds, raw, nowSeconds())
            .maxOfOrNull(PenDose::relativeSeconds)
            ?: return false
        return newest <= cursor
    }

    /**
     * Entry point for a finished pen read — including a partial one, which is the normal
     * case for a pen holding hundreds of doses. "New" means not already in the journal,
     * so re-scanning after a read that got cut short is safe and shows no duplicates.
     */
    @JvmStatic
    fun onScanned(serial: String, chunks: List<OpContext.Doses>) {
        val known = pen(serial)
        update(serial) { it.copy(lastScanAt = System.currentTimeMillis(), fullReadArmed = false) }
        scope.launch {
            val now = nowSeconds()
            val doses = PenDoseParser.merge(
                chunks.map { PenDoseParser.parse(it.referencetime, it.rawdoses, now) }
            )
            val cutoff = now - REVIEW_WINDOW_SECONDS
            val repository = JournalRepository()
            val removed = reconcile(serial, doses, repository)
            val fresh = doses
                .filter { it.timestampSeconds > cutoff }
                .filterNot { repository.hasEntryWithSourceRecordId(sourceRecordId(serial, it)) }
            if (removed > 0) {
                Applic.Toaster(Applic.app.getString(R.string.insulin_pen_duplicates_removed, removed))
            } else if (fresh.isEmpty()) {
                Applic.Toaster(Applic.app.getString(R.string.insulin_pen_no_new_doses))
            }
            if (fresh.isEmpty()) {
                // A scan with nothing to offer still says how far the journal reaches. Without
                // recording that, the next tap would walk the pen's whole log again.
                doses.maxOfOrNull(PenDose::relativeSeconds)?.let { advanceCursor(serial, it) }
                return@launch
            }
            val preselectFrom = if (known == null) now - FIRST_SCAN_PRESELECT_SECONDS else 0L
            InsulinPenScanBus.offer(
                PenScanResult(
                    serial = serial,
                    doses = fresh.sortedByDescending(PenDose::timestampSeconds),
                    preselectFromSeconds = preselectFrom,
                )
            )
        }
    }

    /**
     * Fire-and-forget import for the review sheet. Writing runs on a manager-owned scope,
     * so dismissing the sheet mid-save cannot leave half the doses in the journal.
     */
    fun importDosesAsync(
        serial: String,
        doses: List<PenDose>,
        presetId: Long,
        presetName: String,
    ) {
        scope.launch {
            val saved = importDoses(serial, doses, presetId, presetName)
            Applic.Toaster(Applic.app.getString(R.string.insulin_pen_doses_added, saved))
        }
    }

    /**
     * Writes the chosen doses to the journal. Re-scanning a pen is normal, so every dose
     * carries a stable source id and an already-stored dose is left untouched rather than
     * overwritten — an edited amount survives the next scan.
     */
    suspend fun importDoses(
        serial: String,
        doses: List<PenDose>,
        presetId: Long,
        presetName: String,
    ): Int = withContext(Dispatchers.IO) {
        if (doses.isEmpty()) return@withContext 0
        val repository = JournalRepository()
        var saved = 0
        doses.forEach { dose ->
            val recordId = sourceRecordId(serial, dose)
            runCatching {
                if (repository.hasEntryWithSourceRecordId(recordId)) return@forEach
                repository.upsertEntry(
                    JournalEntryInput(
                        timestamp = dose.timestampSeconds * 1000L,
                        type = JournalEntryType.INSULIN,
                        title = presetName,
                        note = Applic.app.getString(R.string.insulin_pen_name, serial),
                        amount = dose.units,
                        insulinPresetId = presetId.takeIf { it > 0L },
                        source = JournalEntrySource.PEN,
                        sourceRecordId = recordId,
                    )
                )
                saved++
            }.onFailure { error ->
                Log.e(LOG_ID, "Failed to journal pen dose: ${Log.stackline(error)}")
            }
        }
        // The cursor is the "stop reading" mark for the next tap, not the dedupe rule —
        // that is the source record id. Anything older the reader left unticked stays
        // declined unless they ask for a full read.
        val newest = doses.maxOf(PenDose::relativeSeconds)
        update(serial) {
            it.copy(
                insulinPresetId = presetId,
                insulinName = presetName,
                lastImportedDoseRelativeSeconds = maxOf(it.lastImportedDoseRelativeSeconds, newest),
                importedDoseCount = it.importedDoseCount + saved,
            )
        }
        if (saved > 0) {
            UiRefreshBus.requestDataRefresh()
            if (Natives.getpostTreatments()) Natives.wakeuploader()
        }
        saved
    }

    private fun sourceRecordId(serial: String, dose: PenDose) = PenSourceIds.stable(serial, dose)

    /** Moves the "stop reading here" mark up the pen's own counter. */
    private fun advanceCursor(serial: String, relativeSeconds: Long) {
        update(serial) {
            it.copy(
                lastImportedDoseRelativeSeconds =
                    maxOf(it.lastImportedDoseRelativeSeconds, relativeSeconds),
            )
        }
    }

    /**
     * Repairs what builds before the stable record id left in the journal: the same
     * injection written once per scan, and entries named after an anchor that no longer
     * exists. Runs on every scan because a pen only reveals its log when it is tapped,
     * and it stops costing anything once nothing legacy is left.
     *
     * @return how many duplicate entries were removed
     */
    private suspend fun reconcile(
        serial: String,
        doses: List<PenDose>,
        repository: JournalRepository,
    ): Int {
        if (doses.isEmpty()) return 0
        return runCatching {
            val window = PenDuplicateReconciler.MATCH_WINDOW_SECONDS
            val from = (doses.minOf(PenDose::timestampSeconds) - window) * 1000L
            val to = (doses.maxOf(PenDose::timestampSeconds) + window) * 1000L
            val entries = repository
                .entriesFromSourceBetween(JournalEntrySource.PEN, from, to)
                .mapNotNull { entry ->
                    val recordId = entry.sourceRecordId ?: return@mapNotNull null
                    val units = entry.amount ?: return@mapNotNull null
                    PenJournalEntry(entry.id, entry.timestamp / 1000L, units, recordId)
                }
            val plan = PenDuplicateReconciler.plan(serial, doses, entries)
            if (plan.isEmpty) return@runCatching 0
            plan.adopt.forEach { (entryId, recordId) ->
                repository.retagSourceRecordId(entryId, recordId)
            }
            plan.delete.forEach { repository.deleteEntry(it) }
            Log.i(LOG_ID, "Pen $serial: renamed ${plan.adopt.size}, dropped ${plan.delete.size} duplicate(s)")
            if (plan.delete.isNotEmpty()) UiRefreshBus.requestDataRefresh()
            plan.delete.size
        }.getOrElse { error ->
            Log.e(LOG_ID, "Pen duplicate cleanup failed: ${Log.stackline(error)}")
            0
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000L

    private fun update(serial: String, transform: (InsulinPen) -> InsulinPen) {
        val existing = pen(serial)
        val base = existing ?: InsulinPen(serial = serial, addedAt = System.currentTimeMillis())
        val updated = transform(base)
        _pens.value = if (existing == null) {
            _pens.value + updated
        } else {
            _pens.value.map { if (it.serial == serial) updated else it }
        }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _pens.value.forEach { pen ->
            array.put(
                JSONObject()
                    .put("serial", pen.serial)
                    .put("presetId", pen.insulinPresetId)
                    .put("insulinName", pen.insulinName ?: JSONObject.NULL)
                    .put("addedAt", pen.addedAt)
                    .put("lastScanAt", pen.lastScanAt)
                    .put("lastRel", pen.lastImportedDoseRelativeSeconds)
                    .put("count", pen.importedDoseCount)
                    .put("fullRead", pen.fullReadArmed)
            )
        }
        prefs.edit().putString(PENS_KEY, array.toString()).apply()
    }

    private fun loadPens(): List<InsulinPen> {
        val stored = prefs.getString(PENS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val serial = item.optString("serial").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                InsulinPen(
                    serial = serial,
                    insulinPresetId = item.optLong("presetId", 0L),
                    insulinName = item.optString("insulinName").takeIf { it.isNotBlank() },
                    addedAt = item.optLong("addedAt", 0L),
                    lastScanAt = item.optLong("lastScanAt", 0L),
                    lastImportedDoseRelativeSeconds = item.optLong("lastRel", 0L),
                    importedDoseCount = item.optInt("count", 0),
                    fullReadArmed = item.optBoolean("fullRead", false),
                )
            }
        }.getOrElse { error ->
            Log.e(LOG_ID, "Unreadable pen registry: ${Log.stackline(error)}")
            emptyList()
        }
    }
}

/** A finished pen read waiting for the reader to confirm what goes into the journal. */
data class PenScanResult(
    val serial: String,
    val doses: List<PenDose>,
    val preselectFromSeconds: Long,
)

/**
 * A pen is tapped against the phone from wherever the user happens to be, so the review
 * sheet is hosted once at the top of the Compose tree and woken through here.
 */
@Keep
object InsulinPenScanBus {
    private val _pending = MutableStateFlow<PenScanResult?>(null)
    val pending: StateFlow<PenScanResult?> = _pending.asStateFlow()

    fun offer(result: PenScanResult) {
        _pending.value = result
    }

    fun clear() {
        _pending.value = null
    }
}
