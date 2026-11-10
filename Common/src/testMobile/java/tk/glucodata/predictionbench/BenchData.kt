package tk.glucodata.predictionbench

import java.io.File
import kotlin.math.roundToInt
import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.journal.builtInJournalCurve
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.ui.util.GlucoseFormatter

/**
 * One sensor's readings in one lane, ascending by time, valid values only.
 *
 * [display] holds the numbers exactly as the app holds them in the user's display unit,
 * because that is what the production kernel reads: its slope clamp and target pull are
 * stated in display units, so feeding it mg/dL for an mmol user would measure a different
 * function from the one on the phone.
 */
class Lane(
    val sensor: String,
    val laneName: String,
    val times: LongArray,
    val display: FloatArray,
    val isMmol: Boolean,
    /**
     * Which sensor each reading came from, for a lane that spans one person's successive
     * sensors; null = one sensor throughout.
     */
    val segments: IntArray? = null,
) {
    fun segmentAt(index: Int): Int = segments?.get(index) ?: 0

    val id: String get() = "$sensor/$laneName"
    val size: Int get() = times.size

    fun mgdl(index: Int): Float = toMgdl(display[index], isMmol)

    /** Prefix sums of mg/dL, so a trailing mean is O(1) for any causal window. */
    internal val prefixMgdl: DoubleArray by lazy {
        val sums = DoubleArray(size + 1)
        for (i in 0 until size) sums[i + 1] = sums[i] + mgdl(i)
        sums
    }

    /** The median reading interval, in minutes. */
    val cadenceMinutes: Double by lazy {
        val gaps = (1 until size).map { (times[it] - times[it - 1]) / 60_000.0 }.filter { it > 0.0 }.sorted()
        if (gaps.isEmpty()) 5.0 else gaps[gaps.size / 2]
    }

    /**
     * The same sensor seen at a slower cadence: the first reading, then each first reading at
     * least [minutes] after the last one kept. This changes the sampling, not the sensor's
     * noise — a 5-minute Dexcom or Libre 2 stream is not this.
     */
    fun decimated(minutes: Int): Lane {
        val keep = ArrayList<Int>()
        var next = Long.MIN_VALUE
        for (i in 0 until size) {
            if (times[i] >= next) {
                keep += i
                next = times[i] + minutes * 60_000L - 30_000L
            }
        }
        return Lane(sensor, "$laneName@${minutes}min", LongArray(keep.size) { times[keep[it]] },
            FloatArray(keep.size) { display[keep[it]] }, isMmol, segments?.let { s -> IntArray(keep.size) { s[keep[it]] } })
    }

    /** Values rounded to [step] display units, as the CSV export stores them (0.1 mmol/L). */
    fun rounded(step: Float): Lane = Lane(sensor, "$laneName~$step", times,
        FloatArray(size) { (display[it] / step).roundToInt() * step }, isMmol, segments)

    companion object {
        /** One person's lanes end to end, each keeping its own sensor segment. */
        fun person(name: String, lanes: List<Lane>): Lane {
            val ordered = lanes.sortedBy { it.times.firstOrNull() ?: 0L }
            val times = ArrayList<Long>()
            val values = ArrayList<Float>()
            val segments = ArrayList<Int>()
            for ((seg, lane) in ordered.withIndex()) for (i in 0 until lane.size) {
                if (times.isNotEmpty() && lane.times[i] <= times.last()) continue
                times += lane.times[i]; values += lane.display[i]; segments += seg
            }
            return Lane(name, "person", times.toLongArray(), values.toFloatArray(), ordered.first().isMmol, segments.toIntArray())
        }
    }

    fun slice(fromMs: Long, toMs: Long, suffix: String): Lane {
        val idx = (0 until size).filter { times[it] in fromMs..toMs }
        return Lane(sensor, "$laneName$suffix", LongArray(idx.size) { times[idx[it]] },
            FloatArray(idx.size) { display[idx[it]] }, isMmol)
    }
}

