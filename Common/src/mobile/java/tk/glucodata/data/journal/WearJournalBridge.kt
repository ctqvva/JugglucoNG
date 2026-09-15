package tk.glucodata.data.journal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.NotificationHistorySource
import tk.glucodata.WearJournalSync
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Phone side of the watch journal. Encodes what the watch shows and applies what
 * it sends back.
 *
 * Reached reflectively from `tk.glucodata.JournalAccess` in src/main, because the
 * journal's Room layer only exists in this source set. Encoding lives here so the
 * reflective surface stays two byte-array methods wide — see the wire format in
 * [WearJournalSync].
 */
object WearJournalBridge {
    private const val LOG_ID = "WearJournalBridge"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"

    /** Keeps a watch payload inside a single Data Layer message. */
    private const val MAX_ENTRIES = 60
    private const val MAX_TITLE_BYTES = 40
    private const val MAX_CURVE_POINTS = 24

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isJournalEnabled(): Boolean = prefs()?.getBoolean(JOURNAL_ENABLED_KEY, false) ?: false

    /**
     * Encoded journal for the watch, or null when the journal is switched off —
     * the watch then hides the feature rather than showing an empty list.
     */
    @JvmStatic
    fun serveEntries(fromMs: Long): ByteArray? {
        if (!isJournalEnabled()) return null
        return runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val repository = JournalRepository()
                    val entries = repository
                        .getEntriesBetweenSnapshot(fromMs, System.currentTimeMillis())
                        .sortedByDescending { it.timestamp }
                        .take(MAX_ENTRIES)
                    val presets = repository.getInsulinPresetsSnapshot()
                        .filterNot { it.isArchived }
                    encode(entries, presets)
                }
            }
        }.onFailure { Log.stack(LOG_ID, "serveEntries", it) }.getOrNull()
    }

    /** Applies an add or delete the watch sent. */
    @JvmStatic
    fun applyCommand(data: ByteArray): Boolean {
        if (!isJournalEnabled()) {
            Log.w(LOG_ID, "ignoring watch journal command: journal disabled")
            return false
        }
        val command = WearJournalSync.decodeCommand(data) ?: run {
            Log.w(LOG_ID, "ignoring malformed watch journal command")
            return false
        }
        return runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val repository = JournalRepository()
                    when (command.command) {
                        WearJournalSync.CMD_ADD -> {
                            val type = typeOf(command.type)
                            repository.upsertEntry(
                                JournalEntryInput(
                                    timestamp = command.timestampMs.takeIf { it > 0L }
                                        ?: System.currentTimeMillis(),
                                    sensorSerial = runCatching {
                                        NotificationHistorySource.resolveSensorSerial()
                                    }.getOrNull(),
                                    type = type,
                                    title = titleFor(type, command.amount),
                                    amount = command.amount.takeIf { it.isFinite() && it > 0f },
                                    insulinPresetId = command.presetId.takeIf {
                                        it > 0L && type == JournalEntryType.INSULIN
                                    },
                                    source = JournalEntrySource.MANUAL,
                                ),
                            )
                            true
                        }
                        WearJournalSync.CMD_DELETE -> {
                            if (command.id <= 0L) {
                                false
                            } else {
                                repository.deleteEntry(command.id)
                                true
                            }
                        }
                        else -> false
                    }
                }
            }
        }.onFailure { Log.stack(LOG_ID, "applyCommand", it) }.getOrDefault(false)
    }

    private fun typeOf(wireType: Int): JournalEntryType = when (wireType) {
        WearJournalSync.TYPE_INSULIN -> JournalEntryType.INSULIN
        WearJournalSync.TYPE_CARBS -> JournalEntryType.CARBS
        WearJournalSync.TYPE_FINGERSTICK -> JournalEntryType.FINGERSTICK
        WearJournalSync.TYPE_ACTIVITY -> JournalEntryType.ACTIVITY
        else -> JournalEntryType.NOTE
    }

    private fun wireTypeOf(type: JournalEntryType): Int = when (type) {
        JournalEntryType.INSULIN -> WearJournalSync.TYPE_INSULIN
        JournalEntryType.CARBS -> WearJournalSync.TYPE_CARBS
        JournalEntryType.FINGERSTICK -> WearJournalSync.TYPE_FINGERSTICK
        JournalEntryType.ACTIVITY -> WearJournalSync.TYPE_ACTIVITY
        JournalEntryType.NOTE -> WearJournalSync.TYPE_NOTE
    }

    private fun titleFor(type: JournalEntryType, amount: Float): String {
        val rounded = if (amount.isFinite()) amount else 0f
        return when (type) {
            JournalEntryType.INSULIN -> "Insulin ${formatAmount(rounded)}U"
            JournalEntryType.CARBS -> "Carbs ${formatAmount(rounded)}g"
            else -> type.storageValue
        }
    }

    private fun formatAmount(amount: Float): String =
        if (amount % 1f < 0.05f) amount.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", amount)

    /**
     * The preset's curve, capped so one payload stays inside a single Data
     * Layer message. A preset that does not count toward IOB contributes no
     * curve, which is how the phone's own model treats it.
     */
    private fun curvePointsOf(preset: JournalInsulinPreset): List<JournalCurvePoint> {
        if (preset.isArchived || !preset.countsTowardIob) return emptyList()
        return preset.curvePoints.take(MAX_CURVE_POINTS)
    }

    private fun encode(
        entries: List<JournalEntry>,
        presets: List<JournalInsulinPreset>,
    ): ByteArray {
        val presetsById = presets.associateBy { it.id }
        val encodedEntries = entries.map { entry ->
            val title = entry.title.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size <= MAX_TITLE_BYTES) it else it.copyOf(MAX_TITLE_BYTES)
            }
            val snapshot = parseJournalCurve(entry.insulinCurveJsonSnapshot)
                .take(MAX_CURVE_POINTS)
            val fallback = entry.insulinPresetId
                ?.let(presetsById::get)
                ?.let(::curvePointsOf)
                .orEmpty()
            EncodedEntry(
                entry = entry,
                title = title,
                wireType = wireTypeOf(entry.type),
                curve = if (snapshot.size >= 2) snapshot else fallback
            )
        }
        val encodedPresets = presets.map { preset ->
            preset to preset.displayName.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size <= MAX_TITLE_BYTES) it else it.copyOf(MAX_TITLE_BYTES)
            }
        }
        // v3 adds the immutable resolved curve per entry. The preset curve is
        // retained for v2 fallback and for watch entries awaiting phone sync.
        val size = 1 + 1 + 2 +
            encodedEntries.sumOf { 8 + 8 + 1 + 4 + 1 + it.title.size + 8 + 1 + it.curve.size * 6 } +
            2 + encodedPresets.sumOf {
                8 + 4 + 1 + it.second.size + 1 + curvePointsOf(it.first).size * 6
            }
        val buffer = ByteBuffer.allocate(size)
        buffer.put(WearJournalSync.VERSION.toByte())
        buffer.put(1)
        buffer.putShort(encodedEntries.size.toShort())
        encodedEntries.forEach { encoded ->
            val entry = encoded.entry
            buffer.putLong(entry.timestamp)
            buffer.putLong(entry.id)
            buffer.put(encoded.wireType.toByte())
            buffer.putFloat(entry.amount ?: Float.NaN)
            buffer.put(encoded.title.size.toByte())
            buffer.put(encoded.title)
            buffer.putLong(entry.insulinPresetId ?: 0L)
            buffer.put(encoded.curve.size.toByte())
            encoded.curve.forEach { point ->
                buffer.putShort(point.minute.coerceIn(0, 0xFFFF).toShort())
                buffer.putFloat(point.activity)
            }
        }
        buffer.putShort(encodedPresets.size.toShort())
        encodedPresets.forEach { (preset, name) ->
            buffer.putLong(preset.id)
            buffer.putFloat(Float.NaN)
            buffer.put(name.size.toByte())
            buffer.put(name)
            val curve = curvePointsOf(preset)
            buffer.put(curve.size.toByte())
            curve.forEach { point ->
                buffer.putShort(point.minute.coerceIn(0, 0xFFFF).toShort())
                buffer.putFloat(point.activity)
            }
        }
        return buffer.array()
    }

    private data class EncodedEntry(
        val entry: JournalEntry,
        val title: ByteArray,
        val wireType: Int,
        val curve: List<JournalCurvePoint>
    )
}
