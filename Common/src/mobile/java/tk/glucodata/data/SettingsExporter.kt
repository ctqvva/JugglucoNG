package tk.glucodata.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.Natives
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.CloneJournalTombstoneEntity
import tk.glucodata.data.journal.CloneJournalIdentity
import tk.glucodata.data.food.FoodDetailsBackup
import tk.glucodata.data.food.FoodDetailsEntity
import tk.glucodata.data.food.FoodLibrary
import tk.glucodata.data.food.FoodLibraryDatabase
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalFoodRestoreCommit
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.coroutines.coroutineContext

object SettingsExporter {
    private const val TAG = "SettingsExporter"

    /**
     * Preference files too sensitive to export: vendor credentials, and restricted mode (a
     * restored backup must not lift a child's restrictions, and its PIN hash is not something
     * to hand around in a file). They never enter an export (values or raw XML) and are
     * therefore never restored by an import either.
     */
    internal val SECRET_PREFERENCE_FILES = setOf(
        "lumiflex_cloud", "ican_cloud", "dexcom_share", tk.glucodata.RestrictedMode.PREFS_NAME,
    )

    internal fun isSecretPreferenceFile(name: String): Boolean =
        name in SECRET_PREFERENCE_FILES
    internal const val SCHEMA = "tk.glucodata.settings-export"
    private const val SCHEMA_VERSION = 4
    private val importMutex = Mutex()

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

    internal data class ValidationSummary(
        val sharedPreferenceFiles: Int,
        val preferenceValues: Int,
        val nativeFiles: Int,
        val journalEntries: Int,
        val journalInsulinPresets: Int,
        val journalFoods: Int
    )

    private data class JournalRestoreData(
        val entries: List<JournalEntryEntity>,
        val insulinPresets: List<JournalInsulinPresetEntity>,
        val foods: List<JournalFoodEntity>?,
        val foodDetails: List<FoodDetailsEntity>,
        val cloneTombstones: List<CloneJournalTombstoneEntity>
    )

