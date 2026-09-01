package tk.glucodata.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryExporter {
    private const val TAG = "HistoryExporter"
    private const val RECORD_TYPE_GLUCOSE = "glucose"
    private const val RECORD_TYPE_JOURNAL_ENTRY = "journal_entry"
    private const val RECORD_TYPE_INSULIN_PRESET = "journal_insulin_preset"

    // Use a unified date format for CSV to ensure re-import consistency
    // ISO 8601 is best: yyyy-MM-dd HH:mm:ss
    private val CSV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
    // Friendly format for "Readable" export
    private val READABLE_DATE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale.getDefault())

    private fun csvCell(value: Any?): String {
        val text = value?.toString() ?: ""
        return "\"${text.replace("\"", "\"\"")}\""
    }

    private fun formatExportFloat(value: Float?): String {
        return value?.let { String.format(Locale.US, "%.4f", it) }.orEmpty()
    }

    private fun resolveExportSensorSerial(
        point: GlucosePoint,
        serialByTimestamp: Map<Long, String>,
        fallback: String = "unknown"
    ): String {
        return point.sensorSerial
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: serialByTimestamp[point.timestamp]
            ?: fallback
    }

    private suspend fun loadExportJournalEntries(
        journalDao: tk.glucodata.data.journal.JournalDao,
        data: List<GlucosePoint>,
        startMillis: Long?,
        endMillis: Long?
    ): List<tk.glucodata.data.journal.JournalEntryEntity> {
        val rangeStart = startMillis ?: data.minOfOrNull { it.timestamp }
        val rangeEnd = endMillis ?: data.maxOfOrNull { it.timestamp }
        return if (rangeStart != null && rangeEnd != null) {
            journalDao.getEntriesBetween(rangeStart, rangeEnd)
        } else {
            journalDao.getEntries()
        }
    }

    /**
     * Export data to a CSV file.
     * Format: Timestamp(ms),Date,Value,RawValue,Unit,SensorSerial
     * Values are always exported in the User's preferred unit for consistency with what they see.
     * Multi-sensor: includes SensorSerial column for traceability. Re-imported glucose
     * rows are intentionally stored under a stable import namespace instead.
     */
    suspend fun exportToCsv(
        context: Context,
        uri: Uri,
        data: List<GlucosePoint>,
        unit: String,
        startMillis: Long? = null,
        endMillis: Long? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Get all readings from Room to access sensorSerial
                val database = HistoryDatabase.getInstance(context)
                val dao = database.historyDao()
                val journalDao = database.journalDao()
                // Build a map of timestamp -> sensorSerial for enriching export
                val allReadings = dao.getReadingsSince(0L)
                val serialByTimestamp = HashMap<Long, String>(allReadings.size)
                for (reading in allReadings) {
                    serialByTimestamp[reading.timestamp] = reading.sensorSerial
                }
                val journalEntries = loadExportJournalEntries(journalDao, data, startMillis, endMillis)
                val insulinPresets = journalDao.getInsulinPresets()

                // Non-destructive software calibration for the CalibratedValue column
                // (issue #130): mirrors the on-screen/Nightscout projection. Empty when
                // no calibration applies, so the raw Value/RawValue archive is untouched.
                val viewModeOf = ExportCalibration.viewModeResolver()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        // Header — CalibratedValue sits in the glucose block, after RawValue.
                        writer.write(
                            "Timestamp,Date,Value,RawValue,CalibratedValue,Unit,SensorSerial,RecordType," +
                                "JournalId,JournalType,JournalTitle,JournalNote,JournalAmount,JournalGlucoseMgDl," +
                                "JournalDurationMinutes,JournalIntensity,JournalInsulinPresetId,JournalSource,JournalOriginSource," +
                                "JournalSourceRecordId,JournalCreatedAt,JournalUpdatedAt," +
                                "PresetId,PresetName,PresetOnsetMinutes,PresetDurationMinutes,PresetAccentColor," +
                                "PresetCurveJson,PresetBuiltIn,PresetArchived,PresetCountsTowardIob," +
                                "PresetUseForCalculation,PresetSortOrder\n"
                        )

                        // Data
                        for (point in data) {
                            val dateStr = CSV_DATE_FORMAT.format(Date(point.timestamp))
                            // Ensure dot decimal separator for CSV
                            val valueStr = tk.glucodata.ui.util.GlucoseFormatter.formatCsv(point.value, unit)
                            val rawStr = tk.glucodata.ui.util.GlucoseFormatter.formatCsv(point.rawValue, unit)
                            val serial = resolveExportSensorSerial(point, serialByTimestamp)
                            val calibrated = ExportCalibration.calibratedDisplayValue(
                                autoDisplayValue = point.value,
                                rawDisplayValue = point.rawValue,
                                timestamp = point.timestamp,
                                sensorId = point.sensorSerial,
                                viewMode = viewModeOf(point.sensorSerial),
                                sealedDisplayValue = point.sealedDisplayValue
                            )
                            val calibratedStr = calibrated
                                ?.let { tk.glucodata.ui.util.GlucoseFormatter.formatCsv(it, unit) }
                                .orEmpty()

                            writer.write("${point.timestamp},$dateStr,$valueStr,$rawStr,$calibratedStr,$unit,$serial,$RECORD_TYPE_GLUCOSE\n")
                        }
                        for (entry in journalEntries) {
                            val dateStr = CSV_DATE_FORMAT.format(Date(entry.timestamp))
                            writer.write(
                                listOf(
                                    entry.timestamp,
                                    dateStr,
                                    "",
                                    "",
                                    "",
                                    "",
                                    entry.sensorSerial.orEmpty(),
                                    RECORD_TYPE_JOURNAL_ENTRY,
                                    entry.id,
                                    entry.entryType,
                                    entry.title,
                                    entry.note.orEmpty(),
                                    formatExportFloat(entry.amount),
                                    formatExportFloat(entry.glucoseValueMgDl),
                                    entry.durationMinutes ?: "",
                                    entry.intensity.orEmpty(),
                                    entry.insulinPresetId ?: "",
                                    entry.source,
                                    entry.originSource.orEmpty(),
                                    entry.sourceRecordId.orEmpty(),
                                    entry.createdAt,
                                    entry.updatedAt
                                ).joinToString(",") { csvCell(it) } + "\n"
                            )
                        }
                        for (preset in insulinPresets) {
                            writer.write(
                                listOf(
                                    0,
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    RECORD_TYPE_INSULIN_PRESET,
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    preset.id,
                                    preset.displayName,
                                    preset.onsetMinutes,
                                    preset.durationMinutes,
                                    preset.accentColor,
                                    preset.curveJson,
                                    preset.isBuiltIn,
                                    preset.isArchived,
                                    preset.countsTowardIob,
                                    preset.useForCalculation,
                                    preset.sortOrder
                                ).joinToString(",") { csvCell(it) } + "\n"
                            )
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting to CSV", e)
                false
            }
        }
    }

    /**
     * Export data to a human-readable text file.
     * Format: Mon, 01 Jan 2024 12:00: 5.5 mmol/L (Raw: 5.4) [SensorSerial]
     */
    suspend fun exportToReadable(
        context: Context,
        uri: Uri,
        data: List<GlucosePoint>,
        unit: String,
        startMillis: Long? = null,
        endMillis: Long? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        writeReadableReport(context, writer, data, unit, startMillis, endMillis)
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting to text", e)
                false
            }
        }
    }

    suspend fun exportReadableToFile(
        context: Context,
        file: java.io.File,
        data: List<GlucosePoint>,
        unit: String,
        startMillis: Long? = null,
        endMillis: Long? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                file.outputStream().bufferedWriter().use { writer ->
                    writeReadableReport(context, writer, data, unit, startMillis, endMillis)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting readable report to file", e)
                false
            }
        }
    }

    private suspend fun writeReadableReport(
        context: Context,
        writer: java.io.Writer,
        data: List<GlucosePoint>,
        unit: String,
        startMillis: Long?,
        endMillis: Long?
    ) {
        val database = HistoryDatabase.getInstance(context)
        val dao = database.historyDao()
        val journalDao = database.journalDao()
        val allReadings = dao.getReadingsSince(0L)
        val serialByTimestamp = HashMap<Long, String>(allReadings.size)
        for (reading in allReadings) {
            serialByTimestamp[reading.timestamp] = reading.sensorSerial
        }
        val journalEntries = loadExportJournalEntries(journalDao, data, startMillis, endMillis)
        val insulinPresets = journalDao.getInsulinPresets()

        writer.write("JugglucoNG Glucose History Export\n")
        writer.write("Generated on: ${READABLE_DATE_FORMAT.format(Date())}\n")
        writer.write("Total Readings: ${data.size}\n\n")

        val isMmol = GlucoseFormatter.isMmol(unit)
        // Non-destructive software calibration for the readable report (issue #130).
        val viewModeOf = ExportCalibration.viewModeResolver()
        for (point in data) {
            val dateStr = READABLE_DATE_FORMAT.format(Date(point.timestamp))
            val valueStr = GlucoseFormatter.format(point.value, isMmol)
            val rawStr = GlucoseFormatter.format(point.rawValue, isMmol)
            val serial = resolveExportSensorSerial(point, serialByTimestamp, fallback = "")
            val calibrated = ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = point.value,
                rawDisplayValue = point.rawValue,
                timestamp = point.timestamp,
                sensorId = point.sensorSerial,
                viewMode = viewModeOf(point.sensorSerial),
                sealedDisplayValue = point.sealedDisplayValue
            )
            val calibratedTag = calibrated?.let { " (Calibrated: ${GlucoseFormatter.format(it, isMmol)})" }.orEmpty()

            val sensorTag = if (serial.isNotEmpty() && serial != "unknown") " [$serial]" else ""
            val line = "$dateStr: $valueStr $unit (Raw: $rawStr)$calibratedTag$sensorTag\n"
            writer.write(line)
        }
        if (journalEntries.isNotEmpty()) {
            writer.write("\nJournal Entries: ${journalEntries.size}\n")
            for (entry in journalEntries) {
                val dateStr = READABLE_DATE_FORMAT.format(Date(entry.timestamp))
                val amount = entry.amount?.let { " · $it" }.orEmpty()
                val glucose = entry.glucoseValueMgDl?.let { " · ${it.toInt()} mg/dL" }.orEmpty()
                val duration = entry.durationMinutes?.let { " · ${it}min" }.orEmpty()
                val intensity = entry.intensity?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                val source = entry.source.takeIf { it.isNotBlank() && it != "manual" }?.let { " · $it" }.orEmpty()
                val note = entry.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                writer.write("$dateStr: ${entry.entryType} · ${entry.title}$amount$glucose$duration$intensity$source$note\n")
            }
        }
        if (insulinPresets.isNotEmpty()) {
            writer.write("\nInsulin Presets: ${insulinPresets.size}\n")
            for (preset in insulinPresets) {
                val archived = if (preset.isArchived) "archived" else "enabled"
                val iob = if (preset.countsTowardIob) "IOB" else "no IOB"
                val calculation = if (preset.useForCalculation) "calculation" else "no calculation"
                writer.write(
                    "${preset.displayName}: ${preset.onsetMinutes}-${preset.durationMinutes} min · " +
                        "$archived · $iob · $calculation · color ${preset.accentColor}\n"
                )
            }
        }
    }

    /**
     * Import glucose data from a delimited history file.
     *
     * Accepts JugglucoNG's own CSV export in every generation (the legacy 5 and
     * 6 column files, and the current one carrying CalibratedValue) as well as
     * Juggluco's tab separated export, so migrating from Juggluco needs no hand
     * conversion. [HistoryImportParser] does the format detection.
     *
     * Imported readings are stored under a stable import namespace, not a real
     * sensor serial, so native resync/sensor replacement cannot wipe them.
     * Re-importing the same file is idempotent: the unique (timestamp,
     * sensorSerial) index replaces the earlier rows instead of duplicating them.
     *
     * Internal storage is ALWAYS mg/dL.
     */
    suspend fun importFromCsv(context: Context, uri: Uri): ImportResult {
        return withContext(Dispatchers.IO) {
            val importSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL

            try {
                val parsed = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        HistoryImportParser.parse(reader)
                    }
                } ?: return@withContext ImportResult(0, 0, false, "Could not open the selected file.")

                parsed.errorMessage?.let { message ->
                    return@withContext ImportResult(0, 0, false, message)
                }

                val readings = parsed.readings.map { reading ->
                    HistoryReading(
                        timestamp = reading.timestamp,
                        sensorSerial = importSerial,
                        value = reading.valueMgDl,
                        rawValue = reading.rawValueMgDl,
                        rate = 0f,
                        source = GlucoseReadingSource.IMPORT,
                    )
                }
                if (readings.isNotEmpty()) {
                    HistoryRepository(context).storeReadings(readings)
                }

                ImportResult(readings.size, parsed.failedRows, true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing history file", e)
                ImportResult(0, 0, false, e.message)
            }
        }
    }

    data class ImportResult(
        val successCount: Int,
        val failCount: Int,
        val success: Boolean,
        val errorMessage: String?
    )
}
