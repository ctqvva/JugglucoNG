package tk.glucodata.data

import android.content.Context
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.aidex.*
import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveV2
import tk.glucodata.drivers.aidex.adaptive2.AiDexReference
import tk.glucodata.drivers.aidex.native.ble.AiDexBleManager
import tk.glucodata.data.calibration.CalibrationManager
import java.util.Locale

/** A merged history keeps its local applied mode; only new sensors adopt backup ownership. */
internal class AiDexHistoryImport private constructor(
    private val context: Context,
    private val knownIds: Set<String>,
    private val incomingSerials: Set<String>,
    private val selections: Map<String, AiDexAlgorithmSelection>,
    private val newSensors: Set<String>,
    private val backupValues: JSONObject,
    private val originals: Map<String, List<AiDexSourceSample>>,
) {
    private val rebuilt = HashMap<String, Map<Long, AiDexAlgorithmReading>>()
    /** References and fit fingerprints each calibrated rebuild was computed against. */
    private val calibrationInputs = HashMap<String, Triple<List<AiDexReference>, Long, Long>>()

    private fun currentCalibrationInputs(id: String) = Triple(
        AiDexAlgorithmReferences.decode(
            CalibrationManager.getActiveCalibrationAnchors(id, true),
            CalibrationManager.getActiveCalibrationAnchors(id, false), Applic.unit == 1,
        ),
        CalibrationManager.getIntegratedCalibrationFingerprint(id, true),
        CalibrationManager.getIntegratedCalibrationFingerprint(id, false),
    )
    val sensorIds: Set<String> get() = selections.keys

    private fun canonical(serial: String): String? = canonical(serial, knownIds)

    fun validate(reading: HistoryReading) {
        val id = canonical(reading.sensorSerial) ?: return
        require(reading.sensorSerial in incomingSerials) { "Backup history changed while importing" }
        val sources = originals[id] ?: return
        require(sources.binarySearchBy(reading.timestamp / 60_000L) { it.minute } >= 0) {
            "Cannot merge conflicting AiDex history for $id: original factory/raw minute ${reading.timestamp / 60_000L} is missing"
        }
    }

    /** Called after preflight, before any imported rows become observable. */
    fun establishOwnership(drivers: List<AiDexBleManager>, beforeCommit: () -> Unit = {}) {
        if (selections.isEmpty()) { beforeCommit(); return }
        val prefs = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
        selections.forEach { (id, expected) ->
            val driver = drivers.firstOrNull { it.SerialNumber.equals(id, true) }
            if (id in newSensors) {
                check(driver == null && !prefs.contains("algorithmAppliedMode_$id")) { "AiDex sensor changed during import for $id" }
                check(kotlinx.coroutines.runBlocking { HistoryDatabase.getInstance(context).historyDao()
                    .getCountForSensors(incomingSerials.filter { canonical(it) == id }.toList()) } == 0) {
                    "AiDex history changed during import for $id"
                }
            } else {
                val current = driver?.appliedHistorySelection() ?: AiDexAlgorithmSelection.fromStorage(prefs.getInt("algorithmAppliedMode_$id", 0))
                check(current == expected) { "AiDex history ownership changed during import for $id" }
            }
        }
        beforeCommit() // Originals precede ownership and every derived reading.
        val editor = prefs.edit()
        selections.forEach { (id, selection) ->
            editor.putInt("algorithmAppliedMode_$id", selection.storageId)
            if (id in newSensors) {
                editor.putBoolean("algorithmEverAdaptive_$id", selection.adaptive ||
                    backupValues.optJSONObject("algorithmEverAdaptive_$id")?.optBoolean("value") == true)
                editor.putBoolean("algorithmSourceBootstrapped_$id",
                    backupValues.optJSONObject("algorithmSourceBootstrapped_$id")?.optBoolean("value") == true)
            }
        }
        check(editor.commit()) { "Could not preserve AiDex history ownership" }
        ManagedSensorRuntime.clearCaches()
    }

    fun checkOwnership(drivers: List<AiDexBleManager>) {
        val prefs = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
        selections.forEach { (id, expected) ->
            val current = drivers.firstOrNull { it.SerialNumber.equals(id, true) }?.appliedHistorySelection()
                ?: AiDexAlgorithmSelection.fromStorage(prefs.getInt("algorithmAppliedMode_$id", 0))
            check(current == expected) { "AiDex history ownership changed during import for $id" }
        }
        originals.forEach { (id, expected) ->
            val end = expected.lastOrNull()?.minute ?: return@forEach
            val current = AiDexSampleJournal(AiDexSourceJournalBackup.file(context.filesDir, id)).snapshot()
                .filter { it.minute <= end }
            check(current == expected) { "AiDex original readings changed during import for $id" }
        }
        // A reference edit during preflight can let the driver commit newer
        // corrections first; rows fitted to the old references must not replace them.
        calibrationInputs.forEach { (id, expected) ->
            check(currentCalibrationInputs(id) == expected) { "AiDex calibration changed during import for $id" }
        }
    }

    /** Local references and local applied selection define how conflicting incoming rows are rebuilt. */
    fun rebuildConflicts() {
        val factor = if (Applic.unit == 1) 1f / AiDexAdaptiveV2.MGDL_PER_MMOL else 1f
        originals.forEach { (id, sources) ->
            val selection = selections.getValue(id)
            val references = if (selection.calibration) {
                currentCalibrationInputs(id).also { calibrationInputs[id] = it }.first
            } else emptyList()
            val model = AiDexAlgorithmContext(selection)
            val readings = sources.map { model.process(it, references) }
            val fallback = if (selection.calibration && readings.any { it.uncertainty == null }) {
                CalibrationManager.seedIntegratedCalibrationBaseline(
                    FloatArray(sources.size) { sources[it].factoryMgDl * factor },
                    LongArray(sources.size) { sources[it].timestampMs }, false, id,
                )
                CalibrationManager.getIntegratedCalibratedSeries(
                    FloatArray(sources.size) { sources[it].factoryMgDl * factor },
                    LongArray(sources.size) { sources[it].timestampMs }, false, id,
                )
            } else null
            val output = readings.mapIndexed { index, row ->
                val glucose = if (row.uncertainty == null && fallback != null)
                    fallback[index] / factor else row.glucoseMgDl
                require(glucose.isFinite() && glucose > 0f) { "AiDex import rebuild produced an invalid value" }
                row.copy(glucoseMgDl = glucose)
            }
            rebuilt[id] = output.mapIndexed { index, row ->
                row.source.minute to row.copy(rateMgDlPerMinute = AiDexLiveRate.between(output.getOrNull(index - 1), row))
            }.toMap()
        }
    }

    /**
     * The import's own settings step can change calibration inputs: replacing
     * calibration_prefs can rerun the legacy enablement migration for the
     * current sensor. Rebuild against what that step produced; edits made
     * after this point are still rejected by [checkOwnership].
     */
    fun settingsImported() {
        if (originals.isNotEmpty()) rebuildConflicts()
    }

    fun normalized(reading: HistoryReading): AiDexAlgorithmReading? {
        validate(reading)
        val id = canonical(reading.sensorSerial) ?: return null
        if (id !in originals) return null
        return rebuilt.getValue(id).getValue(reading.timestamp / 60_000L)
    }

    companion object {
        private val modePrefixes = listOf("algorithmAppliedMode_", "algorithmEverAdaptive_", "algorithmSourceBootstrapped_")
        private fun canonical(serial: String, known: Set<String>): String? {
            val upper = serial.trim().uppercase(Locale.ROOT)
            if (upper.startsWith("X-")) return upper
            return "X-$upper".takeIf { it in known }
        }

        suspend fun prepare(context: Context, payload: JSONObject, counts: Map<String, Int>, drivers: List<AiDexBleManager>): AiDexHistoryImport {
            val prefs = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
            val backup = payload.optJSONObject("settings")?.optJSONObject("sharedPreferences")
                ?.optJSONObject("AiDexNativePrefs")?.optJSONObject("values") ?: JSONObject()
            fun ids(keys: Set<String>) = keys.mapNotNull { key ->
                (modePrefixes + "algorithmMode_").firstOrNull { key.startsWith(it) }
                    ?.let { key.removePrefix(it).uppercase(Locale.ROOT) }
            }.toSet()
            val heldIds = prefs.all.keys.filter { key -> modePrefixes.any { key.startsWith(it) } }.let { ids(it.toSet()) }
            val stored = HistoryDatabase.getInstance(context).historyDao().getAllSensorSerials()
            val known = ids(prefs.all.keys) + ids(backup.keys().asSequence().toSet()) + stored.filter { it.startsWith("X-", true) }.map { it.uppercase(Locale.ROOT) } +
                drivers.map { it.SerialNumber.uppercase(Locale.ROOT) }
            val local = heldIds + stored.mapNotNull { canonical(it, known) } + drivers.map { it.SerialNumber.uppercase(Locale.ROOT) }
            val incoming = counts.keys.mapNotNull { canonical(it, known) }.toSet()
            val newSensors = incoming - local
            val selections = incoming.associateWith { id ->
                if (id in local) drivers.firstOrNull { it.SerialNumber.equals(id, true) }?.appliedHistorySelection()
                    ?: AiDexAlgorithmSelection.fromStorage(prefs.getInt("algorithmAppliedMode_$id", 0))
                else AiDexAlgorithmSelection.fromStorage(backup.optJSONObject("algorithmAppliedMode_$id")?.optInt("value")
                    ?: backup.optJSONObject("algorithmMode_$id")?.optInt("value") ?: 0)
            }
            val sourceMaps = HashMap<String, List<AiDexSourceSample>>()
            for (id in incoming.intersect(local)) {
                val backupMode = AiDexAlgorithmSelection.fromStorage(backup.optJSONObject("algorithmAppliedMode_$id")?.optInt("value")
                    ?: backup.optJSONObject("algorithmMode_$id")?.optInt("value") ?: 0)
                if (backupMode == selections[id]) continue
                val file = AiDexSourceJournalBackup.file(context.filesDir, id)
                val merged = sortedMapOf<Long, AiDexSourceSample>()
                AiDexSampleJournal(file).snapshot().forEach { merged[it.minute] = it }
                val path = file.relativeTo(context.filesDir).invariantSeparatorsPath
                val encoded = payload.optJSONObject("history")?.optJSONObject("sensorSourceJournals")
                    ?.optJSONObject(path)?.optString("base64")?.takeIf { it.isNotBlank() }
                // Fold the backup's own revisions exactly as journal restore will,
                // then let local originals win: the plan must equal the restored journal.
                if (encoded != null) AiDexSampleJournal.latestRevisions(
                    AiDexSampleJournal.decode(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)),
                ).values.forEach { merged.putIfAbsent(it.minute, it) }
                sourceMaps[id] = merged.values.toList()
            }
            return AiDexHistoryImport(context, known, counts.keys, selections, newSensors, backup, sourceMaps)
        }
    }
}
