package tk.glucodata

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIobCalculator
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.JournalTreatmentTransfer
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.logic.CompressionLowDetector

object OutboundApiJournalSnapshot {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val SNAPSHOT_EVENT_WINDOW_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_ACTIVE_WINDOW_MS = 24L * 60L * 60L * 1000L
    private const val API_SOURCE_PREFIX = "api"
    private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"

    @JvmStatic
    fun snapshotJson(timeMillis: Long): String = runBlocking {
        withContext(Dispatchers.IO) {
            buildSnapshot(timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()).toString()
        }
    }

    private class BroadcastIobCache(val atMillis: Long, val values: FloatArray?)

    @Volatile
    private var broadcastIobCache: BroadcastIobCache? = null
    private const val BROADCAST_IOB_CACHE_MS = 30_000L

    private val journalChangedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var journalChangedJob: Job? = null
    private const val JOURNAL_CHANGED_DEBOUNCE_MS = 400L

    /**
     * Called by JournalRepository after IOB-relevant mutations so the surfaces
     * outside the app catch up right away instead of at the next glucose
     * reading: the persistent notification, the glucodata.Minute broadcast and
     * the webserver's /pebble store. Bursts (importer sync cycles) coalesce
     * into a single refresh.
     */
    @JvmStatic
    fun journalChanged() {
        broadcastIobCache = null
        journalChangedJob?.cancel()
        journalChangedJob = journalChangedScope.launch {
            delay(JOURNAL_CHANGED_DEBOUNCE_MS)
            val now = System.currentTimeMillis()
            val values = runCatching { buildBroadcastIob(now) }.getOrNull()
            broadcastIobCache = BroadcastIobCache(now, values)
            JournalIobAccess.pushWatchserver(now)
            if (values != null) JugglucoSend.rebroadcastIob()
            Notify.showoldglucose()
        }
    }

    /**
     * Compact insulin/carb snapshot for the glucodata.Minute broadcast and the
     * persistent notification, resolved from src/main (JugglucoSend, Notify)
     * via reflection because the journal only exists in the mobile source set.
     * Returns [classicIob, eiob, cob, iobNext30min, cobNext30min] with NaN
     * marking "no data of that kind",
     * or null when the journal feature is disabled or has never seen
     * insulin/carb entries — users of the legacy native amounts must not have
     * their /pebble-polled IOB clobbered with journal zeros.
     *
     * Results are cached briefly; on the main thread the cache (possibly
     * stale) is returned instead of blocking on Room.
     */
    @JvmStatic
    fun broadcastIobSnapshot(timeMillis: Long): FloatArray? {
        val atMillis = timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val cached = broadcastIobCache
        if (cached != null && atMillis - cached.atMillis < BROADCAST_IOB_CACHE_MS) return cached.values
        if (Looper.myLooper() == Looper.getMainLooper()) return cached?.values
        val fresh = runBlocking {
            withContext(Dispatchers.IO) {
                runCatching { buildBroadcastIob(atMillis) }.getOrNull()
            }
        }
        broadcastIobCache = BroadcastIobCache(atMillis, fresh)
        return fresh
    }

