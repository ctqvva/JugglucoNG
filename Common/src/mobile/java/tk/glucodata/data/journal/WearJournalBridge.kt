package tk.glucodata.data.journal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.JournalBridge
import tk.glucodata.Log
import tk.glucodata.NotificationHistorySource
import tk.glucodata.WearJournalSync
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.PredictionModelProfileStore
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Phone side of the watch journal. Encodes what the watch shows and applies what
 * it sends back.
 *
 * Registered as a [JournalBridge] from the mobile `Specific.registerBridges()`
 * (plan P1/Q1); the journal's Room layer only exists in this source set. Encoding
 * lives here so the shared surface stays byte-array wide — see the wire format in
 * [WearJournalSync].
 */
object WearJournalBridge : JournalBridge {
    private const val LOG_ID = "WearJournalBridge"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"

    /** Keeps a watch payload inside a single Data Layer message; shared with the watch's truncation check. */
    private const val MAX_ENTRIES = WearJournalSync.SERVED_ENTRY_CAP
    private const val MAX_TITLE_BYTES = 40
    private const val MAX_CURVE_POINTS = 24

    private const val MAX_ABSORPTION_MINUTES = 0xFFFF

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isJournalEnabled(): Boolean = prefs()?.getBoolean(JOURNAL_ENABLED_KEY, false) ?: false

