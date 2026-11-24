package tk.glucodata.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

object SettingsExporter {
    private const val TAG = "SettingsExporter"
    private const val AIDEX_PREFS_NAME = "AiDexNativePrefs"

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

    private data class JournalImportSummary(
        val entries: Int = 0,
        val insulinPresets: Int = 0,
        val foods: Int = 0
    )

    private sealed interface ValidatedPreferenceValue {
        data class BooleanValue(val value: Boolean) : ValidatedPreferenceValue
        data class FloatValue(val value: Float) : ValidatedPreferenceValue
        data class IntValue(val value: Int) : ValidatedPreferenceValue
        data class LongValue(val value: Long) : ValidatedPreferenceValue
        data class StringValue(val value: String?) : ValidatedPreferenceValue
        data class StringSetValue(val value: Set<String>) : ValidatedPreferenceValue
        data object NullValue : ValidatedPreferenceValue
    }

    private data class ValidatedNativeFile(
        val name: String,
        val bytes: ByteArray
    )

    private data class ValidatedJournalData(
        val entries: List<JournalEntryEntity>,
        val insulinPresets: List<JournalInsulinPresetEntity>,
        val hasFoods: Boolean,
        val foods: List<JournalFoodEntity>,
        val foodDetails: List<FoodDetailsEntity>,
        val cloneTombstones: List<CloneJournalTombstoneEntity>
    )

    private data class ValidatedImport(
        val preferences: Map<String, Map<String, ValidatedPreferenceValue>>,
        val preferenceValueCount: Int,
        val nativeFiles: List<ValidatedNativeFile>,
        val journal: ValidatedJournalData?
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

    internal suspend fun importPayloadForTest(
        context: Context,
        payload: JSONObject,
        requireJournalData: Boolean = true,
        writeNativeFile: (File, ByteArray) -> Unit = ::defaultWriteNativeFile,
        replaceNativeFile: (File, File, ByteArray) -> Unit = ::defaultReplaceNativeFile
    ): Result<ImportSummary> = importSettings(
        context = context,
        requireJournalData = requireJournalData,
        writeNativeFile = writeNativeFile,
        replaceNativeFile = replaceNativeFile
    ) { payload }

    private suspend fun importSettings(
        context: Context,
        requireJournalData: Boolean,
        writeNativeFile: (File, ByteArray) -> Unit = ::defaultWriteNativeFile,
        replaceNativeFile: (File, File, ByteArray) -> Unit = ::defaultReplaceNativeFile,
        read: (Context) -> JSONObject
    ): Result<ImportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            try {
                val payload = read(appContext)
                val validated = parseImport(payload, requireJournalData)
                val summary = importMutex.withLock {
                    restoreSettings(appContext, validated, writeNativeFile, replaceNativeFile)
                }
                Result.success(summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e(TAG, "Settings import failed", failure)
                Result.failure(failure)
            }
        }
    }

    internal fun validatePayload(
        payload: JSONObject,
        requireJournalData: Boolean = true
    ): ValidationSummary {
        val validated = parseImport(payload, requireJournalData)
        return ValidationSummary(
            sharedPreferenceFiles = validated.preferences.size,
            preferenceValues = validated.preferenceValueCount,
            nativeFiles = validated.nativeFiles.size,
            journalEntries = validated.journal?.entries?.size ?: 0,
            journalInsulinPresets = validated.journal?.insulinPresets?.size ?: 0,
            journalFoods = validated.journal?.foods?.size ?: 0
        )
    }