class BenchDataset(
    val name: String,
    val unit: String,
    val lanes: List<Lane>,
    val journal: List<JournalEntry> = emptyList(),
    val presets: Map<Long, JournalInsulinPreset> = emptyMap(),
    /** Per-person journals, keyed by [Lane.sensor], for datasets holding several people. */
    val journalBySensor: Map<String, List<JournalEntry>> = emptyMap(),
    /**
     * Per-person prediction profiles, keyed by [Lane.sensor]. Absent means the app's defaults,
     * which is what a user who never opened the tuning screen runs with.
     */
    val profileBySensor: Map<String, PredictionModelProfile> = emptyMap(),
) {
    fun journalFor(lane: Lane): List<JournalEntry> = journalBySensor[lane.sensor] ?: journal
    fun profileFor(lane: Lane): PredictionModelProfile? = profileBySensor[lane.sensor]
}

internal const val MGDL_PER_MMOL = GlucoseFormatter.MGDL_PER_MMOL

internal fun toMgdl(display: Float, isMmol: Boolean): Float = if (isMmol) display * MGDL_PER_MMOL else display

object BenchLoaders {

    /**
     * The app's CSV export (HistoryExporter): glucose rows plus journal entries and insulin
     * presets. Lanes: `auto` = Value, `raw` = RawValue, `calibrated` = CalibratedValue where
     * present. The export rounds to the display precision (0.1 mmol/L), and holds the history
     * as it was at export time — drivers that rewrite history (replays, Adaptive V2) are not
     * reproduced as they were seen live.
     */
    fun jugglucoExport(file: File, lanes: Set<String> = setOf("auto", "calibrated"), minReadings: Int = 240): BenchDataset {
        val lines = file.bufferedReader().readLines()
        val header = splitCsv(lines.first())
        val col = header.withIndex().associate { it.value to it.index }
        fun List<String>.f(name: String): String = col[name]?.let { getOrNull(it) }.orEmpty()

        var unit = "mg/dL"
        val glucose = HashMap<String, MutableList<Triple<Long, String, List<String>>>>()
        val journal = ArrayList<JournalEntry>()
        val presets = HashMap<Long, JournalInsulinPreset>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val row = splitCsv(line)
            when (row.f("RecordType")) {
                "glucose" -> {
                    unit = row.f("Unit").ifBlank { unit }
                    val ts = row.f("Timestamp").toLongOrNull() ?: continue
                    glucose.getOrPut(row.f("SensorSerial")) { ArrayList() }.add(Triple(ts, row.f("SensorSerial"), row))
                }
                "journal_entry" -> parseJournal(row, col)?.let(journal::add)
                "journal_insulin_preset" -> parsePreset(row, col)?.let { presets[it.id] = it }
            }
        }
        val isMmol = GlucoseFormatter.isMmol(unit)
        val laneColumns = linkedMapOf("auto" to "Value", "raw" to "RawValue", "calibrated" to "CalibratedValue")
        val built = ArrayList<Lane>()
        for ((serial, rows) in glucose) {
            rows.sortBy { it.first }
            for ((laneName, column) in laneColumns) {
                if (laneName !in lanes) continue
                val times = ArrayList<Long>()
                val values = ArrayList<Float>()
                for ((ts, _, row) in rows) {
                    val v = row.f(column).toFloatOrNull() ?: continue
                    if (!v.isFinite() || v <= 0.1f) continue
                    if (times.isNotEmpty() && times.last() == ts) continue
                    times += ts
                    values += v
                }
                if (times.size >= minReadings) {
                    built += Lane(serial, laneName, times.toLongArray(), values.toFloatArray(), isMmol)
                }
            }
        }
        return BenchDataset(file.nameWithoutExtension, unit, built, journal.sortedBy { it.timestamp }, presets)
    }

    /**
     * AZT1D (Mendeley Data, CC BY 4.0): one folder per person with type 1 diabetes on a Tandem
     * t:slim X2 with Control-IQ, Dexcom G6 every 5 minutes. Rows with a bolus or carbs carry a
     * copied CGM value at an off-grid time, so they become journal entries only. Boluses are
     * modelled on the app's generic rapid-acting curve. The dataset's insulin type, sensitivity
     * and carb ratio are not known: each person gets a profile from the standard clinical rules
     * (sensitivity 1800/TDD, carb ratio 500/TDD), TDD being the median daily bolus plus the
     * median basal rate × 24 — a plausible hand-entered profile, not a fitted one. The automated
     * basal adjustments are not modelled, because the app does not model basal.
     */
    /** The app's built-in generic rapid-acting curve as preset id 1. */
    fun genericRapidPreset(): JournalInsulinPreset {
        val curve = builtInJournalCurve(JournalBuiltInCurveProfile.RAPID_GENERIC)
        return JournalInsulinPreset(
            id = 1L, displayName = "Rapid acting (generic)", onsetMinutes = 0, durationMinutes = curve.last().minute,
            accentColor = 0, curveJson = curve.joinToString(";") { "${it.minute}:${it.activity}" },
            isBuiltIn = true, isArchived = false, countsTowardIob = true, sortOrder = 0,
        )
    }

    fun azt1d(root: File, minReadings: Int = 240): BenchDataset {
        val preset = genericRapidPreset()
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val zone = java.time.ZoneId.systemDefault()
        val lanes = ArrayList<Lane>()
        val journals = HashMap<String, List<JournalEntry>>()
        val profiles = HashMap<String, PredictionModelProfile>()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "csv" }.sortedBy { it.name }.toList()
        var nextId = 1L
        for (file in files) {
            val lines = file.readLines().filter { it.isNotBlank() }
            val header = splitCsv(lines.first().trim())
            fun idx(vararg names: String) = names.map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
            val cTime = idx("EventDateTime"); val cCgm = idx("CGM", "Readings (CGM / BGM)")
            val cBolus = idx("TotalBolusInsulinDelivered"); val cCarbs = idx("CarbSize"); val cBasal = idx("Basal")
            val basalRates = ArrayList<Float>()
            val treatmentColumns = listOf("BolusType", "CorrectionDelivered", "TotalBolusInsulinDelivered", "FoodDelivered", "CarbSize").map { header.indexOf(it) }
            val sensor = file.nameWithoutExtension
            val readings = java.util.TreeMap<Long, Float>()
            val entries = ArrayList<JournalEntry>()
            for (line in lines.drop(1)) {
                val row = splitCsv(line.trim())
                val ts = runCatching { java.time.LocalDateTime.parse(row[cTime], format).atZone(zone).toInstant().toEpochMilli() }.getOrNull() ?: continue
                // Subjects differ: most leave non-treatment fields blank, one writes zeros.
                val isTreatment = treatmentColumns.any { c ->
                    val field = row.getOrNull(c).orEmpty().trim()
                    c >= 0 && field.isNotEmpty() && (field.toFloatOrNull()?.let { it != 0f } ?: true)
                }
                // An hourly rate in U/h, with the odd nonsense value (1910) among them.
                row.getOrNull(cBasal)?.toFloatOrNull()?.takeIf { it > 0f && it < 10f }?.let(basalRates::add)
                if (!isTreatment) {
                    row.getOrNull(cCgm)?.toFloatOrNull()?.takeIf { it > 0.1f }?.let { readings.putIfAbsent(ts, it) }
                    continue
                }
                fun entry(type: JournalEntryType, amount: Float, presetId: Long?) = JournalEntry(
                    id = nextId++, timestamp = ts, sensorSerial = null, type = type, title = "", note = null,
                    amount = amount, glucoseValueMgDl = null, durationMinutes = null, intensity = null,
                    insulinPresetId = presetId, foodId = null, proteinGrams = null, fatGrams = null,
                    source = JournalEntrySource.PEN, sourceRecordId = null, createdAt = ts, updatedAt = ts,
                )
                row.getOrNull(cBolus)?.toFloatOrNull()?.takeIf { it > 0f }?.let { entries += entry(JournalEntryType.INSULIN, it, 1L) }
                row.getOrNull(cCarbs)?.toFloatOrNull()?.takeIf { it > 0f }?.let { entries += entry(JournalEntryType.CARBS, it, null) }
            }
            if (readings.size < minReadings) continue
            lanes += Lane(sensor, "cgm", readings.keys.toLongArray(), readings.values.toFloatArray(), isMmol = false)
            // Some subjects repeat a bolus row verbatim.
            val unique = entries.distinctBy { Triple(it.timestamp, it.type, it.amount) }.sortedBy { it.timestamp }
            journals[sensor] = unique
            val bolusPerDay = unique.filter { it.type == JournalEntryType.INSULIN }
                .groupBy { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                .values.map { day -> day.sumOf { (it.amount ?: 0f).toDouble() } }.sorted()
            val basalPerDay = basalRates.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] * 24.0 }
            if (bolusPerDay.isNotEmpty()) {
                val bolus = bolusPerDay[bolusPerDay.size / 2]
                val tdd = bolus + (if (basalPerDay.isFinite()) basalPerDay else bolus)
                profiles[sensor] = PredictionModelProfile.single(
                    carbRatioGramsPerUnit = (500.0 / tdd).toFloat(),
                    insulinSensitivityMgDlPerUnit = (1800.0 / tdd).toFloat(),
                )
            }
        }
        return BenchDataset("azt1d", "mg/dL", lanes, presets = mapOf(1L to preset), journalBySensor = journals,
            profileBySensor = profiles)
    }

    /**
     * HUPA-UCM (Mendeley Data, CC BY 4.0): 25 people with type 1 diabetes on FreeStyle Libre 2.
     *
     * Reads only the CSV exports under `Raw_Data/<patient>/free_style_sensor`. The dataset's
     * `Preprocessed`
     * files are resampled to 5 minutes, and every interpolated point between two 15-minute
     * readings leans on the later one — future information for a forecast made in between —
     * so they are not used. From the raw exports this keeps the 15-minute *historic* records
     * (scans are irregular and are dropped) and, for people with no pump files, the rapid
     * insulin and carbohydrate records of the reader as their journal. Pump users' boluses
     * live in pump exports this does not parse, so they are CGM-only here.
     *
     * The exports come in three layouts (semicolon, tab and comma separated; three date formats;
     * carbohydrates in grams or 10 g portions), recognised from the header row.
     */
    fun hupa(root: File, minReadings: Int = 240): BenchDataset {
        val rawRoot = if (File(root, "Raw_Data").isDirectory) File(root, "Raw_Data") else root
        val preset = genericRapidPreset()
        val zone = java.time.ZoneId.systemDefault()
        val dateFormats = listOf("yyyy/MM/dd HH:mm", "d/M/yy H:mm", "dd-MM-yyyy HH:mm", "d/M/yyyy H:mm")
            .map { java.time.format.DateTimeFormatter.ofPattern(it) }
        val lanes = ArrayList<Lane>()
        val journals = HashMap<String, List<JournalEntry>>()
        val profiles = HashMap<String, PredictionModelProfile>()
        var nextId = 1L
        for (patientDir in rawRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            val usesPump = patientDir.listFiles().orEmpty().any { it.isDirectory && it.name.contains("pump") }
            val readings = java.util.TreeMap<Long, Float>()
            val entries = ArrayList<JournalEntry>()
            val rapidByDay = HashMap<java.time.LocalDate, Double>()
            val longByDay = HashMap<java.time.LocalDate, Double>()
            val files = File(patientDir, "free_style_sensor").listFiles().orEmpty().filter { it.extension == "csv" }
            for (file in files.sortedBy { it.name }) {
                val lines = file.readLines(Charsets.UTF_8)
                val headerIndex = lines.indexOfFirst { it.contains("Tipo de registro") }
                if (headerIndex < 0) continue
                val headerLine = lines[headerIndex]
                val delimiter = listOf(';', '\t', ',').maxByOrNull { d -> headerLine.count { it == d } }!!
                fun split(line: String) = if (delimiter == ',') splitCsv(line) else line.split(delimiter)
                val header = split(headerLine).map { it.trim() }
                fun col(predicate: (String) -> Boolean) = header.indexOfFirst(predicate)
                val cTime = col { it == "Hora" || it.startsWith("Sello de tiempo") }
                val cType = col { it == "Tipo de registro" }
                val cHistoric = col { it.startsWith("Hist") }
                val cRapid = col { it.contains("rápida (unidades)") }
                val cLong = col { it.contains("lenta (unidades)") }
                val cCarbGrams = col { it.contains("Carbohidratos (gramos)") }
                val cCarbPortions = col { it.contains("Carbohidratos (raciones)") || it.contains("Carbohidratos (porciones)") }
                if (cTime < 0 || cType < 0 || cHistoric < 0) continue
                var format: java.time.format.DateTimeFormatter? = null
                fun number(row: List<String>, c: Int): Double? =
                    if (c < 0) null else row.getOrNull(c)?.trim()?.replace(',', '.')?.toDoubleOrNull()
                for (line in lines.drop(headerIndex + 1)) {
                    val row = split(line)
                    val stamp = row.getOrNull(cTime)?.trim().orEmpty()
                    if (stamp.isEmpty()) continue
                    val local = (format?.let { f -> runCatching { java.time.LocalDateTime.parse(stamp, f) }.getOrNull() }
                        ?: dateFormats.firstNotNullOfOrNull { f ->
                            runCatching { java.time.LocalDateTime.parse(stamp, f) }.getOrNull()?.also { format = f }
                        }) ?: continue
                    val ts = local.atZone(zone).toInstant().toEpochMilli()
                    when (row.getOrNull(cType)?.trim()) {
                        "0" -> number(row, cHistoric)?.takeIf { it > 0 }?.let { readings.putIfAbsent(ts, it.toFloat()) }
                        else -> if (!usesPump) {
                            fun entry(type: JournalEntryType, amount: Float, presetId: Long?) = JournalEntry(
                                id = nextId++, timestamp = ts, sensorSerial = null, type = type, title = "", note = null,
                                amount = amount, glucoseValueMgDl = null, durationMinutes = null, intensity = null,
                                insulinPresetId = presetId, foodId = null, proteinGrams = null, fatGrams = null,
                                source = JournalEntrySource.METER, sourceRecordId = null, createdAt = ts, updatedAt = ts,
                            )
                            number(row, cRapid)?.takeIf { it > 0 }?.let {
                                entries += entry(JournalEntryType.INSULIN, it.toFloat(), 1L)
                                rapidByDay.merge(local.toLocalDate(), it, Double::plus)
                            }
                            number(row, cLong)?.takeIf { it > 0 }?.let { longByDay.merge(local.toLocalDate(), it, Double::plus) }
                            val grams = number(row, cCarbGrams)?.takeIf { it > 0 } ?: number(row, cCarbPortions)?.takeIf { it > 0 }?.times(10.0)
                            grams?.let { entries += entry(JournalEntryType.CARBS, it.toFloat(), null) }
                        }
                    }
                }
            }
            if (readings.size < minReadings) continue
            val sensor = patientDir.name
            lanes += Lane(sensor, if (usesPump) "libre15-pump" else "libre15-mdi", readings.keys.toLongArray(), readings.values.toFloatArray(), isMmol = false)
            val unique = entries.distinctBy { Triple(it.timestamp, it.type, it.amount) }.sortedBy { it.timestamp }
            journals[sensor] = unique
            val rapid = rapidByDay.values.sorted()
            if (rapid.isNotEmpty()) {
                val bolus = rapid[rapid.size / 2]
                val basal = longByDay.values.sorted().let { if (it.isEmpty()) bolus else it[it.size / 2] }
                val tdd = bolus + basal
                profiles[sensor] = PredictionModelProfile.single(
                    carbRatioGramsPerUnit = (500.0 / tdd).toFloat(),
                    insulinSensitivityMgDlPerUnit = (1800.0 / tdd).toFloat(),
                )
            }
        }
        return BenchDataset("hupa", "mg/dL", lanes, presets = mapOf(1L to preset), journalBySensor = journals,
            profileBySensor = profiles)
    }

    /** A column CSV such as the Sibionics test fixtures: a millisecond timestamp column and value columns. */
    fun columnCsv(
        file: File,
        timeColumn: String,
        laneColumns: List<String>,
        isMmol: Boolean,
        sensor: String = file.nameWithoutExtension,
    ): BenchDataset {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = splitCsv(lines.first())
        val t = header.indexOf(timeColumn)
        val lanes = laneColumns.map { name ->
            val c = header.indexOf(name)
            require(t >= 0 && c >= 0) { "missing column $timeColumn or $name in ${file.name}" }
            val pairs = lines.drop(1).map(::splitCsv).mapNotNull { row ->
                val ts = row.getOrNull(t)?.toLongOrNull() ?: return@mapNotNull null
                val v = row.getOrNull(c)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0.1f } ?: return@mapNotNull null
                ts to v
            }.sortedBy { it.first }.distinctBy { it.first }
            Lane(sensor, name, LongArray(pairs.size) { pairs[it].first }, FloatArray(pairs.size) { pairs[it].second }, isMmol)
        }
        return BenchDataset(file.nameWithoutExtension, if (isMmol) "mmol/L" else "mg/dL", lanes)
    }

    private fun parseJournal(row: List<String>, col: Map<String, Int>): JournalEntry? {
        fun f(name: String) = col[name]?.let { row.getOrNull(it) }.orEmpty()
        val timestamp = f("Timestamp").toLongOrNull() ?: return null
        val created = f("JournalCreatedAt").toLongOrNull() ?: timestamp
        return JournalEntry(
            id = f("JournalId").toLongOrNull() ?: return null,
            timestamp = timestamp,
            sensorSerial = null,
            type = JournalEntryType.fromStorage(f("JournalType")),
            title = f("JournalTitle"),
            note = f("JournalNote").ifBlank { null },
            amount = f("JournalAmount").toFloatOrNull(),
            glucoseValueMgDl = f("JournalGlucoseMgDl").toFloatOrNull(),
            durationMinutes = f("JournalDurationMinutes").toIntOrNull(),
            intensity = JournalIntensity.fromStorage(f("JournalIntensity").ifBlank { null }),
            insulinPresetId = f("JournalInsulinPresetId").toLongOrNull(),
            foodId = null,
            proteinGrams = null,
            fatGrams = null,
            source = JournalEntrySource.fromStorage(f("JournalSource")),
            sourceRecordId = f("JournalSourceRecordId").ifBlank { null },
            createdAt = created,
            updatedAt = f("JournalUpdatedAt").toLongOrNull() ?: created,
        )
    }

    private fun parsePreset(row: List<String>, col: Map<String, Int>): JournalInsulinPreset? {
        fun f(name: String) = col[name]?.let { row.getOrNull(it) }.orEmpty()
        return JournalInsulinPreset(
            id = f("PresetId").toLongOrNull() ?: return null,
            displayName = f("PresetName"),
            onsetMinutes = f("PresetOnsetMinutes").toIntOrNull() ?: 0,
            durationMinutes = f("PresetDurationMinutes").toIntOrNull() ?: 0,
            accentColor = f("PresetAccentColor").toIntOrNull() ?: 0,
            curveJson = f("PresetCurveJson"),
            isBuiltIn = f("PresetBuiltIn").toBoolean(),
            isArchived = f("PresetArchived").toBoolean(),
            countsTowardIob = f("PresetCountsTowardIob").toBoolean(),
            sortOrder = f("PresetSortOrder").toIntOrNull() ?: 0,
            useForCalculation = f("PresetUseForCalculation").ifBlank { "true" }.toBoolean(),
        )
    }

    /** RFC 4180 field split: quoted fields may hold commas and doubled quotes. */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += field.toString(); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        out += field.toString()
        return out
    }
}