    private data class ValidatedImport(
        val preferences: JSONObject,
        val nativeFiles: JSONObject,
        val journal: JournalRestoreData?,
        val preferenceValueCount: Int
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

    suspend fun importFromJson(context: Context, uri: Uri): Result<ImportSummary> =
        importSettings(context, requireJournalData = true) { readPayload(it, uri) }

    suspend fun importPayload(
        context: Context,
        payload: JSONObject,
        requireJournalData: Boolean = true
    ): Result<ImportSummary> = importSettings(context, requireJournalData) { payload }

    private suspend fun importSettings(
        context: Context,
        requireJournalData: Boolean,
        read: (Context) -> JSONObject
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val replacement = parseImport(read(appContext), requireJournalData)
            val summary = importMutex.withLock {
                coroutineContext.ensureActive()
                restoreSettings(appContext, replacement)
            }
            coroutineContext.ensureActive()
            Result.success(summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Settings import failed", failure)
            Result.failure(failure)
        }
    }

    internal fun validatePayload(
        payload: JSONObject,
        requireJournalData: Boolean = true
    ): ValidationSummary {
        val replacement = parseImport(payload, requireJournalData)
        return ValidationSummary(
            sharedPreferenceFiles = replacement.preferences.length(),
            preferenceValues = replacement.preferenceValueCount,
            nativeFiles = replacement.nativeFiles.length(),
            journalEntries = replacement.journal?.entries?.size ?: 0,
            journalInsulinPresets = replacement.journal?.insulinPresets?.size ?: 0,
            journalFoods = replacement.journal?.foods?.size ?: 0
        )
    }

    private fun parseImport(payload: JSONObject, requireJournalData: Boolean): ValidatedImport {
        require(payload.optString("schema") == SCHEMA) { "Unsupported settings export" }
        val schemaVersion = payload.optInt("schemaVersion", 0)
        require(schemaVersion in 1..SCHEMA_VERSION) {
            "Unsupported settings export version: $schemaVersion"
        }

        val exportedPreferences = payload.optJSONObject("sharedPreferences")
            ?: error("Settings preferences are missing")
        var preferenceValueCount = 0
        exportedPreferences.keySet().forEach { name ->
            val entry = exportedPreferences.optJSONObject(name)
                ?: error("Invalid shared preferences file: $name")
            val values = entry.optJSONObject("values")
                ?: error("Preference values are missing: $name")
            values.keySet().forEach { key ->
                validatePreferenceEntry(key, values.optJSONObject(key))
                preferenceValueCount++
            }
        }

        val exportedFiles = payload.optJSONObject("nativeSettingsFiles")
            ?: error("Native settings files are missing")
        exportedFiles.keySet().forEach { name ->
            require(name in nativeSettingsFiles) { "Unsupported native settings file: $name" }
            val entry = exportedFiles.optJSONObject(name)
                ?: error("Invalid native settings file: $name")
            val encoded = entry.optString("base64").takeIf { it.isNotBlank() }
                ?: error("Native settings data is missing: $name")
            java.util.Base64.getMimeDecoder().decode(encoded)
        }

        val journalData = if (payload.has("journalData")) payload.getJSONObject("journalData") else null
        if (requireJournalData && schemaVersion >= 2) {
            require(journalData != null) { "Settings journal data is missing" }
        }
        val journal = journalData?.let { parseJournal(it, schemaVersion) }
        return ValidatedImport(exportedPreferences, exportedFiles, journal, preferenceValueCount)
    }

    private fun parseJournal(data: JSONObject, schemaVersion: Int): JournalRestoreData {
        val entries = data.getJSONArray("entries").toJournalEntries()
        val presets = data.getJSONArray("insulinPresets").toInsulinPresets()
        val foods = data.optionalArray("foods")?.toFoods()
        if (schemaVersion >= 3) require(foods != null) { "Settings food presets are missing" }
        val details = data.optionalArray(FoodDetailsBackup.KEY)
        require(details == null || foods != null) { "Food details require food presets" }
        val parsedDetails = FoodDetailsBackup.fromJson(details, strict = true)
        val tombstones = data.optionalArray("cloneTombstones").toCloneTombstones()

        val foodIds = foods.orEmpty().map { it.id }.filter { it != 0L }
        requireUnique("journal entry IDs", entries.map { it.id }.filter { it != 0L })
        requireUnique("journal source IDs", entries.mapNotNull { it.sourceRecordId })
        requireUnique("journal recovery IDs", entries.mapNotNull { it.recoveryId })
        requireUnique("insulin preset IDs", presets.map { it.id }.filter { it != 0L })
        requireUnique("food IDs", foodIds)
        requireUnique("food detail IDs", parsedDetails.map { it.foodId })
        requireUnique("Clone tombstone IDs", tombstones.map { it.entryId })

        // Older exports could include orphaned details. Only restore details with a food row.
        val restoredFoodIds = foodIds.toHashSet()
        return JournalRestoreData(entries, presets, foods, parsedDetails.filter { it.foodId in restoredFoodIds }, tombstones)
    }

    private suspend fun restoreSettings(context: Context, replacement: ValidatedImport): ImportSummary {
        val preferencesSummary = importSharedPreferences(context, replacement.preferences)
        coroutineContext.ensureActive()
        val nativeFileCount = importNativeSettingsFiles(context, replacement.nativeFiles)
        coroutineContext.ensureActive()
        val journal = replacement.journal
        if (journal != null) {
            val database = HistoryDatabase.getInstance(context)
            val foodLibrary = journal.foods?.let {
                FoodLibrary(database.journalDao(), FoodLibraryDatabase.getInstance(context).foodDetailsDao())
            }
            replaceJournal(database, foodLibrary, journal)
        }

        return ImportSummary(
            sharedPreferenceFiles = preferencesSummary.first,
            preferenceValues = preferencesSummary.second,
            nativeFiles = nativeFileCount,
            journalEntries = journal?.entries?.size ?: 0,
            journalInsulinPresets = journal?.insulinPresets?.size ?: 0,
            journalFoods = journal?.foods?.size ?: 0
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
            .listFiles { file ->
                file.isFile && file.extension == "xml" &&
                    !isSecretPreferenceFile(file.name.removeSuffix(".xml"))
            }
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

    internal fun importSharedPreferences(
        context: Context,
        exportedPreferences: JSONObject,
    ): Pair<Int, Int> {
        val aidexName = "AiDexNativePrefs"
        fun historyKey(key: String) = key.startsWith("algorithmAppliedMode_") ||
            key.startsWith("algorithmEverAdaptive_") || key.startsWith("algorithmSourceBootstrapped_")
        // Settings change requests, not the provenance of retained glucose.
        fun importEditor(name: String): android.content.SharedPreferences.Editor {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (name == aidexName) {
                // Leave ownership keys untouched, including changes committed
                // by a writer during import; don't restore an earlier snapshot.
                prefs.all.keys.filterNot(::historyKey).forEach(editor::remove)
            } else editor.clear()
            return editor
        }
        val importedNames = exportedPreferences.keySet().filterNot(::isSecretPreferenceFile)
        discoverSharedPreferenceNames(context)
            .filterNot { it in importedNames }
            .forEach { name ->
                importEditor(name).commit()
            }

        var importedValueCount = 0
        importedNames.sorted().forEach { name ->
            val entry = exportedPreferences.optJSONObject(name) ?: return@forEach
            val values = entry.optJSONObject("values") ?: JSONObject()
            val editor = importEditor(name)

            values.keySet().sorted().forEach valueLoop@ { key ->
                if (name == aidexName && historyKey(key)) return@valueLoop
                putPreferenceValue(editor, key, values.getJSONObject(key))
                if (name == aidexName && key.startsWith("algorithmMode_")) {
                    // An imported request cannot claim that local history has
                    // already been rewritten, even when the backup lacks a mode.
                    val appliedKey = key.replaceFirst("algorithmMode_", "algorithmAppliedMode_")
                    if (!context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(appliedKey)) editor.putInt(appliedKey, 0)
                }
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

    private fun validatePreferenceEntry(key: String, entry: JSONObject?) {
        require(entry != null) { "Invalid preference value: $key" }
        when (entry.optString("type")) {
            "boolean" -> entry.getBoolean("value")
            "float" -> entry.getDouble("value")
            "int" -> entry.getInt("value")
            "long" -> entry.getLong("value")
            "string" -> if (!entry.isNull("value")) entry.getString("value")
            "string_set" -> {
                val values = entry.getJSONArray("value")
                for (index in 0 until values.length()) values.getString(index)
            }
            "null" -> Unit
            else -> throw IllegalArgumentException("Unsupported preference type for $key")
        }
    }

    private fun requireUnique(label: String, ids: List<Any>) {
        require(ids.toSet().size == ids.size) { "Duplicate $label in settings backup" }
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
        val (foods, foodDetails) = FoodLibrary(journalDao, FoodLibraryDatabase.getInstance(context).foodDetailsDao()).snapshot()
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
                    foods.forEach { array.put(it.toJson()) }
                }
            )
            // What the food library knows beyond those rows. An older build skips the key and
            // reads every food as the plain preset it used to be.
            .put(
                FoodDetailsBackup.KEY,
                FoodDetailsBackup.toJson(foodDetails)
            )
            .put(
                "cloneTombstones",
                JSONArray().also { array ->
                    journalDao.getCloneJournalTombstones().forEach { tombstone ->
                        array.put(
                            JSONObject()
                                .put("entryId", tombstone.entryId)
                                .putNullable("recoveryId", tombstone.recoveryId)
                                .put("deletedAt", tombstone.deletedAt)
                        )
                    }
                }
            )
    }

    private suspend fun replaceJournal(
        database: HistoryDatabase,
        foodLibrary: FoodLibrary?,
        journal: JournalRestoreData
    ) {
        suspend fun commitJournal(foodRestoreToken: String?) = database.withTransaction {
            val dao = database.journalDao()
            dao.deleteAllEntries()
            dao.deleteAllInsulinPresets()
            dao.deleteAllCloneJournalTombstones()
            journal.foods?.let {
                dao.deleteAllFoods()
                dao.insertFoods(it.sortedBy { food -> food.id == 0L })
            }
            // Insert explicit IDs before allocating missing IDs from legacy backups.
            dao.insertInsulinPresets(journal.insulinPresets.sortedBy { it.id == 0L })
            dao.upsertEntries(journal.entries.sortedBy { it.id == 0L })
            dao.upsertCloneJournalTombstones(journal.cloneTombstones)
            if (foodRestoreToken != null) {
                dao.recordFoodRestoreCommit(JournalFoodRestoreCommit(token = foodRestoreToken))
            }
            coroutineContext.ensureActive()
        }
        if (foodLibrary == null) commitJournal(null)
        else foodLibrary.restoreWithJournal(journal.foodDetails, ::commitJournal)
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
            .putNullable("recoveryId", recoveryId)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .putNullable("insulinCurveJsonSnapshot", insulinCurveJsonSnapshot)
            .putNullable("insulinCurveProfileId", insulinCurveProfileId)
            .putNullable("insulinCurveModelVersion", insulinCurveModelVersion)
            .putNullable("insulinCurveEvidence", insulinCurveEvidence)
            .putNullable("insulinBodyWeightKg", insulinBodyWeightKg)
            .put("insulinCurveWasApproximated", insulinCurveWasApproximated)
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
            .putNullable("curveProfileId", curveProfileId)
            .put("curveModelVersion", curveModelVersion)
            .put("curveEvidence", curveEvidence)
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

    private fun JSONArray.toJournalEntries(): List<JournalEntryEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                val timestamp = item.backupLong("timestamp")
                add(
                    JournalEntryEntity(
                        id = item.backupRowId(),
                        timestamp = timestamp,
                        sensorSerial = item.optNullableString("sensorSerial"),
                        entryType = item.backupString("entryType"),
                        title = item.backupString("title"),
                        note = item.optNullableString("note"),
                        amount = item.optNullableFloat("amount"),
                        glucoseValueMgDl = item.optNullableFloat("glucoseValueMgDl"),
                        durationMinutes = item.optNullableInt("durationMinutes"),
                        intensity = item.optNullableString("intensity"),
                        insulinPresetId = item.optNullableLong("insulinPresetId"),
                        foodId = item.optNullableLong("foodId"),
                        proteinGrams = item.optNullableFloat("proteinGrams"),
                        fatGrams = item.optNullableFloat("fatGrams"),
                        source = item.backupString("source", "import"),
                        originSource = item.optNullableString("originSource"),
                        sourceRecordId = item.optNullableString("sourceRecordId"),
                        recoveryId = item.backupRecoveryId() ?: CloneJournalIdentity.newRecoveryId(),
                        createdAt = item.backupLong("createdAt", timestamp),
                        updatedAt = item.backupLong("updatedAt", timestamp),
                        insulinCurveJsonSnapshot = item.optNullableString("insulinCurveJsonSnapshot"),
                        insulinCurveProfileId = item.optNullableString("insulinCurveProfileId"),
                        insulinCurveModelVersion = item.optNullableInt("insulinCurveModelVersion"),
                        insulinCurveEvidence = item.optNullableString("insulinCurveEvidence"),
                        insulinBodyWeightKg = item.optNullableFloat("insulinBodyWeightKg"),
                        insulinCurveWasApproximated = item.backupBoolean(
                            "insulinCurveWasApproximated",
                            false
                        )
                    )
                )
            }
        }
    }

    private fun JSONArray?.toCloneTombstones(): List<CloneJournalTombstoneEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                val entryId = item.backupLong("entryId")
                val deletedAt = item.backupLong("deletedAt")
                require(entryId > 0L && deletedAt > 0L) { "Invalid Clone journal tombstone" }
                add(
                    CloneJournalTombstoneEntity(
                        entryId = entryId,
                        deletedAt = deletedAt,
                        recoveryId = item.backupRecoveryId(),
                    )
                )
            }
        }
    }

    private fun JSONArray.toFoods(): List<JournalFoodEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    JournalFoodEntity(
                        id = item.backupRowId(),
                        displayName = item.backupString("displayName"),
                        carbsGrams = item.backupFloat("carbsGrams", 0f),
                        proteinGrams = item.optNullableFloat("proteinGrams"),
                        fatGrams = item.optNullableFloat("fatGrams"),
                        absorptionMinutes = item.backupInt("absorptionMinutes", 90),
                        accentColor = item.backupInt("accentColor", 0xFF5F7D4B.toInt()),
                        isBuiltIn = item.backupBoolean("isBuiltIn", false),
                        isArchived = item.backupBoolean("isArchived", false),
                        sortOrder = item.backupInt("sortOrder", index),
                        createdAt = item.backupLong("createdAt", System.currentTimeMillis()),
                        updatedAt = item.backupLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    private fun JSONArray.toInsulinPresets(): List<JournalInsulinPresetEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                val isBuiltIn = item.backupBoolean("isBuiltIn", false)
                val sortOrder = item.backupInt("sortOrder", index)
                add(
                    JournalInsulinPresetEntity(
                        id = item.backupRowId(),
                        displayName = item.backupString("displayName"),
                        onsetMinutes = item.backupInt("onsetMinutes"),
                        durationMinutes = item.backupInt("durationMinutes"),
                        accentColor = item.backupInt("accentColor"),
                        curveJson = item.backupString("curveJson", ""),
                        isBuiltIn = isBuiltIn,
                        isArchived = item.backupBoolean("isArchived", false),
                        countsTowardIob = item.backupBoolean("countsTowardIob", true),
                        sortOrder = sortOrder,
                        useForCalculation = item.backupBoolean("useForCalculation", !(isBuiltIn && sortOrder in setOf(1, 10))),
                        curveProfileId = item.optNullableString("curveProfileId"),
                        curveModelVersion = item.backupInt("curveModelVersion", 0),
                        curveEvidence = item.backupString("curveEvidence", "unverified")
                    )
                )
            }
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else backupString(name)
    }

    private fun JSONObject.optNullableFloat(name: String): Float? {
        return if (isNull(name)) null else backupFloat(name)
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (isNull(name)) null else backupInt(name)
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (isNull(name)) null else backupLong(name)
    }

    private fun JSONObject.optionalArray(name: String): JSONArray? =
        if (has(name)) getJSONArray(name) else null

    private fun JSONObject.backupRecoveryId(): String? =
        optNullableString("recoveryId")?.let {
            requireNotNull(CloneJournalIdentity.normalizeRecoveryId(it)) { "Invalid journal recovery ID" }
        }

    private fun JSONObject.backupRowId(): Long = backupLong("id", 0L).also {
        require(it >= 0L) { "Invalid backup row ID" }
    }

    private fun encodeFile(file: File): String {
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun readPayload(context: Context, uri: Uri): JSONObject {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open import source")
        val text = ExportPackageCodec.readUtf8(inputStream)
        return JSONObject(text)
    }

    private fun discoverSharedPreferenceNames(context: Context): Set<String> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return prefsDir
            .listFiles { file -> file.isFile && file.extension == "xml" }
            ?.mapTo(mutableSetOf()) { it.name.removeSuffix(".xml") }
            ?.filterNot(::isSecretPreferenceFile)
            ?.toMutableSet()
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