    /**
     * Whether the newest journal insulin dose is past its curve-derived activity peak
     * at [timeMillis]: 1 = past, 0 = not yet, -1 = unknown. Resolved from src/main via
     * reflection (JournalIobAccess) for the compression-low hold. "No doses inside the
     * lookback window" is a vacuous 1 — with nothing on board there is no ramp-up to
     * wait out, and the hold's separate IOB gate judges the amount. Unknown is returned
     * on the main thread and on any failure; the hold treats it as "do not hold".
     */
    @JvmStatic
    fun lastDosePeakPassed(timeMillis: Long): Int {
        if (Looper.myLooper() == Looper.getMainLooper()) return -1
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val app = Applic.app ?: return@runCatching -1
                    val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    if (!prefs.getBoolean(JOURNAL_ENABLED_KEY, true)) return@runCatching -1
                    val dao = HistoryDatabase.getInstance(app).journalDao()
                    val presetsById = dao.getInsulinPresets().map { toPresetModel(it) }.associateBy { it.id }
                    val maxPresetDurationMs = presetsById.values
                        .maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }?.times(60_000L)
                        ?: DEFAULT_ACTIVE_WINDOW_MS
                    val startMillis = (timeMillis - maxOf(DEFAULT_ACTIVE_WINDOW_MS, maxPresetDurationMs) - 60_000L)
                        .coerceAtLeast(0L)
                    val entries = dao.getEntriesBetween(startMillis, timeMillis)
                    val doses = JournalIobCalculator.dosesFromEntities(entries, presetsById)
                    val newest = doses.maxByOrNull { it.timestampMillis } ?: return@runCatching 1
                    val curve = newest.preset.curvePoints.map { it.minute to it.activity }
                    if (CompressionLowDetector.dosePeakPassed(newest.timestampMillis, curve, timeMillis)) 1 else 0
                }.getOrDefault(-1)
            }
        }
    }

    private suspend fun buildBroadcastIob(atMillis: Long): FloatArray? {
        val app = Applic.app ?: return null
        val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean(JOURNAL_ENABLED_KEY, true)) return null
        val dao = HistoryDatabase.getInstance(app).journalDao()
        val hasInsulin = dao.countEntriesByType(JournalEntryType.INSULIN.storageValue) > 0
        val hasCarbs = dao.countEntriesByType(JournalEntryType.CARBS.storageValue) > 0
        if (!hasInsulin && !hasCarbs) return null
        val presetsById = dao.getInsulinPresets().map { toPresetModel(it) }.associateBy { it.id }
        val maxPresetDurationMs = presetsById.values.maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }
            ?.times(60_000L)
            ?: DEFAULT_ACTIVE_WINDOW_MS
        val startMillis = (atMillis - maxOf(DEFAULT_ACTIVE_WINDOW_MS, maxPresetDurationMs) - 60_000L)
            .coerceAtLeast(0L)
        val entries = dao.getEntriesBetween(startMillis, atMillis)
        val doses = JournalIobCalculator.dosesFromEntities(entries, presetsById)
        val insulin = JournalIobCalculator.compute(doses, atMillis)
        // Insulin delivering / carbs absorbing within the next 30 minutes —
        // the "soon" quantities the notification risk warning projects with.
        val windowMillis = 30L * 60L * 1000L
        val insulinAfterWindow = JournalIobCalculator.compute(doses, atMillis + windowMillis)
        val iobNextWindow = (insulin.iobUnits - insulinAfterWindow.iobUnits).coerceAtLeast(0f)
        val cobNow = if (hasCarbs) activeCarbsGrams(entries, atMillis) else Float.NaN
        val cobNextWindow = if (hasCarbs) {
            (cobNow - activeCarbsGrams(entries, atMillis + windowMillis)).coerceAtLeast(0f)
        } else {
            Float.NaN
        }
        // A fresh devicestatus from the uploading device replaces the local
        // journal math: IOB, eIOB and COB switch source together because they
        // come from one document and one computation — mixing them would be
        // inconsistent. The one exception is a field the document lacks (an
        // uploader without carb data omits cob): that field alone stays
        // local. The 30-minute-window projections keep the local values in
        // either case; they only feed the notification risk tint and have no
        // remote counterpart.
        val remote = tk.glucodata.drivers.nightscout.NightscoutFollowerDeviceStatus.fresh(atMillis)
        val localIob = if (hasInsulin) insulin.iobUnits else Float.NaN
        val localEiob = if (hasInsulin) insulin.eiobUnits else Float.NaN
        return floatArrayOf(
            remote?.iobUnits ?: localIob,
            remote?.eiobUnits?.takeIf { it.isFinite() } ?: localEiob,
            remote?.cobGrams?.takeIf { it.isFinite() } ?: cobNow,
            if (hasInsulin) iobNextWindow else Float.NaN,
            cobNextWindow
        )
    }

    @JvmStatic
    fun importFromJson(raw: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            importJournal(raw, API_SOURCE_PREFIX)
        }
    }

    @JvmStatic
    fun importFromJsonForSource(raw: String, sourcePrefix: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            importJournal(raw, sourcePrefix.trim().ifBlank { API_SOURCE_PREFIX })
        }
    }

    private suspend fun buildSnapshot(atMillis: Long): JSONObject {
        val database = HistoryDatabase.getInstance(Applic.app)
        val dao = database.journalDao()
        val presetEntities = dao.getInsulinPresets()
        val presets = presetEntities.map { toPresetModel(it) }
        val presetsById = presets.associateBy { it.id }
        val presetEntitiesById = presetEntities.associateBy { it.id }
        val foodsById = dao.getFoods().associateBy { it.id }
        val maxPresetDurationMs = presets.maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }?.times(60_000L)
            ?: DEFAULT_ACTIVE_WINDOW_MS
        val startMillis = (atMillis - maxOf(DEFAULT_ACTIVE_WINDOW_MS, maxPresetDurationMs) - 60_000L)
            .coerceAtLeast(0L)
        val entries = dao.getEntriesBetween(startMillis, atMillis)
        val insulin = JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromEntities(entries, presetsById),
            atMillis
        )
        val cob = activeCarbsGrams(entries, atMillis)
        val eventWindowStart = atMillis - SNAPSHOT_EVENT_WINDOW_MS
        val events = JSONArray()
        entries
            .filter { it.timestamp >= eventWindowStart }
            .takeLast(64)
            .forEach { entry -> events.put(entry.toTransferJson(presetEntitiesById, foodsById)) }
        return JSONObject()
            .put("schema", "tk.glucodata.journal.snapshot.v2")
            .put("timestamp", atMillis)
            .put("iob", finiteOrNull(insulin.iobUnits))
            .put("journal_iob", finiteOrNull(insulin.iobUnits))
            .put("eiob", finiteOrNull(insulin.eiobUnits))
            .put("journal_eiob", finiteOrNull(insulin.eiobUnits))
            .put("cob", finiteOrNull(cob))
            .put("journal_cob", finiteOrNull(cob))
            .put("events", events)
            .put("treatments", events)
    }

    private suspend fun importJournal(raw: String, sourcePrefix: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0
        val events = runCatching { collectJournalEvents(trimmed) }.getOrNull() ?: return 0
        if (events.length() == 0) return 0

        val repository = JournalRepository()
        repository.ensureDefaultInsulinPresets()
        val presets = repository.getInsulinPresetsSnapshot()
        var imported = 0
        var deleted = 0
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = Applic.app,
                treatment = item,
                source = JournalEntrySource.API,
                sourcePrefix = sourcePrefix,
                insulinPresets = presets
            )
                ?: continue
            if (parsed.deleteOnly) {
                repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                deleted += parsed.candidateSourceRecordIds.size
                continue
            }
            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            val importedSourceIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
            val staleSourceIds = parsed.candidateSourceRecordIds.filterNot { it in importedSourceIds }
            repository.deleteEntriesBySourceRecordIds(staleSourceIds)
            deleted += staleSourceIds.size
        }
        return imported
    }

    private fun collectJournalEvents(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return collectJournalEvents(JSONArray(trimmed))
        val root = JSONObject(trimmed)
        root.optJSONObject("journal")?.let { return collectJournalEvents(it.toString()) }
        root.optJSONArray("events")?.let { return collectJournalEvents(it) }
        root.optJSONArray("treatments")?.let { return collectJournalEvents(it) }
        root.optJSONArray("journal")?.let { return collectJournalEvents(it) }
        root.optJSONArray("readings")?.let { readings ->
            val events = collectJournalEvents(readings)
            if (events.length() > 0) return events
        }
        return JSONArray().also { array ->
            if (root.looksLikeJournalEvent()) array.put(root)
        }
    }

    private fun collectJournalEvents(array: JSONArray): JSONArray {
        val events = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.looksLikeJournalEvent()) {
                events.put(item)
                continue
            }
            val nested = collectJournalEvents(item.toString())
            for (nestedIndex in 0 until nested.length()) {
                events.put(nested.opt(nestedIndex))
            }
        }
        return events
    }

    private fun JSONObject.looksLikeJournalEvent(): Boolean {
        if (has("eventType") || has("eventtype") || has("event_type") || has("entryType") || has("journalType")) {
            return true
        }
        val type = optString("type", "").lowercase()
        if (JournalEntryType.entries.any { it.storageValue == type }) return true
        return hasTreatmentTimestamp() &&
            (
                has("carbs") ||
                    has("carb") ||
                    has("enteredCarbs") ||
                    has("insulin") ||
                    has("enteredInsulin") ||
                    has("bolus")
            )
    }

    private fun JSONObject.hasTreatmentTimestamp(): Boolean {
        for (key in listOf("date", "mills", "millis", "timestamp", "time", "createdAt", "created_at", "created_at_millis")) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is Number) return true
            if (value is String && value.trim().isNotBlank()) return true
        }
        return false
    }

    private fun JournalEntryEntity.toTransferJson(
        presetsById: Map<Long, JournalInsulinPresetEntity>,
        foodsById: Map<Long, JournalFoodEntity>
    ): JSONObject {
        val type = JournalEntryType.fromStorage(entryType)
        val transferId = sourceRecordId ?: nsRemoteId ?: "journal:$id"
        val treatment = JournalTreatmentTransfer.buildTreatmentJson(
            entry = this,
            remoteId = transferId,
            preset = insulinPresetId?.let(presetsById::get),
            food = foodId?.let(foodsById::get),
            useV3 = true
        ) ?: JSONObject()
            .put("date", timestamp)
            .put("eventType", defaultEventType(type))
            .put("type", type.storageValue)
        return treatment
            .put("id", id)
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial)
            .put("type", type.storageValue)
            .put("title", title)
            .put("note", note)
            .put("amount", finiteOrNull(amount))
            .put("glucose_mgdl", finiteOrNull(glucoseValueMgDl))
            .put("durationMinutes", durationMinutes)
            .put("intensity", intensity)
            .put("insulinPresetId", insulinPresetId)
            .put("insulinPreset", insulinPresetId?.let(presetsById::get)?.displayName)
            .put("foodId", foodId)
            .put("proteinGrams", finiteOrNull(proteinGrams))
            .put("fatGrams", finiteOrNull(fatGrams))
            .put("source", source)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("nsUploadedAt", nsUploadedAt)
            .put("nsRemoteId", nsRemoteId)
    }

    private fun activeCarbsGrams(entries: List<JournalEntryEntity>, atMillis: Long): Float {
        val prefs = Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val profile = PredictionModelProfileStore.load(prefs)
        return entries.sumOf { entry ->
            if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.CARBS) return@sumOf 0.0
            val grams = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            val absorptionMinutes = entry.durationMinutes?.toFloat()
                ?: (grams / profile.parametersAt(entry.timestamp).carbAbsorptionGramsPerHour * 60f)
                    .coerceIn(30f, 360f)
            val progress = linearProgress(entry.timestamp, absorptionMinutes, atMillis)
            (grams * (1f - progress)).coerceAtLeast(0f).toDouble()
        }.toFloat()
    }

    private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        if (atMillis <= startMillis) return 0f
        val elapsedMinutes = (atMillis - startMillis) / 60_000f
        return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    internal fun toPresetModel(entity: JournalInsulinPresetEntity): JournalInsulinPreset =
        JournalInsulinPreset(
            id = entity.id,
            displayName = entity.displayName,
            onsetMinutes = entity.onsetMinutes,
            durationMinutes = entity.durationMinutes,
            accentColor = entity.accentColor,
            curveJson = entity.curveJson,
            isBuiltIn = entity.isBuiltIn,
            isArchived = entity.isArchived,
            countsTowardIob = entity.countsTowardIob,
            sortOrder = entity.sortOrder,
            useForCalculation = entity.useForCalculation
        )

    private fun defaultEventType(type: JournalEntryType): String =
        when (type) {
            JournalEntryType.INSULIN -> "Correction Bolus"
            JournalEntryType.CARBS -> "Meal Bolus"
            JournalEntryType.FINGERSTICK -> "BG Check"
            JournalEntryType.ACTIVITY -> "Exercise"
            JournalEntryType.NOTE -> "Note"
        }

    private fun finiteOrNull(value: Float?): Any? =
        value?.takeIf { it.isFinite() }?.toDouble()

}