    /**
     * Encoded journal for the watch, or null when the journal is switched off —
     * the watch then hides the feature rather than showing an empty list.
     */
    override fun serveEntries(fromMs: Long): ByteArray? {
        if (!isJournalEnabled()) return null
        return runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val repository = JournalRepository()
                    val entries = repository
                        .getEntriesBetweenSnapshot(fromMs, System.currentTimeMillis())
                        .filterNot { it.type == JournalEntryType.TEMP_BASAL }
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
    override fun applyCommand(data: ByteArray): Boolean {
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
                                    // The id the watch gave this entry, stored where every other
                                    // producer's is (`meter:`, `pen:`, `clone:`) so the unique index
                                    // and upsertEntry's match make a re-sent add the same row
                                    // instead of a second one (#502).
                                    sourceRecordId = command.entryIdentity.takeIf { it.isNotEmpty() },
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
        // Not shown on the watch; filtered out before encoding anyway.
        JournalEntryType.TEMP_BASAL, JournalEntryType.NOTE -> WearJournalSync.TYPE_NOTE
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
     * Layer message (see [thinCurve]: a longer curve keeps its true end). A
     * preset that does not count toward IOB contributes no curve, which is how
     * the phone's own model treats it.
     */
    private fun curvePointsOf(preset: JournalInsulinPreset): List<JournalCurvePoint> {
        if (preset.isArchived || !preset.countsTowardIob) return emptyList()
        return thinCurve(preset.curvePoints, MAX_CURVE_POINTS)
    }

    /**
     * At most [cap] points, always including the first and the last: the watch's IOB maths takes
     * the last received point as the end of the curve, so truncating would end it early. Interior
     * points are picked evenly by index (deterministic). A curve within the cap is returned as is.
     */
    internal fun thinCurve(points: List<JournalCurvePoint>, cap: Int): List<JournalCurvePoint> {
        if (points.size <= cap) return points
        if (cap < 2) return points.take(cap)
        val last = points.size - 1
        return List(cap) { i -> points[Math.round(i.toDouble() * last / (cap - 1)).toInt()] }
    }

    /**
     * The curve the watch counts for [entry]. A dose whose preset is present and does not count
     * toward IOB sends none even if it has a snapshot, as the phone drops it
     * (`JournalIobCalculator.toDose`). Residual (#500): a non-counting preset that is also
     * archived is absent from the presets (`serveEntries` filters archived), so its snapshot is
     * still sent and the watch counts that dose.
     */
    private fun resolveCurve(
        entry: JournalEntry,
        presetsById: Map<Long, JournalInsulinPreset>
    ): List<JournalCurvePoint> {
        val preset = entry.insulinPresetId?.let(presetsById::get)
        if (preset != null && !preset.countsTowardIob) return emptyList()
        val snapshot = thinCurve(parseJournalCurve(entry.insulinCurveJsonSnapshot), MAX_CURVE_POINTS)
        return if (snapshot.size >= 2) snapshot else preset?.let(::curvePointsOf).orEmpty()
    }

    // internal so the IOB agreement test can encode through the real bridge rather than a
    // hand-built copy of this shape: the point of that test is that the wire and the phone
    // agree, which a mirror of the encoder would assume rather than check.
    internal fun encode(
        entries: List<JournalEntry>,
        presets: List<JournalInsulinPreset>,
        carbAbsorptionGramsPerHourAt: (timestampMillis: Long) -> Float = phoneCarbAbsorptionRate(),
    ): ByteArray {
        val presetsById = presets.associateBy { it.id }
        val encodedEntries = entries.map { entry ->
            val title = entry.title.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size <= MAX_TITLE_BYTES) it else it.copyOf(MAX_TITLE_BYTES)
            }
            EncodedEntry(
                entry = entry,
                title = title,
                wireType = wireTypeOf(entry.type),
                curve = resolveCurve(entry, presetsById)
            )
        }
        val encodedPresets = presets.map { preset ->
            preset to preset.displayName.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size <= MAX_TITLE_BYTES) it else it.copyOf(MAX_TITLE_BYTES)
            }
        }
        // v3 adds the immutable resolved curve per entry. The preset curve is
        // retained for v2 fallback and for watch entries awaiting phone sync.
        // Appended, after the presets, and read only by a build that knows about it: the entry
        // identities the watch needs to recognise an entry of its own (#502).
        val identities = encodedEntries.map { encoded ->
            val raw = encoded.entry.sourceRecordId
                ?.takeIf { it.isNotEmpty() && it.length <= WearJournalSync.MAX_IDENTITY_BYTES }
                ?.toByteArray(StandardCharsets.UTF_8)
                ?: ByteArray(0)
            raw
        }
        // Appended after the identities, read only by a build that knows it: the minutes the phone's
        // own COB absorbs each carb entry over, so the watch needs no rate of its own.
        val absorption = encodedEntries.map { absorptionMinutesFor(it.entry, carbAbsorptionGramsPerHourAt) }
        val size = 1 + 1 + 2 +
            encodedEntries.sumOf { 8 + 8 + 1 + 4 + 1 + it.title.size + 8 + 1 + it.curve.size * 6 } +
            2 + encodedPresets.sumOf {
                8 + 4 + 1 + it.second.size + 1 + curvePointsOf(it.first).size * 6
            } +
            2 + identities.sumOf { 1 + it.size } +
            2 + absorption.size * 2
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
        buffer.putShort(identities.size.toShort())
        identities.forEach { identity ->
            buffer.put(identity.size.toByte())
            buffer.put(identity)
        }
        buffer.putShort(absorption.size.toShort())
        absorption.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    /**
     * The minutes `JournalIobMath.activeCarbsGrams` would absorb [entry] over at this instant, or 0
     * (absent) for anything it does not count as carbs. Same rule as the phone's COB: an explicit
     * duration wins (the maths floors it at one minute), otherwise grams / the rate at the entry's
     * own timestamp, clamped to 30..360. Pure, and it never throws on a hostile value.
     */
    internal fun absorptionMinutesFor(
        entry: JournalEntry,
        rateAt: (timestampMillis: Long) -> Float,
    ): Int {
        if (entry.type != JournalEntryType.CARBS) return 0
        val grams = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return 0
        entry.durationMinutes?.let { return it.coerceIn(1, MAX_ABSORPTION_MINUTES) }
        val resolved = (grams / rateAt(entry.timestamp) * 60f).coerceIn(30f, 360f)
        // NaN survives coerceIn; it has no honest minutes, so it is absent rather than a guess.
        return if (resolved.isNaN()) 0 else Math.round(resolved)
    }

    /**
     * The phone's absorption rate by time of day, the same source `OutboundApiJournalSnapshot`
     * feeds its COB from. The profile is read once per encode, and only if a carb needs it.
     */
    private fun phoneCarbAbsorptionRate(): (Long) -> Float {
        val profile: PredictionModelProfile? by lazy {
            runCatching {
                PredictionModelProfileStore.load(requireNotNull(prefs()) { "no application context" })
            }.onFailure { Log.stack(LOG_ID, "absorption profile", it) }.getOrNull()
        }
        return { timestamp ->
            profile?.parametersAt(timestamp)?.carbAbsorptionGramsPerHour
                ?: PredictionModelProfile.DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR
        }
    }

    private data class EncodedEntry(
        val entry: JournalEntry,
        val title: ByteArray,
        val wireType: Int,
        val curve: List<JournalCurvePoint>
    )
}
