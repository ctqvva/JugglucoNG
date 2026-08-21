package tk.glucodata

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
import tk.glucodata.NovoPen.PenImportNotifier
import tk.glucodata.NovoPen.PenUnattendedImportPolicy
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
    /** Newest dose second imported from this pen; the "nothing new" gate reads it. */
    val lastImportedDoseSeconds: Long = 0L,
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
    private const val BACKGROUND_IMPORT_KEY = "insulin_pen_background_import"
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
        syncBackgroundReceiver(Applic.app)
    }

    private val _backgroundImportEnabled = MutableStateFlow(prefs.getBoolean(BACKGROUND_IMPORT_KEY, false))
    val backgroundImportEnabled: StateFlow<Boolean> = _backgroundImportEnabled.asStateFlow()

    /**
     * Off until asked for: with the app in the background a pen tap imports without a
     * review sheet, so it is the reader's choice to take that. See
     * [tk.glucodata.ui.PenTagReceiverActivity].
     */
    @JvmStatic
    fun isBackgroundImportEnabled(): Boolean = _backgroundImportEnabled.value

    fun setBackgroundImportEnabled(context: Context, enabled: Boolean) {
        _backgroundImportEnabled.value = enabled
        prefs.edit().putBoolean(BACKGROUND_IMPORT_KEY, enabled).apply()
        syncBackgroundReceiver(context)
    }

    /**
     * The receiver activity is a manifest component, so the system only hands it a tag
     * while it is enabled: enabled exactly when pens are read and the background import
     * is on. Called on each change and once per start, so an upgrade or a restored
     * backup ends up matching the settings.
     */
    fun syncBackgroundReceiver(context: Context) {
        val wanted = isEnabled() && isBackgroundImportEnabled()
        runCatching {
            val pm = context.packageManager
            val receiver = ComponentName(context, tk.glucodata.ui.PenTagReceiverActivity::class.java)
            val state = if (wanted) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(receiver) != state) {
                pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { error ->
            Log.e(LOG_ID, "Pen receiver component: ${Log.stackline(error)}")
        }
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
        val cursor = known.lastImportedDoseSeconds
        if (cursor <= 0L) return false
        val newest = PenDoseParser.parse(referenceTimeSeconds, raw, nowSeconds())
            .maxOfOrNull(PenDose::timestampSeconds)
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
            val fresh = freshDoses(serial, chunks, now)
            if (fresh.isEmpty()) {
                Applic.Toaster(Applic.app.getString(R.string.insulin_pen_no_new_doses))
                return@launch
            }
            val preselectFrom = if (known == null) now - FIRST_SCAN_PRESELECT_SECONDS else 0L
            InsulinPenScanBus.offer(PenScanResult(serial, fresh, preselectFrom))
        }
    }

    /**
     * The same read, taken with the app in the background: nobody will see a sheet, so
     * what a foreground scan would have offered is written — the pen's insulin, air shots
     * left out — and a notification says what happened. A pen without an insulin chosen
     * has nothing to name its doses with, so its doses wait for the sheet instead and the
     * notification says so. The decision itself is [PenUnattendedImportPolicy].
     */
    @JvmStatic
    fun onScannedUnattended(serial: String, chunks: List<OpContext.Doses>) {
        val known = pen(serial)
        update(serial) { it.copy(lastScanAt = System.currentTimeMillis(), fullReadArmed = false) }
        scope.launch {
            val context = Applic.app
            val now = nowSeconds()
            val fresh = freshDoses(serial, chunks, now)
            val presetName = known?.insulinName?.takeIf { it.isNotBlank() }
            when (val plan = PenUnattendedImportPolicy.plan(fresh, hasPreset = presetName != null)) {
                PenUnattendedImportPolicy.Plan.NothingNew ->
                    PenImportNotifier.nothingNew(context, serial)

                is PenUnattendedImportPolicy.Plan.Import -> {
                    // importDoses moves the cursor over the doses it is handed, nothing
                    // beyond — so the air shots left out here never mark a later real
                    // dose as done. That is the rule the review sheet already relies on.
                    val saved = importDoses(serial, plan.doses, known?.insulinPresetId ?: 0L, presetName!!)
                    PenImportNotifier.imported(context, serial, saved)
                }

                is PenUnattendedImportPolicy.Plan.Review -> {
                    val preselectFrom = if (known == null) now - FIRST_SCAN_PRESELECT_SECONDS else 0L
                    InsulinPenScanBus.offer(PenScanResult(serial, plan.doses, preselectFrom))
                    PenImportNotifier.awaitingReview(context, serial, plan.doses.size)
                }
            }
        }
    }

    /**
     * What a scan has to offer, newest first: the chunks parsed and merged, cut to the
     * review window, minus what the journal already holds. One function for both the
     * review sheet and the unattended import, so the two cannot come to differ.
     */
    private suspend fun freshDoses(serial: String, chunks: List<OpContext.Doses>, now: Long): List<PenDose> {
        val doses = PenDoseParser.merge(
            chunks.map { PenDoseParser.parse(it.referencetime, it.rawdoses, now) }
        )
        val cutoff = now - REVIEW_WINDOW_SECONDS
        val repository = JournalRepository()
        return doses
            .filter { it.timestampSeconds > cutoff }
            .filterNot { repository.hasEntryWithSourceRecordId(sourceRecordId(serial, it)) }
            .sortedByDescending(PenDose::timestampSeconds)
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
        val newest = doses.maxOf(PenDose::timestampSeconds)
        update(serial) {
            it.copy(
                insulinPresetId = presetId,
                insulinName = presetName,
                lastImportedDoseSeconds = maxOf(it.lastImportedDoseSeconds, newest),
                importedDoseCount = it.importedDoseCount + saved,
            )
        }
        if (saved > 0) {
            UiRefreshBus.requestDataRefresh()
            if (Natives.getpostTreatments()) Natives.wakeuploader()
        }
        saved
    }

    private fun sourceRecordId(serial: String, dose: PenDose) = "pen:$serial:${dose.timestampSeconds}"

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
                    .put("lastDose", pen.lastImportedDoseSeconds)
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
                    lastImportedDoseSeconds = item.optLong("lastDose", 0L),
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
