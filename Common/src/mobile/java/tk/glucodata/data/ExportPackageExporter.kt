package tk.glucodata.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.HistorySourceProvenance
import tk.glucodata.data.calibration.CalibrationDatabase
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.CloneJournalIdentity
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object ExportPackageExporter {
    private const val SCHEMA = "tk.glucodata.export-package"
    private const val SCHEMA_VERSION = 1
    private const val SQLITE_BIND_CHUNK = 900

    data class ExportRequest(
        val includeSettings: Boolean,
        val includeHistory: Boolean,
        val includeCalibrations: Boolean,
        val historyDays: Long?
    ) {
        val hasSelection: Boolean
            get() = includeSettings || includeHistory || includeCalibrations

        val isSettingsOnly: Boolean
            get() = includeSettings && !includeHistory && !includeCalibrations
    }

    data class ExportSummary(
        val settingsIncluded: Boolean,
        val historyReadings: Int,
        val journalEntries: Int,
        val journalFoods: Int,
        val insulinPresets: Int,
        val calibrations: Int
    )

    data class CachedExport(
        val file: File,
        val fileName: String,
        val mimeType: String
    )

    data class ImportSummary(
        val settingsImported: Boolean,
        val historyReadings: Int,
        val journalEntries: Int,
        val journalFoods: Int,
        val insulinPresets: Int,
        val calibrations: Int,
        val restartRequired: Boolean,
        // Serial to display on the dashboard for the imported glucose (may be a
        // synthetic "imported"/"__imported_csv__" id when the file had no serial).
        val historyDisplaySerial: String? = null
    )

    // Result of importing only the glucose/history section.
    data class HistoryOnlyImport(
        val readings: Int,
        val displaySerial: String?
    )

    private data class HistoryImportSummary(
        val readings: Int = 0,
        val journalEntries: Int = 0,
        val journalFoods: Int = 0,
        val insulinPresets: Int = 0,
        val displaySerial: String? = null
    )

    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        request: ExportRequest
    ): Result<ExportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                require(request.hasSelection) { "No export content selected" }
                val (payload, summary) = buildPayload(appContext, request)
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: error("Could not open export destination")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                summary
            }
        }
    }

    suspend fun isExportPackage(context: Context, uri: Uri): Boolean {
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
                require(payload.optString("schema") == SCHEMA) { "Unsupported export package" }
                val schemaVersion = payload.optInt("schemaVersion", 0)
                require(schemaVersion in 1..SCHEMA_VERSION) {
                    "Unsupported export package version: $schemaVersion"
                }

                val settingsResult = payload.optJSONObject("settings")?.let { settings ->
                    SettingsExporter.importPayload(appContext, settings).getOrThrow()
                }
                val historySummary = payload.optJSONObject("history")?.let { history ->
                    importHistorySection(appContext, history)
                } ?: HistoryImportSummary()
                val calibrationCount = payload.optJSONObject("calibrations")?.let { calibrations ->
                    importCalibrationSection(appContext, calibrations)
                } ?: 0

                ImportSummary(
                    settingsImported = settingsResult != null,
                    historyReadings = historySummary.readings,
                    journalEntries = historySummary.journalEntries,
                    journalFoods = historySummary.journalFoods,
                    insulinPresets = historySummary.insulinPresets,
                    calibrations = calibrationCount,
                    restartRequired = settingsResult != null,
                    historyDisplaySerial = historySummary.displaySerial
                )
            }
        }
    }

    /**
     * Import only the glucose/history section from an export package, ignoring
     * settings and calibrations. Returns the number of imported glucose readings,
     * or null when the package contains no history section at all.
     */
    suspend fun importHistoryFromPackage(context: Context, uri: Uri): Result<HistoryOnlyImport?> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = readPayload(appContext, uri)
                require(payload.optString("schema") == SCHEMA) { "Unsupported export package" }
                val schemaVersion = payload.optInt("schemaVersion", 0)
                require(schemaVersion in 1..SCHEMA_VERSION) {
                    "Unsupported export package version: $schemaVersion"
                }
                val history = payload.optJSONObject("history") ?: return@runCatching null
                val summary = importHistorySection(appContext, history)
                HistoryOnlyImport(readings = summary.readings, displaySerial = summary.displaySerial)
            }
        }
    }

    suspend fun writeToCache(
        context: Context,
        request: ExportRequest
    ): Result<CachedExport> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                require(request.hasSelection) { "No export content selected" }
                val (payload, _) = buildPayload(appContext, request)
                val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                exportDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("Juggluco_") }
                    ?.forEach { it.delete() }

                val fileName = suggestedFileName(request)
                val file = File(exportDir, fileName)
                OutputStreamWriter(file.outputStream(), StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                CachedExport(
                    file = file,
                    fileName = fileName,
                    mimeType = mimeTypeFor(request)
                )
            }
        }
    }

    fun mimeTypeFor(request: ExportRequest): String = "application/json"

    fun suggestedFileName(request: ExportRequest): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        val label = when {
            request.includeSettings && request.includeHistory && request.includeCalibrations -> "Everything"
            request.includeSettings && !request.includeHistory && !request.includeCalibrations -> "Settings"
            request.includeHistory && !request.includeSettings && !request.includeCalibrations -> "Data"
            request.includeCalibrations && !request.includeSettings && !request.includeHistory -> "Calibrations"
            else -> "Package"
        }
        return "Juggluco_${label}_$date.json"
    }

    fun suggestedReadableReportFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        return "Juggluco_Report_$date.txt"
    }

    private suspend fun buildPayload(
        context: Context,
        request: ExportRequest
    ): Pair<JSONObject, ExportSummary> {
        if (request.isSettingsOnly) {
            return SettingsExporter.buildExportPayload(context) to ExportSummary(
                settingsIncluded = true,
                historyReadings = 0,
                journalEntries = 0,
                journalFoods = 0,
                insulinPresets = 0,
                calibrations = 0
            )
        }

        val sections = JSONArray()
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("containsSensitiveData", request.includeSettings)
            .put("containsHealthData", request.includeHistory || request.includeCalibrations)
            .put("app", buildAppInfo(context))

        if (request.includeSettings) {
            sections.put("settings")
            root.put("settings", SettingsExporter.buildExportPayload(context, includeJournalData = false))
        }

        val historySummary = if (request.includeHistory) {
            sections.put("history")
            val (history, summary) = buildHistorySection(context, request.historyDays)
            root.put("history", history)
            summary
        } else {
            HistorySummary()
        }

        val calibrationCount = if (request.includeCalibrations) {
            sections.put("calibrations")
            val (calibrations, count) = buildCalibrationSection(context)
            root.put("calibrations", calibrations)
            count
        } else {
            0
        }

        root.put("sections", sections)

        return root to ExportSummary(
            settingsIncluded = request.includeSettings,
            historyReadings = historySummary.readings,
            journalEntries = historySummary.journalEntries,
            journalFoods = historySummary.journalFoods,
            insulinPresets = historySummary.insulinPresets,
            calibrations = calibrationCount
        )
    }

    private fun buildAppInfo(context: Context): JSONObject {
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("locale", Locale.getDefault().toLanguageTag())
    }

    private data class HistorySummary(
        val readings: Int = 0,
        val journalEntries: Int = 0,
        val journalFoods: Int = 0,
        val insulinPresets: Int = 0
    )

    private suspend fun buildHistorySection(
        context: Context,
        historyDays: Long?
    ): Pair<JSONObject, HistorySummary> {
        val database = HistoryDatabase.getInstance(context)
        val endMillis = System.currentTimeMillis()
        val startMillis = historyDays
            ?.let { endMillis - TimeUnit.DAYS.toMillis(it.coerceAtLeast(1L)) }
            ?: 0L
        val readings = database.historyDao().getReadingsSince(startMillis)
        val journalEntries = if (startMillis > 0L) {
            database.journalDao().getEntriesBetween(startMillis, endMillis)
        } else {
            database.journalDao().getEntries()
        }
        val insulinPresets = database.journalDao().getInsulinPresets()
        val foods = database.journalDao().getFoods()

        // Non-destructive software calibration (issue #130): the stored values stay
        // raw mg/dL; each reading additionally carries the calibrated mg/dL that live
        // outputs (Nightscout/xDrip/screen) would show, when a calibration applies.
        val isMmol = tk.glucodata.Applic.unit == 1
        val viewModeOf = ExportCalibration.viewModeResolver()
        // Readings whose displayed value was recorded export that value rather
        // than a fresh recomputation — see ReadingDisplay.
        val sealedByKey: Map<Long, Float> = if (
            runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)
        ) {
            val nowMs = System.currentTimeMillis()
            runCatching {
                database.readingDisplayDao().getAllSince(0L)
                    .filter { it.isUsable && it.isSealedAt(nowMs) }
                    .associate { sealedDisplayKey(it.sensorSerial, it.timestamp) to it.displayMgdl }
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val sealedOf: (HistoryReading) -> Float? = { reading ->
            sealedByKey[sealedDisplayKey(reading.sensorSerial, reading.timestamp)]
        }

        return JSONObject()
            .put("rangeStartEpochMillis", if (startMillis > 0L) startMillis else JSONObject.NULL)
            .put("rangeEndEpochMillis", endMillis)
            .put("storedUnit", "mg/dL")
            .put(
                "readings",
                JSONArray().also { array ->
                    readings.forEach { array.put(it.toJson(isMmol, viewModeOf, sealedOf)) }
                }
            )
            .put(
                "journalEntries",
                JSONArray().also { array ->
                    journalEntries.forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "journalInsulinPresets",
                JSONArray().also { array ->
                    insulinPresets.forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "journalFoods",
                JSONArray().also { array ->
                    foods.forEach { array.put(it.toJson()) }
                }
            ) to HistorySummary(
            readings = readings.size,
            journalEntries = journalEntries.size,
            journalFoods = foods.size,
            insulinPresets = insulinPresets.size
        )
    }

    private suspend fun buildCalibrationSection(context: Context): Pair<JSONObject, Int> {
        CalibrationManager.init(context)
        CalibrationManager.getCachedCalibrations()
        val rows = CalibrationDatabase.getInstance(context)
            .calibrationDao()
            .getAllSync()
            .sortedByDescending { it.timestamp }
        val currentSensorId = CalibrationManager.getResolvedCurrentSensorId()

        return JSONObject()
            .put("version", 2)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put(
                "rawEnabled",
                CalibrationManager.isEnabledForMode(isRawMode = true, sensorIdOverride = currentSensorId)
            )
            .put(
                "autoEnabled",
                CalibrationManager.isEnabledForMode(isRawMode = false, sensorIdOverride = currentSensorId)
            )
            .put(
                "sensorEnablement",
                JSONArray().also { array ->
                    CalibrationManager.getSensorEnablementSnapshot().forEach { state ->
                        array.put(
                            JSONObject()
                                .put("sensorId", state.sensorId)
                                .put("rawEnabled", state.rawEnabled)
                                .put("autoEnabled", state.autoEnabled)
                        )
                    }
                }
            )
            .put("hideInitialWhenCalibrated", CalibrationManager.hideInitialWhenCalibrated.value)
            .put("applyToPast", CalibrationManager.applyToPast.value)
            .put("lockPastHistory", CalibrationManager.lockPastHistory.value)
            .put("keepDisabledHistory", CalibrationManager.keepDisabledHistory.value)
            .put("overwriteSensorValues", CalibrationManager.overwriteSensorValues.value)
            .put("visualContinuity", CalibrationManager.visualContinuity.value)
            .put("calibrateFromJournal", CalibrationManager.calibrateFromJournal.value)
            .put("weightMode", CalibrationManager.getWeightMode().storageValue)
            .put("rawAlgorithm", CalibrationManager.getAlgorithmForMode(isRawMode = true).storageValue)
            .put("autoAlgorithm", CalibrationManager.getAlgorithmForMode(isRawMode = false).storageValue)
            .put(
                "calibrations",
                JSONArray().also { array ->
                    rows.forEach { array.put(it.toJson()) }
                }
            ) to rows.size
    }

    private suspend fun importHistorySection(
        context: Context,
        history: JSONObject
    ): HistoryImportSummary {
        val database = HistoryDatabase.getInstance(context)
        val readings = history.optJSONArray("readings").toHistoryReadings()
        val entries = history.optJSONArray("journalEntries").toJournalEntries()
        val insulinPresets = history.optJSONArray("journalInsulinPresets").toInsulinPresets()
        val foods = history.optJSONArray("journalFoods").toFoods()

        if (readings.isNotEmpty()) {
            database.withTransaction {
                val dao = database.historyDao()
                val existingByKey = HashMap<Pair<String, Long>, HistoryReading>()
                readings.groupBy(HistoryReading::sensorSerial).forEach { (serial, rows) ->
                    rows.map(HistoryReading::timestamp).distinct().chunked(SQLITE_BIND_CHUNK)
                        .forEach { timestamps ->
                            dao.getSensorReadingsAtTimestamps(serial, timestamps).forEach { existing ->
                                existingByKey[serial to existing.timestamp] = existing
                            }
                        }
                }
                dao.insertAll(preserveImportedHistoryProvenance(readings, existingByKey))
            }
        }
        if (foods.isNotEmpty()) {
            database.journalDao().insertFoods(foods)
        }
        if (insulinPresets.isNotEmpty()) {
            database.journalDao().insertInsulinPresets(insulinPresets)
        }
        if (entries.isNotEmpty()) {
            database.journalDao().upsertEntries(entries)
            // Written straight to the table rather than through the repository, so the wake
            // it raises has to be raised here: a restored journal is a backlog like any
            // other and would otherwise sit until something unrelated woke the uploader.
            tk.glucodata.NightscoutUploadWake.afterJournalChange()
        }

        // Serial to key the dashboard on: the newest reading's serial (already
        // normalized to "imported" by toHistoryReadings() when the file had none).
        val displaySerial = readings.maxByOrNull { it.timestamp }?.sensorSerial

        return HistoryImportSummary(
            readings = readings.size,
            journalEntries = entries.size,
            journalFoods = foods.size,
            insulinPresets = insulinPresets.size,
            displaySerial = displaySerial
        )
    }

    private suspend fun importCalibrationSection(
        context: Context,
        calibrations: JSONObject
    ): Int {
        CalibrationManager.init(context)
        val rows = calibrations.optJSONArray("calibrations").toCalibrationEntities()
        if (rows.isNotEmpty()) {
            CalibrationDatabase.getInstance(context).calibrationDao().insertAll(rows)
        }

        val sensorEnablement = calibrations.optJSONArray("sensorEnablement")
        if (sensorEnablement != null) {
            for (index in 0 until sensorEnablement.length()) {
                val state = sensorEnablement.optJSONObject(index) ?: continue
                val sensorId = state.optString("sensorId", "").takeIf { it.isNotBlank() } ?: continue
                CalibrationManager.setEnabledForMode(
                    isRawMode = true,
                    enabled = state.optBoolean("rawEnabled", true),
                    sensorIdOverride = sensorId
                )
                CalibrationManager.setEnabledForMode(
                    isRawMode = false,
                    enabled = state.optBoolean("autoEnabled", true),
                    sensorIdOverride = sensorId
                )
            }
        } else {
            val currentSensorId = CalibrationManager.getResolvedCurrentSensorId()
            if (currentSensorId.isNotBlank()) {
                CalibrationManager.setEnabledForMode(
                    isRawMode = true,
                    enabled = calibrations.optBoolean(
                        "rawEnabled",
                        CalibrationManager.isEnabledForMode(true, currentSensorId)
                    ),
                    sensorIdOverride = currentSensorId
                )
                CalibrationManager.setEnabledForMode(
                    isRawMode = false,
                    enabled = calibrations.optBoolean(
                        "autoEnabled",
                        CalibrationManager.isEnabledForMode(false, currentSensorId)
                    ),
                    sensorIdOverride = currentSensorId
                )
            }
        }
        CalibrationManager.setHideInitialWhenCalibrated(
            calibrations.optBoolean(
                "hideInitialWhenCalibrated",
                CalibrationManager.hideInitialWhenCalibrated.value
            )
        )
        CalibrationManager.setApplyToPast(
            calibrations.optBoolean("applyToPast", CalibrationManager.applyToPast.value)
        )
        CalibrationManager.setLockPastHistory(
            calibrations.optBoolean("lockPastHistory", CalibrationManager.lockPastHistory.value)
        )
        CalibrationManager.setKeepDisabledHistory(
            calibrations.optBoolean("keepDisabledHistory", CalibrationManager.keepDisabledHistory.value)
        )
        CalibrationManager.setOverwriteSensorValues(
            calibrations.optBoolean("overwriteSensorValues", CalibrationManager.overwriteSensorValues.value)
        )
        CalibrationManager.setVisualContinuity(
            calibrations.optBoolean("visualContinuity", CalibrationManager.visualContinuity.value)
        )
        CalibrationManager.setCalibrateFromJournal(
            calibrations.optBoolean("calibrateFromJournal", CalibrationManager.calibrateFromJournal.value)
        )
        CalibrationManager.setWeightMode(
            CalibrationManager.CalibrationWeightMode.fromStorage(
                calibrations.optString("weightMode", "")
            )
        )
        CalibrationManager.setAlgorithmForMode(
            isRawMode = true,
            algorithm = CalibrationManager.CalibrationAlgorithm.fromStorage(
                calibrations.optString("rawAlgorithm", "")
            )
        )
        CalibrationManager.setAlgorithmForMode(
            isRawMode = false,
            algorithm = CalibrationManager.CalibrationAlgorithm.fromStorage(
                calibrations.optString("autoAlgorithm", "")
            )
        )
        CalibrationManager.loadCalibrations()
        return rows.size
    }

    /** Mirrors HistoryRepository's display key: sensor plus minute bucket. */
    private fun sealedDisplayKey(sensorSerial: String, timestamp: Long): Long =
        (timestamp / 60_000L) * 31L + sensorSerial.hashCode()

    private fun HistoryReading.toJson(
        isMmol: Boolean,
        viewModeOf: (String?) -> Int,
        sealedOf: (HistoryReading) -> Float?
    ): JSONObject {
        val calibratedMgDl = ExportCalibration.calibratedMgDl(
            autoMgDl = value,
            rawMgDl = rawValue,
            timestamp = timestamp,
            sensorId = sensorSerial,
            viewMode = viewModeOf(sensorSerial),
            isMmol = isMmol,
            sealedMgDl = sealedOf(this)
        )
        return JSONObject()
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial)
            .put("valueMgDl", value.toDouble())
            .put("rawValueMgDl", rawValue.toDouble())
            .put("calibratedValueMgDl", calibratedMgDl?.toDouble() ?: JSONObject.NULL)
            .put("rate", rate?.toDouble() ?: JSONObject.NULL)
            .put("source", source)
            .put("firstStoredAt", firstStoredAt)
    }

    private fun JournalEntryEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial ?: JSONObject.NULL)
            .put("entryType", entryType)
            .put("title", title)
            .put("note", note ?: JSONObject.NULL)
            .put("amount", amount?.toDouble() ?: JSONObject.NULL)
            .put("glucoseValueMgDl", glucoseValueMgDl?.toDouble() ?: JSONObject.NULL)
            .put("durationMinutes", durationMinutes ?: JSONObject.NULL)
            .put("intensity", intensity ?: JSONObject.NULL)
            .put("insulinPresetId", insulinPresetId ?: JSONObject.NULL)
            .put("foodId", foodId ?: JSONObject.NULL)
            .put("proteinGrams", proteinGrams?.toDouble() ?: JSONObject.NULL)
            .put("fatGrams", fatGrams?.toDouble() ?: JSONObject.NULL)
            .put("source", source)
            .put("originSource", originSource ?: JSONObject.NULL)
            .put("sourceRecordId", sourceRecordId ?: JSONObject.NULL)
            .put("recoveryId", recoveryId ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("nsUploadedAt", nsUploadedAt ?: JSONObject.NULL)
            .put("nsRemoteId", nsRemoteId ?: JSONObject.NULL)
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
            .put("carbsGrams", carbsGrams.toDouble())
            .put("proteinGrams", proteinGrams?.toDouble() ?: JSONObject.NULL)
            .put("fatGrams", fatGrams?.toDouble() ?: JSONObject.NULL)
            .put("absorptionMinutes", absorptionMinutes)
            .put("accentColor", accentColor)
            .put("isBuiltIn", isBuiltIn)
            .put("isArchived", isArchived)
            .put("sortOrder", sortOrder)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun CalibrationEntity.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestamp)
            .put("sensorId", sensorId)
            .put("sensorValue", sensorValue.toDouble())
            .put("sensorValueRaw", sensorValueRaw.toDouble())
            .put("userValue", userValue.toDouble())
            .put("isEnabled", isEnabled)
            .put("isRawMode", isRawMode)
            .put("journalEntryId", journalEntryId ?: JSONObject.NULL)
    }

    private fun JSONArray?.toHistoryReadings(): List<HistoryReading> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val timestamp = item.optLong("timestamp", 0L)
                val sensorSerial = item.optString("sensorSerial", "").ifBlank { "imported" }
                val value = item.optDouble("valueMgDl", 0.0).toFloat()
                val rawValue = item.optDouble("rawValueMgDl", 0.0).toFloat()
                if (timestamp <= 0L || value <= 0f) continue
                add(
                    HistoryReading(
                        timestamp = timestamp,
                        sensorSerial = sensorSerial,
                        value = value,
                        rawValue = rawValue,
                        rate = item.optNullableFloat("rate"),
                        source = item.optString("source", GlucoseReadingSource.SENSOR)
                            .ifBlank { GlucoseReadingSource.SENSOR },
                        firstStoredAt = item.optLong("firstStoredAt", System.currentTimeMillis())
                            .takeIf { it > 0L } ?: System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    internal fun preserveImportedHistoryProvenance(
        incoming: List<HistoryReading>,
        existingByKey: Map<Pair<String, Long>, HistoryReading>,
    ): List<HistoryReading> = incoming.map { reading ->
        val existing = existingByKey[reading.sensorSerial to reading.timestamp]
        reading.copy(
            source = HistorySourceProvenance.stableSource(existing?.source, reading.source),
            firstStoredAt = HistorySourceProvenance.stableFirstStoredAt(
                existing?.firstStoredAt,
                reading.firstStoredAt,
            ),
        )
    }

    private fun JSONArray?.toJournalEntries(): List<JournalEntryEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val timestamp = item.optLong("timestamp", 0L)
                if (timestamp <= 0L) continue
                add(
                    JournalEntryEntity(
                        id = item.optLong("id", 0L),
                        timestamp = timestamp,
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
                        recoveryId = CloneJournalIdentity.normalizeRecoveryId(
                            item.optNullableString("recoveryId")
                        ) ?: CloneJournalIdentity.newRecoveryId(),
                        createdAt = item.optLong("createdAt", timestamp),
                        updatedAt = item.optLong("updatedAt", timestamp),
                        nsUploadedAt = item.optNullableLong("nsUploadedAt"),
                        nsRemoteId = item.optNullableString("nsRemoteId")
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

    private fun JSONArray?.toCalibrationEntities(): List<CalibrationEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val timestamp = item.optLong("timestamp", 0L)
                if (timestamp <= 0L) continue
                add(
                    CalibrationEntity(
                        timestamp = timestamp,
                        sensorId = item.optString("sensorId", ""),
                        sensorValue = item.optDouble("sensorValue", 0.0).toFloat(),
                        sensorValueRaw = item.optDouble("sensorValueRaw", 0.0).toFloat(),
                        userValue = item.optDouble("userValue", 0.0).toFloat(),
                        isEnabled = item.optBoolean("isEnabled", true),
                        isRawMode = item.optBoolean("isRawMode", false),
                        journalEntryId = item.optNullableLong("journalEntryId")
                    )
                )
            }
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name) || !has(name)) null else optString(name)
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

    private fun readPayload(context: Context, uri: Uri): JSONObject {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open import source")
        val text = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }
}