    private fun parseImport(
        payload: JSONObject,
        requireJournalData: Boolean
    ): ValidatedImport {
        val schema = payload.opt("schema") as? String
        require(schema == SCHEMA) { "Unsupported settings export" }
        val schemaVersion = payload.strictInt("schemaVersion")
            ?: throw IllegalArgumentException(
                "Unsupported settings export version: ${payload.opt("schemaVersion")}"
            )
        require(schemaVersion in 1..SCHEMA_VERSION) {
            "Unsupported settings export version: $schemaVersion"
        }

        require(payload.has("sharedPreferences")) { "Settings preferences are missing" }
        val exportedPreferences = payload.opt("sharedPreferences") as? JSONObject
            ?: throw IllegalArgumentException("Invalid shared preferences section")
        val (parsedPreferences, preferenceValueCount) = parseSharedPreferences(exportedPreferences)

        require(payload.has("nativeSettingsFiles")) { "Native settings files are missing" }
        val exportedFiles = payload.opt("nativeSettingsFiles") as? JSONObject
            ?: throw IllegalArgumentException("Invalid native settings files section")
        val parsedNativeFiles = parseNativeSettingsFiles(exportedFiles)

        val journalData = if (payload.has("journalData")) {
            payload.opt("journalData") as? JSONObject
                ?: throw IllegalArgumentException("Invalid settings journal data")
        } else {
            null
        }
        if (requireJournalData && schemaVersion >= 2) {
            require(journalData != null) { "Settings journal data is missing" }
        }
        val parsedJournal = journalData?.let { parseJournalData(it, schemaVersion) }

        return ValidatedImport(
            preferences = parsedPreferences,
            preferenceValueCount = preferenceValueCount,
            nativeFiles = parsedNativeFiles,
            journal = parsedJournal
        )
    }

    private fun parseSharedPreferences(
        exportedPreferences: JSONObject
    ): Pair<Map<String, Map<String, ValidatedPreferenceValue>>, Int> {
        val parsedFiles = LinkedHashMap<String, Map<String, ValidatedPreferenceValue>>()
        var preferenceValueCount = 0
        exportedPreferences.keySet().sorted().forEach { name ->
            require(isValidPreferenceFileName(name)) {
                "Invalid shared preferences file name: $name"
            }
            val entry = exportedPreferences.opt(name) as? JSONObject
                ?: throw IllegalArgumentException("Invalid shared preferences file: $name")
            require(entry.has("values")) { "Preference values are missing: $name" }
            val values = entry.opt("values") as? JSONObject
                ?: throw IllegalArgumentException("Invalid preference values: $name")
            val parsedValues = LinkedHashMap<String, ValidatedPreferenceValue>()
            values.keySet().sorted().forEach { key ->
                val valueEntry = values.opt(key) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid preference value: $key")
                parsedValues[key] = parsePreferenceEntry(key, valueEntry)
                preferenceValueCount++
            }
            parsedFiles[name] = parsedValues
        }
        return parsedFiles to preferenceValueCount
    }

