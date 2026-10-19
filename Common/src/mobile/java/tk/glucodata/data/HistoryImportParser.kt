package tk.glucodata.data

import tk.glucodata.ui.util.GlucoseFormatter
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Reads the delimited glucose history files JugglucoNG accepts on import.
 *
 * Two shapes are recognised, both by header name:
 *
 *  - JugglucoNG's own CSV export, whose first column is `Timestamp`. This
 *    covers every generation of the export: the legacy 5/6 column files and
 *    the current one that inserted `CalibratedValue` (issue #130). The derived
 *    `CalibratedValue` column stays out of the import — `Value`/`RawValue`
 *    remain the source of truth.
 *  - Juggluco's tab separated export, whose first column is `Sensorid`. Its
 *    value column is named after the unit (`mg/dL`, `mmol/L`, and for the
 *    "both"/undetermined settings the label Juggluco writes there), the time
 *    lives in `UnixTime` as epoch seconds, and there is no raw column in the
 *    history export, so the value doubles as the raw value. The stream/scan
 *    exports share that header prefix and carry a `Raw` column, which is
 *    picked up when present.
 *
 * Parsing is kept free of Android and Room so it can be unit tested; the
 * caller turns [ParsedReading]s into [HistoryReading]s.
 *
 * Values are normalised to mg/dL, timestamps to epoch milliseconds.
 */
internal object HistoryImportParser {
    private const val RECORD_TYPE_GLUCOSE = "glucose"

    /** Byte order mark some spreadsheet tools prepend to exported text. */
    private const val BOM = "\uFEFF"

    /**
     * Epoch values below this are seconds, above are milliseconds. The cut is
     * 1973-03-03, so it separates every plausible reading date either way.
     */
    private const val SECONDS_UPPER_BOUND = 100_000_000_000L

    /**
     * Juggluco names its value column after the configured unit; these are the
     * labels its exporter can emit ("both" and "undetermined" write mg/dL).
     */
    private val JUGGLUCO_UNIT_COLUMNS = setOf("mmol/l", "mg/dl", "both", "undetermined")

    private val DATE_PATTERNS = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")

    data class ParsedReading(
        val timestamp: Long,
        val valueMgDl: Float,
        val rawValueMgDl: Float,
    )

    data class ParseResult(
        val readings: List<ParsedReading>,
        val failedRows: Int,
        val errorMessage: String?,
    )

    /**
     * Column positions resolved from the header, plus [fixedUnit] for sources
     * that state the unit in the column name instead of a per-row cell.
     */
    private data class Layout(
        val timestampIndex: Int,
        val valueIndex: Int,
        val rawIndex: Int,
        val unitIndex: Int,
        val fixedUnit: String?,
        val recordTypeIndex: Int,
        val minColumns: Int,
    )

    fun parse(reader: BufferedReader): ParseResult {
        val headerLine = reader.readLine()?.removePrefix(BOM)
            ?: return ParseResult(emptyList(), 0, "The file is empty.")
        val delimiter = if (headerLine.contains('\t')) '\t' else ','
        val headerColumns = parseDelimitedLine(headerLine, delimiter).map { it.trim() }
        val layout = resolveLayout(headerColumns)
            ?: return ParseResult(emptyList(), 0, unrecognisedHeaderMessage(headerColumns, delimiter))

        val readings = ArrayList<ParsedReading>()
        var failedRows = 0
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = parseDelimitedLine(line, delimiter)
            if (layout.recordTypeIndex >= 0 && parts.size > layout.recordTypeIndex) {
                val recordType = parts[layout.recordTypeIndex].trim()
                // Journal entries and insulin presets share the file; only
                // glucose rows are readings.
                if (recordType.isNotBlank() && !recordType.equals(RECORD_TYPE_GLUCOSE, ignoreCase = true)) {
                    return@forEachLine
                }
            }
            if (parts.size < layout.minColumns) {
                failedRows++
                return@forEachLine
            }
            val reading = readRow(parts, layout)
            if (reading == null) {
                failedRows++
            } else {
                readings.add(reading)
            }
        }
        return ParseResult(readings, failedRows, null)
    }

    private fun readRow(parts: List<String>, layout: Layout): ParsedReading? {
        val timestamp = parseTimestampMs(parts[layout.timestampIndex]) ?: return null
        val value = parts[layout.valueIndex].trim().toFloatOrNull() ?: return null
        val rawValue = parts[layout.rawIndex].trim().toFloatOrNull() ?: value
        val unit = layout.fixedUnit ?: parts[layout.unitIndex].trim()
        return if (GlucoseFormatter.isMmol(unit)) {
            ParsedReading(
                timestamp = timestamp,
                valueMgDl = GlucoseFormatter.mmolToMg(value),
                rawValueMgDl = GlucoseFormatter.mmolToMg(rawValue),
            )
        } else {
            ParsedReading(timestamp = timestamp, valueMgDl = value, rawValueMgDl = rawValue)
        }
    }

    private fun resolveLayout(headerColumns: List<String>): Layout? {
        val first = headerColumns.firstOrNull().orEmpty()
        return when {
            first.startsWith("Timestamp") -> nativeLayout(headerColumns)
            first.equals("Sensorid", ignoreCase = true) -> jugglucoLayout(headerColumns)
            else -> null
        }
    }

    /** JugglucoNG's own export: columns by name, legacy files by position. */
    private fun nativeLayout(headerColumns: List<String>): Layout {
        val valueIndex = headerColumns.indexOf("Value").let { if (it >= 0) it else 2 }
        val rawIndex = headerColumns.indexOf("RawValue").let { if (it >= 0) it else 3 }
        val unitIndex = headerColumns.indexOf("Unit").let { if (it >= 0) it else 4 }
        return Layout(
            timestampIndex = 0,
            valueIndex = valueIndex,
            rawIndex = rawIndex,
            unitIndex = unitIndex,
            fixedUnit = null,
            recordTypeIndex = headerColumns.indexOf("RecordType"),
            // Five columns is the floor the legacy positional fallback needs.
            minColumns = maxOf(5, valueIndex + 1, rawIndex + 1, unitIndex + 1),
        )
    }

    /** Juggluco's TSV export: `Sensorid nr UnixTime <date> TZ Min <unit>`. */
    private fun jugglucoLayout(headerColumns: List<String>): Layout? {
        // The date column next to UnixTime is a local-time string whose header
        // name drifted between Juggluco versions ("YYYY-mm-dd-HH:MM:SS" and
        // "YYYY-mm-ddTHH:MM:SS"), so it is matched loosely and only used when
        // UnixTime is missing — it carries no zone, unlike the epoch column.
        val timestampIndex = headerColumns.indexOfFirst { it.equals("UnixTime", ignoreCase = true) }
            .let {
                if (it >= 0) it
                else headerColumns.indexOfFirst { column -> column.startsWith("YYYY", ignoreCase = true) }
            }
        if (timestampIndex < 0) return null
        val valueIndex = headerColumns.indexOfFirst { it.lowercase(Locale.US) in JUGGLUCO_UNIT_COLUMNS }
        if (valueIndex < 0) return null
        // Only the calibrated stream/scan export carries a raw column; without
        // one the history value is the raw value.
        val rawIndex = headerColumns.indexOfFirst { it.equals("Raw", ignoreCase = true) }
            .let { if (it >= 0) it else valueIndex }
        return Layout(
            timestampIndex = timestampIndex,
            valueIndex = valueIndex,
            rawIndex = rawIndex,
            unitIndex = -1,
            fixedUnit = headerColumns[valueIndex],
            recordTypeIndex = -1,
            minColumns = maxOf(timestampIndex + 1, valueIndex + 1, rawIndex + 1),
        )
    }

    private fun unrecognisedHeaderMessage(headerColumns: List<String>, delimiter: Char): String {
        val delimiterName = if (delimiter == '\t') "tab" else "comma"
        val first = headerColumns.firstOrNull().orEmpty()
        return "Unrecognised header: first $delimiterName-separated column is \"$first\". " +
            "Expected a JugglucoNG export starting with \"Timestamp\", " +
            "or a Juggluco export starting with \"Sensorid\"."
    }

    /**
     * Accepts epoch seconds, epoch milliseconds, or the local-time date string
     * the exports carry alongside them. Returns null when none of those fit,
     * so the caller can count the row as failed and keep going.
     */
    private fun parseTimestampMs(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { epoch ->
            return if (abs(epoch) < SECONDS_UPPER_BOUND) epoch * 1000L else epoch
        }
        for (pattern in DATE_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            runCatching { format.parse(trimmed) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    /**
     * Quote-aware split on [delimiter]. Doubled quotes inside a quoted cell are
     * one literal quote, matching what the CSV export writes.
     */
    private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == delimiter && !inQuotes -> {
                    cells.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        cells.add(current.toString())
        return cells
    }
}
