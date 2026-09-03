package tk.glucodata.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.Natives
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.CloneJournalTombstoneEntity
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

object SettingsExporter {
    private const val TAG = "SettingsExporter"
    private const val SCHEMA = "tk.glucodata.settings-export"
    private const val SCHEMA_VERSION = 3

    private val nativeSettingsFiles = listOf(
        "settings.dat",
        "backup.dat",
        "orbackup.dat"
    )

    data class ImportSummary(
        val sharedPreferenceFiles: Int,
        val preferenceValues: Int,
        val nativeFiles: Int,
        val journalEntries: Int = 0,
        val journalInsulinPresets: Int = 0,
        val journalFoods: Int = 0
    )

    private data class JournalImportSummary(
        val entries: Int = 0,
        val insulinPresets: Int = 0,
        val foods: Int = 0
    )

    suspend fun exportToJson(context: Context, uri: Uri): Result<Unit> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildExportPayload(appContext)
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: error("Could not open export destination")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                Log.i(TAG, "Exported settings package")
                Unit
            }.onFailure {
                Log.e(TAG, "Settings export failed", it)
            }
        }
    }

    suspend fun buildExportPayload(
        context: Context,
        includeJournalData: Boolean = true
    ): JSONObject {
        return buildPayload(context.applicationContext, includeJournalData)
    }

    suspend fun isSettingsExport(context: Context, uri: Uri): Boolean {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                readPayload(appContext, uri).optString("schema") == SCHEMA
            }.getOrDefault(false)
        }
    }

    suspend fun importFromJson(context: Context, uri: Uri): Result<ImportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = readPayload(appContext, uri)
                importPayloadUnchecked(appContext, payload)
            }.onFailure {
                Log.e(TAG, "Settings import failed", it)
            }
        }
    }

    suspend fun importPayload(context: Context, payload: JSONObject): Result<ImportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                importPayloadUnchecked(appContext, payload)
            }.onFailure {
                Log.e(TAG, "Settings import failed", it)
            }
        }
    }

    private suspend fun importPayloadUnchecked(
        context: Context,
        payload: JSONObject
    ): ImportSummary {
        require(payload.optString("schema") == SCHEMA) { "Unsupported settings export" }
        val schemaVersion = payload.optInt("schemaVersion", 0)
        require(schemaVersion in 1..SCHEMA_VERSION) {
            "Unsupported settings export version: $schemaVersion"
        }

        val preferencesSummary = importSharedPreferences(
            context,
            payload.optJSONObject("sharedPreferences") ?: JSONObject()
        )
        val nativeFileCount = importNativeSettingsFiles(
            context,
            payload.optJSONObject("nativeSettingsFiles") ?: JSONObject()
        )
        val journalSummary = importJournalData(
            context,
            payload.optJSONObject("journalData")
        )

        return ImportSummary(
            sharedPreferenceFiles = preferencesSummary.first,
            preferenceValues = preferencesSummary.second,
            nativeFiles = nativeFileCount,
            journalEntries = journalSummary.entries,
            journalInsulinPresets = journalSummary.insulinPresets,
            journalFoods = journalSummary.foods
        )
    }

    private suspend fun buildPayload(
        context: Context,
        includeJournalData: Boolean = true
    ): JSONObject {
        return JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("containsSensitiveData", true)
            .put("app", buildAppInfo(context))
            .put("sharedPreferences", buildSharedPreferences(context))
            .put("nativeSettingsFiles", buildNativeSettingsFiles(context))
            .put("nativeTransferSettings", buildNativeTransferSettings())
            .also { payload ->
                if (includeJournalData) {
                    payload.put("journalData", buildJournalData(context))
                }
            }
    }

    private fun buildAppInfo(context: Context): JSONObject {
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("locale", Locale.getDefault().toLanguageTag())
    }

    private fun buildSharedPreferences(context: Context): JSONObject {
        val result = JSONObject()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefFiles = prefsDir
            .listFiles { file -> file.isFile && file.extension == "xml" }
            ?.sortedBy { it.name }
            .orEmpty()

        prefFiles.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .all
                .toSortedMap()
                .forEach { (key, value) ->
                    values.put(key, preferenceEntry(value))
                }

            result.put(
                name,
                JSONObject()
                    .put("byteCount", file.length())
                    .put("lastModifiedEpochMillis", file.lastModified())
                    .put("values", values)
                    .put("rawXmlBase64", encodeFile(file))
            )
        }
        return result
    }

    private fun importSharedPreferences(
        context: Context,
        exportedPreferences: JSONObject
    ): Pair<Int, Int> {
        val importedNames = exportedPreferences.keySet()
        discoverSharedPreferenceNames(context)
            .filterNot { it in importedNames }
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }

        var importedValueCount = 0
        importedNames.sorted().forEach { name ->
            val entry = exportedPreferences.optJSONObject(name) ?: return@forEach
            val values = entry.optJSONObject("values") ?: JSONObject()
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()

            values.keySet().sorted().forEach { key ->
                putPreferenceValue(editor, key, values.getJSONObject(key))
                importedValueCount++
            }

            require(editor.commit()) { "Could not import preferences: $name" }
        }

        return importedNames.size to importedValueCount
    }

    private fun putPreferenceValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        entry: JSONObject
    ) {
        when (entry.optString("type")) {
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "string" -> editor.putString(
                key,
                if (entry.isNull("value")) null else entry.getString("value")
            )
            "string_set" -> {
                val values = entry.optJSONArray("value") ?: JSONArray()
                editor.putStringSet(
                    key,
                    buildSet {
                        for (index in 0 until values.length()) {
                            add(values.getString(index))
                        }
                    }
                )
            }
            "null" -> editor.remove(key)
            else -> throw IllegalArgumentException("Unsupported preference type for $key")
        }
    }

    private fun preferenceEntry(value: Any?): JSONObject {
        return JSONObject()
            .put("type", preferenceType(value))
            .put("value", preferenceValue(value))
    }

    private fun preferenceType(value: Any?): String {
        return when (value) {
            is Boolean -> "boolean"
            is Float -> "float"
            is Int -> "int"
            is Long -> "long"
            is String -> "string"
            is Set<*> -> "string_set"
            null -> "null"
            else -> value.javaClass.name
        }
    }

    private fun preferenceValue(value: Any?): Any {
        return when (value) {
            is Float -> value.toDouble()
            is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted())
            null -> JSONObject.NULL
            else -> value
        }
    }

    private fun buildNativeSettingsFiles(context: Context): JSONObject {
        val result = JSONObject()
        nativeSettingsFiles.forEach { name ->
            val file = File(context.filesDir, name)
            if (file.isFile) {
                result.put(
                    name,
                    JSONObject()
                        .put("byteCount", file.length())
                        .put("lastModifiedEpochMillis", file.lastModified())
                        .put("base64", encodeFile(file))
                )
            }
        }
        return result
    }

    private fun importNativeSettingsFiles(
        context: Context,
        exportedFiles: JSONObject
    ): Int {
        var importedCount = 0
        nativeSettingsFiles.forEach { name ->
            val entry = exportedFiles.optJSONObject(name) ?: return@forEach
            val encoded = entry.optString("base64").takeIf { it.isNotBlank() } ?: return@forEach
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            File(context.filesDir, name).outputStream().use { output ->
                output.write(bytes)
            }
            importedCount++
        }
        return importedCount
    }

    private fun buildNativeTransferSettings(): JSONObject {
        return JSONObject().also { result ->
            runCatching { Natives.bytesettings() }
                .getOrNull()
                ?.let { bytes ->
                    result.put("byteCount", bytes.size)
                    result.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
        }
    }

    private suspend fun buildJournalData(context: Context): JSONObject {
        val journalDao = HistoryDatabase.getInstance(context).journalDao()
        return JSONObject()
            .put(
                "entries",
                JSONArray().also { array ->
                    journalDao.getEntries().forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "insulinPresets",
                JSONArray().also { array ->
                    journalDao.getInsulinPresets().forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "foods",
                JSONArray().also { array ->
                    journalDao.getFoods().forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "cloneTombstones",
                JSONArray().also { array ->
                    journalDao.getCloneJournalTombstones().forEach { tombstone ->
                        array.put(
                            JSONObject()
                                .put("entryId", tombstone.entryId)
                                .put("deletedAt", tombstone.deletedAt)
                        )
                    }
                }
            )
    }

    private suspend fun importJournalData(context: Context, journalData: JSONObject?): JournalImportSummary {
        if (journalData == null) return JournalImportSummary()
        val entries = journalData.optJSONArray("entries").toJournalEntries()
        val insulinPresets = journalData.optJSONArray("insulinPresets").toInsulinPresets()
        val hasFoods = journalData.has("foods")
        val foods = journalData.optJSONArray("foods").toFoods()
        val cloneTombstones = journalData.optJSONArray("cloneTombstones").toCloneTombstones()
        val journalDao = HistoryDatabase.getInstance(context).journalDao()

        journalDao.deleteAllEntries()
        journalDao.deleteAllInsulinPresets()
        journalDao.deleteAllCloneJournalTombstones()
        if (hasFoods) {
            journalDao.deleteAllFoods()
            if (foods.isNotEmpty()) {
                journalDao.insertFoods(foods)
            }
        }
        if (insulinPresets.isNotEmpty()) {
            journalDao.insertInsulinPresets(insulinPresets)
        }
        if (entries.isNotEmpty()) {
            journalDao.upsertEntries(entries)
        }
        if (cloneTombstones.isNotEmpty()) {
            journalDao.upsertCloneJournalTombstones(cloneTombstones)
        }
        return JournalImportSummary(
            entries = entries.size,
            insulinPresets = insulinPresets.size,
            foods = if (hasFoods) foods.size else 0
        )
    }

    private fun JournalEntryEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("timestamp", timestamp)
            .putNullable("sensorSerial", sensorSerial)
            .put("entryType", entryType)
            .put("title", title)
            .putNullable("note", note)
            .putNullable("amount", amount)
            .putNullable("glucoseValueMgDl", glucoseValueMgDl)
            .putNullable("durationMinutes", durationMinutes)
            .putNullable("intensity", intensity)
            .putNullable("insulinPresetId", insulinPresetId)
            .putNullable("foodId", foodId)
            .putNullable("proteinGrams", proteinGrams)
            .putNullable("fatGrams", fatGrams)
            .put("source", source)
            .putNullable("originSource", originSource)
            .putNullable("sourceRecordId", sourceRecordId)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun JournalInsulinPresetEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("onsetMinutes", onsetMinutes)
            .put("durationMinutes", durationMinutes)
            .put("accentColor", accentColor)
            .put("curveJson", curveJson)
            .put("isBuiltIn", isBuiltIn)
            .put("isArchived", isArchived)
            .put("countsTowardIob", countsTowardIob)
            .put("useForCalculation", useForCalculation)
            .put("sortOrder", sortOrder)
    }

    private fun JournalFoodEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("carbsGrams", carbsGrams)
            .putNullable("proteinGrams", proteinGrams)
            .putNullable("fatGrams", fatGrams)
            .put("absorptionMinutes", absorptionMinutes)
            .put("accentColor", accentColor)
            .put("isBuiltIn", isBuiltIn)
            .put("isArchived", isArchived)
            .put("sortOrder", sortOrder)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun JSONArray?.toJournalEntries(): List<JournalEntryEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    JournalEntryEntity(
                        id = item.optLong("id", 0L),
                        timestamp = item.getLong("timestamp"),
                        sensorSerial = item.optNullableString("sensorSerial"),
                        entryType = item.getString("entryType"),
                        title = item.getString("title"),
                        note = item.optNullableString("note"),
                        amount = item.optNullableFloat("amount"),
                        glucoseValueMgDl = item.optNullableFloat("glucoseValueMgDl"),
                        durationMinutes = item.optNullableInt("durationMinutes"),
                        intensity = item.optNullableString("intensity"),
                        insulinPresetId = item.optNullableLong("insulinPresetId"),
                        foodId = item.optNullableLong("foodId"),
                        proteinGrams = item.optNullableFloat("proteinGrams"),
                        fatGrams = item.optNullableFloat("fatGrams"),
                        source = item.optString("source", "import"),
                        originSource = item.optNullableString("originSource"),
                        sourceRecordId = item.optNullableString("sourceRecordId"),
                        createdAt = item.optLong("createdAt", item.getLong("timestamp")),
                        updatedAt = item.optLong("updatedAt", item.getLong("timestamp"))
                    )
                )
            }
        }
    }

    private fun JSONArray?.toCloneTombstones(): List<CloneJournalTombstoneEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val entryId = item.optLong("entryId", 0L)
                val deletedAt = item.optLong("deletedAt", 0L)
                if (entryId > 0L && deletedAt > 0L) {
                    add(CloneJournalTombstoneEntity(entryId = entryId, deletedAt = deletedAt))
                }
            }
        }
    }

    private fun JSONArray?.toFoods(): List<JournalFoodEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    JournalFoodEntity(
                        id = item.optLong("id", 0L),
                        displayName = item.getString("displayName"),
                        carbsGrams = item.optDouble("carbsGrams", 0.0).toFloat(),
                        proteinGrams = item.optNullableFloat("proteinGrams"),
                        fatGrams = item.optNullableFloat("fatGrams"),
                        absorptionMinutes = item.optInt("absorptionMinutes", 90),
                        accentColor = item.optInt("accentColor", 0xFF5F7D4B.toInt()),
                        isBuiltIn = item.optBoolean("isBuiltIn", false),
                        isArchived = item.optBoolean("isArchived", false),
                        sortOrder = item.optInt("sortOrder", index),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    private fun JSONArray?.toInsulinPresets(): List<JournalInsulinPresetEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    JournalInsulinPresetEntity(
                        id = item.optLong("id", 0L),
                        displayName = item.getString("displayName"),
                        onsetMinutes = item.getInt("onsetMinutes"),
                        durationMinutes = item.getInt("durationMinutes"),
                        accentColor = item.getInt("accentColor"),
                        curveJson = item.optString("curveJson", ""),
                        isBuiltIn = item.optBoolean("isBuiltIn", false),
                        isArchived = item.optBoolean("isArchived", false),
                        countsTowardIob = item.optBoolean("countsTowardIob", true),
                        sortOrder = item.optInt("sortOrder", index),
                        useForCalculation = item.optBoolean(
                            "useForCalculation",
                            !(item.optBoolean("isBuiltIn", false) &&
                                item.optInt("sortOrder", index) in setOf(1, 10))
                        )
                    )
                )
            }
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else optString(name)
    }

    private fun JSONObject.optNullableFloat(name: String): Float? {
        return if (isNull(name) || !has(name)) null else optDouble(name).toFloat()
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (isNull(name) || !has(name)) null else optInt(name)
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (isNull(name) || !has(name)) null else optLong(name)
    }

    private fun encodeFile(file: File): String {
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun readPayload(context: Context, uri: Uri): JSONObject {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open import source")
        val text = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun discoverSharedPreferenceNames(context: Context): Set<String> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return prefsDir
            .listFiles { file -> file.isFile && file.extension == "xml" }
            ?.mapTo(mutableSetOf()) { it.name.removeSuffix(".xml") }
            .orEmpty()
    }

    private fun JSONObject.keySet(): Set<String> {
        val keys = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            keys.add(iterator.next())
        }
        return keys
    }
}