    private fun parseNativeSettingsFiles(exportedFiles: JSONObject): List<ValidatedNativeFile> {
        exportedFiles.keySet().forEach { name ->
            require(name in nativeSettingsFiles) { "Unsupported native settings file: $name" }
        }
        return buildList {
            nativeSettingsFiles.forEach { name ->
                if (!exportedFiles.has(name)) return@forEach
                val entry = exportedFiles.opt(name) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid native settings file: $name")
                val encoded = (entry.opt("base64") as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Native settings data is missing: $name")
                val bytes = decodeNativeSettingsBase64(name, encoded)
                add(ValidatedNativeFile(name, bytes))
            }
        }
    }

    private fun parseJournalData(journalData: JSONObject, schemaVersion: Int): ValidatedJournalData {
        require(journalData.has("entries")) { "Settings journal entries are missing" }
        val journalEntries = journalData.opt("entries") as? JSONArray
            ?: throw IllegalArgumentException("Invalid settings journal entries")
        require(journalData.has("insulinPresets")) { "Settings insulin presets are missing" }
        val insulinPresets = journalData.opt("insulinPresets") as? JSONArray
            ?: throw IllegalArgumentException("Invalid settings insulin presets")

        val hasFoods = journalData.has("foods")
        val foods = if (hasFoods) {
            journalData.opt("foods") as? JSONArray
                ?: throw IllegalArgumentException("Invalid settings food presets")
        } else {
            null
        }
        if (schemaVersion >= 3) {
            require(foods != null) { "Settings food presets are missing" }
        }

        val foodDetailsArray = if (journalData.has(FoodDetailsBackup.KEY)) {
            require(foods != null) { "Food details require food presets" }
            journalData.opt(FoodDetailsBackup.KEY) as? JSONArray
                ?: throw IllegalArgumentException("Invalid settings food details")
        } else {
            null
        }
        val parsedFoodDetails = parseFoodDetails(foodDetailsArray)

        val cloneTombstonesArray = if (journalData.has("cloneTombstones")) {
            journalData.opt("cloneTombstones") as? JSONArray
                ?: throw IllegalArgumentException("Invalid Clone journal tombstones")
        } else {
            null
        }

        val parsedEntries = journalEntries.toJournalEntries()
        val parsedInsulins = insulinPresets.toInsulinPresets()
        val parsedFoods = foods?.toFoods().orEmpty()
        val parsedTombstones = cloneTombstonesArray?.toCloneTombstones().orEmpty()

        return ValidatedJournalData(
            entries = parsedEntries,
            insulinPresets = parsedInsulins,
            hasFoods = hasFoods,
            foods = parsedFoods,
            foodDetails = parsedFoodDetails,
            cloneTombstones = parsedTombstones
        )
    }

    private fun parseFoodDetails(array: JSONArray?): List<FoodDetailsEntity> {
        if (array == null) return emptyList()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("Invalid food detail record at index $index")
            val foodId = item.strictLong("foodId")
            require(foodId != null && foodId > 0L) { "Invalid food detail ID at index $index" }
        }
        val parsed = FoodDetailsBackup.fromJson(array)
        require(parsed.size == array.length()) {
            "settings food details contains ${array.length() - parsed.size} invalid record(s)"
        }
        return parsed
    }

    private suspend fun restoreSettings(
        context: Context,
        replacement: ValidatedImport,
        writeNativeFile: (File, ByteArray) -> Unit,
        replaceNativeFile: (File, File, ByteArray) -> Unit
    ): ImportSummary {
        val nativeTx = NativeFilesTransaction(
            filesDir = context.filesDir,
            files = replacement.nativeFiles,
            writeFile = writeNativeFile,
            replaceFile = replaceNativeFile
        )
        val nativeFileCount = nativeTx.commit()
        var prefsRollback: SharedPreferencesRollback? = null
        try {
            val (preferencesSummary, rollback) = applySharedPreferences(context, replacement.preferences)
            prefsRollback = rollback
            val journalSummary = importJournalData(context, replacement.journal)
            return ImportSummary(
                sharedPreferenceFiles = preferencesSummary.first,
                preferenceValues = preferencesSummary.second,
                nativeFiles = nativeFileCount,
                journalEntries = journalSummary.entries,
                journalInsulinPresets = journalSummary.insulinPresets,
                journalFoods = journalSummary.foods
            )
        } catch (failure: Throwable) {
            try {
                prefsRollback?.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            try {
                nativeTx.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
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
        val (parsedPreferences, _) = parseSharedPreferences(exportedPreferences)
        return applySharedPreferences(context, parsedPreferences).first
    }

    private fun isAiDexHistoryKey(key: String): Boolean =
        key.startsWith("algorithmAppliedMode_") ||
            key.startsWith("algorithmEverAdaptive_") ||
            key.startsWith("algorithmSourceBootstrapped_")

    // Settings change requests, not the provenance of retained glucose.
    private fun createImportEditor(
        context: Context,
        name: String
    ): android.content.SharedPreferences.Editor {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (name == AIDEX_PREFS_NAME) {
            // Leave ownership keys untouched, including changes committed
            // by a writer during import; don't restore an earlier snapshot.
            prefs.all.keys.filterNot(::isAiDexHistoryKey).forEach(editor::remove)
        } else {
            editor.clear()
        }
        return editor
    }

    private class SharedPreferencesRollback(
        private val context: Context,
        private val snapshots: Map<String, Map<String, Any?>>,
        private val newlyAddedAiDexAppliedKeys: MutableSet<String> = LinkedHashSet()
    ) {
        fun recordAddedAiDexAppliedKey(key: String) {
            newlyAddedAiDexAppliedKeys += key
        }

        fun rollback() {
            snapshots.forEach { (name, entries) ->
                val editor = createImportEditor(context, name)
                if (name == AIDEX_PREFS_NAME) {
                    newlyAddedAiDexAppliedKeys.forEach(editor::remove)
                }
                entries.forEach { (key, value) ->
                    putSnapshotValue(editor, key, value)
                }
                require(editor.commit()) { "Could not roll back preferences: $name" }
            }
        }
    }

    private fun snapshotSharedPreferences(
        context: Context,
        names: Collection<String>
    ): SharedPreferencesRollback {
        val snapshots = LinkedHashMap<String, Map<String, Any?>>()
        names.filterNot(::isSecretPreferenceFile).toSortedSet().forEach { name ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val copied = LinkedHashMap<String, Any?>()
            all.forEach { (key, value) ->
                if (name == AIDEX_PREFS_NAME && isAiDexHistoryKey(key)) return@forEach
                copied[key] = when (value) {
                    is Set<*> -> value.filterIsInstance<String>().toSet()
                    else -> value
                }
            }
            snapshots[name] = copied
        }
        return SharedPreferencesRollback(context, snapshots)
    }

    private fun putSnapshotValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any?
    ) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
        }
    }

