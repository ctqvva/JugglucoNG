package tk.glucodata

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetEntity

object OutboundApiJournalSnapshot {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREDICTION_CARB_ABSORPTION_KEY = "dashboard_prediction_carb_absorption_g_per_h"
    private const val PREDICTION_CARB_ABSORPTION_DEFAULT = 35f
    private const val SNAPSHOT_EVENT_WINDOW_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_ACTIVE_WINDOW_MS = 24L * 60L * 60L * 1000L

    @JvmStatic
    fun snapshotJson(timeMillis: Long): String = runBlocking {
        withContext(Dispatchers.IO) {
            buildSnapshot(timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()).toString()
        }
    }

    @JvmStatic
    fun importFromJson(raw: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            importJournal(raw)
        }
    }

    private suspend fun buildSnapshot(atMillis: Long): JSONObject {
        val database = HistoryDatabase.getInstance(Applic.app)
        val dao = database.journalDao()
        val presets = dao.getInsulinPresets().map { toPresetModel(it) }
        val presetsById = presets.associateBy { it.id }
        val maxPresetDurationMs = presets.maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }?.times(60_000L)
            ?: DEFAULT_ACTIVE_WINDOW_MS
        val startMillis = (atMillis - maxOf(DEFAULT_ACTIVE_WINDOW_MS, maxPresetDurationMs) - 60_000L)
            .coerceAtLeast(0L)
        val entries = dao.getEntriesBetween(startMillis, atMillis)
        val iob = activeInsulinUnits(entries, presetsById, atMillis)
        val cob = activeCarbsGrams(entries, atMillis)
        val eventWindowStart = atMillis - SNAPSHOT_EVENT_WINDOW_MS
        val events = JSONArray()
        entries
            .filter { it.timestamp >= eventWindowStart }
            .takeLast(64)
            .forEach { entry -> events.put(entry.toJson(presetsById)) }
        return JSONObject()
            .put("timestamp", atMillis)
            .put("iob", finiteOrNull(iob))
            .put("cob", finiteOrNull(cob))
            .put("events", events)
    }

    private suspend fun importJournal(raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0
        val events = runCatching {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("events")
                        ?: root.optJSONArray("journal")
                        ?: JSONArray().also { array ->
                            if (root.has("type") || root.has("entryType")) array.put(root)
                        }
                }
            }
        }.getOrNull() ?: return 0
        if (events.length() == 0) return 0

        val now = System.currentTimeMillis()
        val imported = ArrayList<JournalEntryEntity>(events.length())
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val timestamp = normalizeTimestamp(
                firstLong(
                    item.optLong("timestamp", 0L),
                    item.optLong("date", 0L),
                    item.optLong("time", 0L)
                )
            ) ?: continue
            val type = JournalEntryType.fromStorage(
                item.optString("type", item.optString("entryType", "note"))
                    .lowercase(Locale.US)
            )
            val amount = firstFiniteAny(
                item.optDouble("amount", Double.NaN),
                item.optDouble("insulin", Double.NaN),
                item.optDouble("carbs", Double.NaN)
            )?.toFloat()
            val glucoseMgdl = firstFiniteAny(
                item.optDouble("glucoseValueMgDl", Double.NaN),
                item.optDouble("glucose_mgdl", Double.NaN),
                item.optDouble("mgdl", Double.NaN)
            )?.toFloat()
            val remoteId = item.optString("id", "").ifBlank {
                "%s:%d:%s:%s".format(
                    Locale.US,
                    item.optString("source", "api"),
                    timestamp,
                    type.storageValue,
                    item.optString("title", "")
                )
            }
            imported += JournalEntryEntity(
                id = 0L,
                timestamp = timestamp,
                sensorSerial = item.optString("sensorSerial", "").ifBlank { null },
                entryType = type.storageValue,
                title = item.optString("title", defaultTitle(type)).ifBlank { defaultTitle(type) },
                note = item.optString("note", "").ifBlank { null },
                amount = amount,
                glucoseValueMgDl = glucoseMgdl,
                durationMinutes = item.optInt("durationMinutes", 0).takeIf { it > 0 },
                intensity = item.optString("intensity", "").ifBlank { null },
                insulinPresetId = null,
                source = JournalEntrySource.MANUAL.storageValue,
                sourceRecordId = "api:$remoteId",
                createdAt = normalizeTimestamp(item.optLong("createdAt", 0L)) ?: now,
                updatedAt = normalizeTimestamp(item.optLong("updatedAt", 0L)) ?: now
            )
        }
        if (imported.isEmpty()) return 0
        HistoryDatabase.getInstance(Applic.app).journalDao().upsertEntries(imported)
        return imported.size
    }

    private fun JournalEntryEntity.toJson(presetsById: Map<Long, JournalInsulinPreset>): JSONObject {
        val type = JournalEntryType.fromStorage(entryType)
        return JSONObject()
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
            .put("source", source)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun activeInsulinUnits(
        entries: List<JournalEntryEntity>,
        presetsById: Map<Long, JournalInsulinPreset>,
        atMillis: Long
    ): Float {
        return entries.sumOf { entry ->
            if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.INSULIN) return@sumOf 0.0
            val preset = entry.insulinPresetId?.let(presetsById::get) ?: return@sumOf 0.0
            if (!preset.countsTowardIob) return@sumOf 0.0
            val amount = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            (amount * remainingCurveFraction(preset.curvePoints, entry.timestamp, atMillis)).toDouble()
        }.toFloat()
    }

    private fun activeCarbsGrams(entries: List<JournalEntryEntity>, atMillis: Long): Float {
        val prefs = Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val absorptionGramsPerHour = prefs
            .getFloat(PREDICTION_CARB_ABSORPTION_KEY, PREDICTION_CARB_ABSORPTION_DEFAULT)
            .coerceIn(10f, 90f)
        return entries.sumOf { entry ->
            if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.CARBS) return@sumOf 0.0
            val grams = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            val absorptionMinutes = (grams / absorptionGramsPerHour * 60f).coerceIn(30f, 360f)
            val progress = linearProgress(entry.timestamp, absorptionMinutes, atMillis)
            (grams * (1f - progress)).coerceAtLeast(0f).toDouble()
        }.toFloat()
    }

    private fun remainingCurveFraction(
        points: List<tk.glucodata.data.journal.JournalCurvePoint>,
        doseTimestamp: Long,
        atMillis: Long
    ): Float {
        if (points.size < 2 || atMillis < doseTimestamp) return 0f
        val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
        val total = integrateCurve(points, points.last().minute.toFloat())
        if (total <= 0.0001f) return 0f
        val delivered = (integrateCurve(points, elapsedMinutes) / total).coerceIn(0f, 1f)
        return (1f - delivered).coerceIn(0f, 1f)
    }

    private fun integrateCurve(
        points: List<tk.glucodata.data.journal.JournalCurvePoint>,
        upToMinute: Float
    ): Float {
        if (points.size < 2 || upToMinute <= points.first().minute) return 0f
        var area = 0f
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (upToMinute <= start.minute) break
            val segmentEndMinute = minOf(upToMinute, end.minute.toFloat())
            val segmentWidth = segmentEndMinute - start.minute
            if (segmentWidth <= 0f) continue
            val fullWidth = (end.minute - start.minute).coerceAtLeast(1).toFloat()
            val endFraction = ((segmentEndMinute - start.minute) / fullWidth).coerceIn(0f, 1f)
            val segmentEndActivity = start.activity + ((end.activity - start.activity) * endFraction)
            area += ((start.activity + segmentEndActivity) * 0.5f) * segmentWidth
            if (upToMinute <= end.minute) break
        }
        return area
    }

    private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        if (atMillis <= startMillis) return 0f
        val elapsedMinutes = (atMillis - startMillis) / 60_000f
        return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun toPresetModel(entity: JournalInsulinPresetEntity): JournalInsulinPreset =
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
            sortOrder = entity.sortOrder
        )

    private fun defaultTitle(type: JournalEntryType): String =
        when (type) {
            JournalEntryType.INSULIN -> Applic.app.getString(R.string.journal_type_insulin)
            JournalEntryType.CARBS -> Applic.app.getString(R.string.carbo)
            JournalEntryType.FINGERSTICK -> Applic.app.getString(R.string.journal_type_fingerstick)
            JournalEntryType.ACTIVITY -> Applic.app.getString(R.string.journal_type_activity)
            JournalEntryType.NOTE -> Applic.app.getString(R.string.journal_type_note)
        }

    private fun finiteOrNull(value: Float?): Any? =
        value?.takeIf { it.isFinite() }?.toDouble()

    private fun firstFiniteAny(vararg values: Double): Double? =
        values.firstOrNull { it.isFinite() }

    private fun firstLong(vararg values: Long): Long =
        values.firstOrNull { it > 0L } ?: 0L

    private fun normalizeTimestamp(raw: Long): Long? {
        if (raw <= 0L) return null
        val millis = if (raw < 10_000_000_000L) raw * 1000L else raw
        return millis.takeIf { it > 0L }
    }
}