    private fun applySharedPreferences(
        context: Context,
        parsedPreferences: Map<String, Map<String, ValidatedPreferenceValue>>
    ): Pair<Pair<Int, Int>, SharedPreferencesRollback> {
        val importedNames = parsedPreferences.keys.filterNot(::isSecretPreferenceFile)
        val existingNames = discoverSharedPreferenceNames(context)
        val rollback = snapshotSharedPreferences(context, existingNames + importedNames)
        try {
            existingNames
                .filterNot { it in importedNames }
                .sorted()
                .forEach { name ->
                    require(createImportEditor(context, name).commit()) {
                        "Could not clear preferences: $name"
                    }
                }

            var importedValueCount = 0
            importedNames.sorted().forEach { name ->
                val values = parsedPreferences.getValue(name)
                val editor = createImportEditor(context, name)

                values.forEach valueLoop@{ (key, value) ->
                    if (name == AIDEX_PREFS_NAME && isAiDexHistoryKey(key)) return@valueLoop
                    putValidatedPreferenceValue(editor, key, value)
                    if (name == AIDEX_PREFS_NAME && key.startsWith("algorithmMode_")) {
                        // An imported request cannot claim that local history has
                        // already been rewritten, even when the backup lacks a mode.
                        val appliedKey = key.replaceFirst("algorithmMode_", "algorithmAppliedMode_")
                        if (!context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(appliedKey)) {
                            editor.putInt(appliedKey, 0)
                            rollback.recordAddedAiDexAppliedKey(appliedKey)
                        }
                    }
                    importedValueCount++
                }

                require(editor.commit()) { "Could not import preferences: $name" }
            }

            return (importedNames.size to importedValueCount) to rollback
        } catch (failure: Throwable) {
            try {
                rollback.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun putValidatedPreferenceValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: ValidatedPreferenceValue
    ) {
        when (value) {
            is ValidatedPreferenceValue.BooleanValue -> editor.putBoolean(key, value.value)
            is ValidatedPreferenceValue.FloatValue -> editor.putFloat(key, value.value)
            is ValidatedPreferenceValue.IntValue -> editor.putInt(key, value.value)
            is ValidatedPreferenceValue.LongValue -> editor.putLong(key, value.value)
            is ValidatedPreferenceValue.StringValue -> editor.putString(key, value.value)
            is ValidatedPreferenceValue.StringSetValue -> editor.putStringSet(key, value.value)
            ValidatedPreferenceValue.NullValue -> editor.remove(key)
        }
    }

    private fun parsePreferenceEntry(key: String, entry: JSONObject): ValidatedPreferenceValue {
        val type = entry.opt("type") as? String
            ?: throw IllegalArgumentException("Missing preference type for $key")
        return when (type) {
            "boolean" -> {
                val raw = entry.requireRawField("value", key)
                require(raw is Boolean) { "Invalid boolean preference value for $key" }
                ValidatedPreferenceValue.BooleanValue(raw)
            }
            "float" -> {
                val raw = entry.requireRawField("value", key)
                require(raw is Number) { "Invalid float preference value for $key" }
                val floatVal = raw.toDouble().toFloat()
                require(floatVal.isFinite()) { "Non-finite float preference value for $key" }
                ValidatedPreferenceValue.FloatValue(floatVal)
            }
            "int" -> {
                val raw = entry.strictInt("value")
                    ?: throw IllegalArgumentException("Invalid int preference value for $key")
                ValidatedPreferenceValue.IntValue(raw)
            }
            "long" -> {
                val raw = entry.strictLong("value")
                    ?: throw IllegalArgumentException("Invalid long preference value for $key")
                ValidatedPreferenceValue.LongValue(raw)
            }
            "string" -> {
                require(entry.has("value")) { "Missing string preference value for $key" }
                if (entry.isNull("value")) {
                    ValidatedPreferenceValue.StringValue(null)
                } else {
                    val raw = entry.opt("value")
                    require(raw is String) { "Invalid string preference value for $key" }
                    ValidatedPreferenceValue.StringValue(raw)
                }
            }
            "string_set" -> {
                val array = entry.opt("value") as? JSONArray
                    ?: throw IllegalArgumentException("Invalid string_set preference value for $key")
                val items = LinkedHashSet<String>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.opt(index)
                    require(item is String) {
                        "Invalid string_set element for $key at index $index"
                    }
                    items.add(item)
                }
                ValidatedPreferenceValue.StringSetValue(items)
            }
            "null" -> {
                require(!entry.has("value") || entry.isNull("value")) {
                    "Invalid null preference value for $key"
                }
                ValidatedPreferenceValue.NullValue
            }
            else -> throw IllegalArgumentException("Unsupported preference type for $key")
        }
    }

    private fun isValidPreferenceFileName(name: String): Boolean =
        name.isNotBlank() &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name &&
            name != "." &&
            name != ".."

    private fun decodeNativeSettingsBase64(name: String, encoded: String): ByteArray {
        val compact = buildString(encoded.length) {
            for (ch in encoded) {
                if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') {
                    append(ch)
                }
            }
        }
        require(compact.isNotEmpty()) { "Native settings data is missing: $name" }
        return try {
            java.util.Base64.getDecoder().decode(compact)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 for native settings file: $name", error)
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

    private fun defaultWriteNativeFile(file: File, bytes: ByteArray) {
        file.outputStream().use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private fun defaultReplaceNativeFile(staged: File, target: File, bytes: ByteArray) {
        if (!staged.renameTo(target)) {
            defaultWriteNativeFile(target, bytes)
            staged.delete()
        }
    }

    private class NativeFilesTransaction(
        private val filesDir: File,
        private val files: List<ValidatedNativeFile>,
        private val writeFile: (File, ByteArray) -> Unit,
        private val replaceFile: (File, File, ByteArray) -> Unit
    ) {
        private val originals = LinkedHashMap<String, ByteArray?>()
        private val stagedFiles = LinkedHashMap<String, File>()
        private val committedNames = mutableListOf<String>()

        fun commit(): Int {
            if (files.isEmpty()) return 0
            if (!filesDir.exists() && !filesDir.mkdirs()) {
                throw IOException("Could not create native settings directory")
            }
            if (!filesDir.isDirectory) {
                throw IOException("Native settings path is not a directory")
            }

            try {
                files.forEach { file ->
                    val target = File(filesDir, file.name)
                    if (target.exists() && !target.isFile) {
                        throw IOException("Native settings target is not a regular file: ${file.name}")
                    }
                    originals[file.name] = if (target.isFile) target.readBytes() else null
                    val staged = File(filesDir, ".${file.name}.restore.tmp")
                    if (staged.exists() && !staged.delete()) {
                        throw IOException("Could not clear staged native settings file: ${file.name}")
                    }
                    stagedFiles[file.name] = staged
                    writeFile(staged, file.bytes)
                }

                files.forEach { file ->
                    val staged = stagedFiles.getValue(file.name)
                    val target = File(filesDir, file.name)
                    committedNames += file.name
                    replaceFile(staged, target, file.bytes)
                }
                return files.size
            } catch (failure: Throwable) {
                try {
                    rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            } finally {
                cleanupStagedFiles()
            }
        }

        fun rollback() {
            cleanupStagedFiles()
            var firstFailure: Throwable? = null
            committedNames.asReversed().forEach { name ->
                runCatching {
                    val target = File(filesDir, name)
                    val original = originals[name]
                    if (original == null) {
                        if (target.exists() && !target.delete()) {
                            throw IOException("Could not remove partially restored native settings file: $name")
                        }
                    } else {
                        val restoreTmp = File(filesDir, ".$name.rollback.tmp")
                        try {
                            defaultWriteNativeFile(restoreTmp, original)
                            defaultReplaceNativeFile(restoreTmp, target, original)
                        } finally {
                            if (restoreTmp.exists()) restoreTmp.delete()
                        }
                    }
                }.onFailure { error ->
                    val existing = firstFailure
                    if (existing == null) firstFailure = error
                    else existing.addSuppressed(error)
                }
            }
            committedNames.clear()
            firstFailure?.let { throw it }
        }

        private fun cleanupStagedFiles() {
            stagedFiles.values.forEach { staged ->
                if (staged.exists()) staged.delete()
            }
        }
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

    private suspend fun importJournalData(
        context: Context,
        journalData: ValidatedJournalData?
    ): JournalImportSummary {
        if (journalData == null) return JournalImportSummary()
        val journalDao = HistoryDatabase.getInstance(context).journalDao()

        journalDao.deleteAllEntries()
        journalDao.deleteAllInsulinPresets()
        journalDao.deleteAllCloneJournalTombstones()
        if (journalData.hasFoods) {
            FoodLibrary(journalDao, FoodLibraryDatabase.getInstance(context).foodDetailsDao()).restore(
                journalData.foods, journalData.foodDetails, replaceAll = true
            )
        }
        if (journalData.insulinPresets.isNotEmpty()) {
            journalDao.insertInsulinPresets(journalData.insulinPresets)
        }
        if (journalData.entries.isNotEmpty()) {
            journalDao.upsertEntries(journalData.entries)
        }
        if (journalData.cloneTombstones.isNotEmpty()) {
            journalDao.upsertCloneJournalTombstones(journalData.cloneTombstones)
        }
        return JournalImportSummary(
            entries = journalData.entries.size,
            insulinPresets = journalData.insulinPresets.size,
            foods = if (journalData.hasFoods) journalData.foods.size else 0
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
                val item = opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid journal entry at index $index")
                val timestamp = item.requireStrictLong("timestamp")
                add(
                    JournalEntryEntity(
                        id = item.strictRowId(),
                        timestamp = timestamp,
                        sensorSerial = item.strictNullableString("sensorSerial"),
                        entryType = item.requireStrictString("entryType"),
                        title = item.requireStrictString("title"),
                        note = item.strictNullableString("note"),
                        amount = item.strictNullableFloat("amount"),
                        glucoseValueMgDl = item.strictNullableFloat("glucoseValueMgDl"),
                        durationMinutes = item.strictNullableInt("durationMinutes"),
                        intensity = item.strictNullableString("intensity"),
                        insulinPresetId = item.strictNullableLong("insulinPresetId"),
                        foodId = item.strictNullableLong("foodId"),
                        proteinGrams = item.strictNullableFloat("proteinGrams"),
                        fatGrams = item.strictNullableFloat("fatGrams"),
                        source = item.strictStringOrDefault("source", "import"),
                        originSource = item.strictNullableString("originSource"),
                        sourceRecordId = item.strictNullableString("sourceRecordId"),
                        recoveryId = item.strictRecoveryId() ?: CloneJournalIdentity.newRecoveryId(),
                        createdAt = item.strictLongOrDefault("createdAt", timestamp),
                        updatedAt = item.strictLongOrDefault("updatedAt", timestamp),
                        insulinCurveJsonSnapshot = item.strictNullableString("insulinCurveJsonSnapshot"),
                        insulinCurveProfileId = item.strictNullableString("insulinCurveProfileId"),
                        insulinCurveModelVersion = item.strictNullableInt("insulinCurveModelVersion"),
                        insulinCurveEvidence = item.strictNullableString("insulinCurveEvidence"),
                        insulinBodyWeightKg = item.strictNullableFloat("insulinBodyWeightKg"),
                        insulinCurveWasApproximated = item.strictBooleanOrDefault(
                            "insulinCurveWasApproximated",
                            false
                        )
                    )
                )
            }
        }
    }

    private fun JSONArray.toCloneTombstones(): List<CloneJournalTombstoneEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid Clone journal tombstone at index $index")
                val entryId = item.requireStrictLong("entryId")
                val deletedAt = item.requireStrictLong("deletedAt")
                require(entryId > 0L && deletedAt > 0L) {
                    "Invalid Clone journal tombstone at index $index"
                }
                add(
                    CloneJournalTombstoneEntity(
                        entryId = entryId,
                        deletedAt = deletedAt,
                        recoveryId = item.strictRecoveryId(),
                    )
                )
            }
        }
    }

    private fun JSONArray.toFoods(): List<JournalFoodEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid food preset at index $index")
                add(
                    JournalFoodEntity(
                        id = item.strictRowId(),
                        displayName = item.requireStrictString("displayName"),
                        carbsGrams = item.strictFloatOrDefault("carbsGrams", 0f),
                        proteinGrams = item.strictNullableFloat("proteinGrams"),
                        fatGrams = item.strictNullableFloat("fatGrams"),
                        absorptionMinutes = item.strictIntOrDefault("absorptionMinutes", 90),
                        accentColor = item.strictIntOrDefault("accentColor", 0xFF5F7D4B.toInt()),
                        isBuiltIn = item.strictBooleanOrDefault("isBuiltIn", false),
                        isArchived = item.strictBooleanOrDefault("isArchived", false),
                        sortOrder = item.strictIntOrDefault("sortOrder", index),
                        createdAt = item.strictLongOrDefault("createdAt", System.currentTimeMillis()),
                        updatedAt = item.strictLongOrDefault("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    private fun JSONArray.toInsulinPresets(): List<JournalInsulinPresetEntity> {
        return buildList {
            for (index in 0 until length()) {
                val item = opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid insulin preset at index $index")
                val isBuiltIn = item.strictBooleanOrDefault("isBuiltIn", false)
                val sortOrder = item.strictIntOrDefault("sortOrder", index)
                add(
                    JournalInsulinPresetEntity(
                        id = item.strictRowId(),
                        displayName = item.requireStrictString("displayName"),
                        onsetMinutes = item.requireStrictInt("onsetMinutes"),
                        durationMinutes = item.requireStrictInt("durationMinutes"),
                        accentColor = item.requireStrictInt("accentColor"),
                        curveJson = item.strictStringOrDefault("curveJson", ""),
                        isBuiltIn = isBuiltIn,
                        isArchived = item.strictBooleanOrDefault("isArchived", false),
                        countsTowardIob = item.strictBooleanOrDefault("countsTowardIob", true),
                        sortOrder = sortOrder,
                        useForCalculation = item.strictBooleanOrDefault(
                            "useForCalculation",
                            !(isBuiltIn && sortOrder in setOf(1, 10))
                        ),
                        curveProfileId = item.strictNullableString("curveProfileId"),
                        curveModelVersion = item.strictIntOrDefault("curveModelVersion", 0),
                        curveEvidence = item.strictStringOrDefault("curveEvidence", "unverified")
                    )
                )
            }
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.requireRawField(name: String, label: String): Any {
        require(has(name) && !isNull(name)) { "Missing value for $label" }
        return opt(name) ?: throw IllegalArgumentException("Missing value for $label")
    }

    private fun JSONObject.strictLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        val raw = opt(name) ?: return null
        return when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            else -> null
        }
    }

    private fun JSONObject.strictInt(name: String): Int? {
        val value = strictLong(name) ?: return null
        return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun JSONObject.strictFloat(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        val raw = opt(name) as? Number ?: return null
        val floatValue = raw.toDouble().toFloat()
        return floatValue.takeIf { it.isFinite() }
    }

    private fun JSONObject.requireStrictLong(name: String): Long =
        strictLong(name) ?: throw IllegalArgumentException("Invalid backup integer: $name")

    private fun JSONObject.requireStrictInt(name: String): Int =
        strictInt(name) ?: throw IllegalArgumentException("Invalid backup integer: $name")

    private fun JSONObject.requireStrictString(name: String): String {
        if (!has(name) || isNull(name)) {
            throw IllegalArgumentException("Missing backup string: $name")
        }
        return opt(name) as? String
            ?: throw IllegalArgumentException("Invalid backup string: $name")
    }

    private fun JSONObject.strictLongOrDefault(name: String, default: Long): Long =
        if (!has(name)) default else requireStrictLong(name)

    private fun JSONObject.strictIntOrDefault(name: String, default: Int): Int =
        if (!has(name)) default else requireStrictInt(name)

    private fun JSONObject.strictFloatOrDefault(name: String, default: Float): Float =
        if (!has(name)) default else strictFloat(name)
            ?: throw IllegalArgumentException("Invalid backup number: $name")

    private fun JSONObject.strictBooleanOrDefault(name: String, default: Boolean): Boolean {
        if (!has(name)) return default
        return opt(name) as? Boolean
            ?: throw IllegalArgumentException("Invalid backup boolean: $name")
    }

    private fun JSONObject.strictStringOrDefault(name: String, default: String): String =
        if (!has(name)) default else requireStrictString(name)

    private fun JSONObject.strictNullableString(name: String): String? =
        if (isNull(name)) null else requireStrictString(name)

    private fun JSONObject.strictNullableFloat(name: String): Float? =
        if (isNull(name)) null else strictFloat(name)
            ?: throw IllegalArgumentException("Invalid backup number: $name")

    private fun JSONObject.strictNullableInt(name: String): Int? =
        if (isNull(name)) null else requireStrictInt(name)

    private fun JSONObject.strictNullableLong(name: String): Long? =
        if (isNull(name)) null else requireStrictLong(name)

    private fun JSONObject.strictRowId(): Long = strictLongOrDefault("id", 0L).also {
        require(it >= 0L) { "Invalid backup row ID" }
    }

    private fun JSONObject.strictRecoveryId(): String? =
        strictNullableString("recoveryId")?.let {
            CloneJournalIdentity.normalizeRecoveryId(it)
                ?: throw IllegalArgumentException("Invalid journal recovery ID")
        }

    private fun encodeFile(file: File): String {
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun readPayload(context: Context, uri: Uri): JSONObject {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open import source")
        val text = ExportPackageCodec.readUtf8(inputStream)
        return parseRootObject(text)
    }

    internal fun parseRootObject(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val root = tokener.nextValue()
        require(root is JSONObject) { "Settings backup is not a JSON object" }
        require(tokener.nextClean() == '\u0000') { "Unexpected trailing data in settings backup" }
        return root
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
