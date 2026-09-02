package tk.glucodata.data.journal

import android.content.Context
import org.json.JSONObject
import tk.glucodata.R
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object JournalTreatmentTransfer {
    private const val SOURCE_KIND_CARBS = "carbs"
    private const val SOURCE_KIND_INSULIN = "insulin"
    private const val SOURCE_KIND_FINGERSTICK = "fingerstick"
    private const val SOURCE_KIND_ACTIVITY = "activity"
    private const val SOURCE_KIND_NOTE = "note"
    private const val MIN_VALID_EPOCH_MS = 946_684_800_000L
    private const val MGDL_PER_MMOLL = 18.0182f

    private val allKinds = listOf(
        SOURCE_KIND_CARBS,
        SOURCE_KIND_INSULIN,
        SOURCE_KIND_FINGERSTICK,
        SOURCE_KIND_ACTIVITY,
        SOURCE_KIND_NOTE
    )

    data class ParsedTreatment(
        val inputs: List<JournalEntryInput>,
        val candidateSourceRecordIds: List<String>,
        val remoteId: String?,
        val deleteOnly: Boolean = false
    )

    fun buildTreatmentJson(
        entry: JournalEntryEntity,
        remoteId: String,
        preset: JournalInsulinPresetEntity?,
        food: JournalFoodEntity?,
        useV3: Boolean,
        includeRemoteId: Boolean = true
    ): JSONObject? {
        val type = JournalEntryType.fromStorage(entry.entryType)
        val timestamp = entry.timestamp.takeIf { it > 0L } ?: return null
        val json = JSONObject()
            .put("date", timestamp)
            .put("created_at", formatIso8601(timestamp))
            .put("utcOffset", 0)
            .put("isValid", true)
            .put("app", "JugglucoNG")
            .put("enteredBy", "JugglucoNG")
            .put("type", type.storageValue)
            .put("journalTitle", entry.title)
            .put("journalSource", entry.originSource ?: entry.source)
            .put("updated_at", formatIso8601(entry.updatedAt.takeIf { it > 0L } ?: timestamp))

        when (type) {
            JournalEntryType.INSULIN -> {
                val units = positiveAmount(entry.amount) ?: return null
                val isBasal = preset?.let { !it.countsTowardIob } ?: false
                json.put("eventType", "Correction Bolus")
                    .put("insulin", units.toDouble())
                    .put("isBasalInsulin", isBasal)
                    .put("insulinType", preset?.displayName ?: entry.title)
                    .put("notes", mergedNotes(if (isBasal) "Long-Acting" else "Rapid-Acting", entry.note))
                preset?.let {
                    json.put("insulinOnsetMinutes", it.onsetMinutes)
                        .put("insulinDurationMinutes", it.durationMinutes)
                }
            }
            JournalEntryType.CARBS -> {
                val grams = positiveAmount(entry.amount) ?: return null
                val durationMinutes = entry.durationMinutes
                    ?: food?.absorptionMinutes
                    ?: defaultAbsorptionMinutes(grams, entry.proteinGrams, entry.fatGrams)
                json.put("eventType", if (grams < 12f) "Carb Correction" else "Meal Bolus")
                    .put("carbs", grams.toDouble())
                    .put("food", food?.displayName ?: entry.title)
                    .put("duration", durationMinutes * 60_000L)
                    .put("durationInMilliseconds", durationMinutes * 60_000L)
                    .put("absorptionTime", durationMinutes)
                putFinite(json, "protein", entry.proteinGrams)
                putFinite(json, "proteinGrams", entry.proteinGrams)
                putFinite(json, "fat", entry.fatGrams)
                putFinite(json, "fatGrams", entry.fatGrams)
                entry.note?.takeIf { it.isNotBlank() }?.let { json.put("notes", it) }
            }
            JournalEntryType.FINGERSTICK -> {
                val mgdl = positiveAmount(entry.glucoseValueMgDl) ?: return null
                json.put("eventType", "BG Check")
                    .put("glucose", mgdl.toDouble())
                    .put("glucoseValueMgDl", mgdl.toDouble())
                    .put("glucoseType", "Finger")
                    .put("units", "mg/dl")
                entry.note?.takeIf { it.isNotBlank() }?.let { json.put("notes", it) }
            }
            JournalEntryType.ACTIVITY -> {
                json.put("eventType", "Exercise")
                    .put("duration", (entry.durationMinutes ?: 0).coerceAtLeast(0))
                    .put("intensity", entry.intensity.orEmpty())
                    .put("notes", mergedNotes(entry.title, entry.note))
            }
            JournalEntryType.NOTE -> {
                val note = mergedNotes(entry.title, entry.note) ?: return null
                json.put("eventType", "Note")
                    .put("notes", note)
            }
        }

        if (includeRemoteId) {
            json.put("_id", remoteId)
                .put("identifier", remoteId)
        }
        if (!useV3) {
            json.remove("identifier")
        }
        return json
    }

/**
     * The same document with the fields v3 will not let a client change taken out.
     *
     * v3 answers an update that carries them with 400 "Field date cannot be modified by the
     * client", and its collection POST rejects a payload with no date. This partial payload
     * therefore goes to PATCH /treatments/{identifier}; the route identifies the document,
     * while the body contains only fields the client may change.
     */
    fun stripImmutableForUpdate(json: JSONObject): JSONObject = json
        .apply {
            remove("date")
            remove("created_at")
            remove("utcOffset")
            // The PATCH route names the document; neither identity field belongs in its body.
            remove("_id")
            remove("identifier")
        }

    fun parseTreatment(
        context: Context,
        treatment: JSONObject,
        source: JournalEntrySource,
        sourcePrefix: String,
        insulinPresets: List<JournalInsulinPreset>
    ): ParsedTreatment? = parseTreatment(
        treatment = treatment,
        source = source,
        sourcePrefix = sourcePrefix,
        insulinPresets = insulinPresets,
        stringResource = context::getString
    )

    internal fun parseTreatment(
        treatment: JSONObject,
        source: JournalEntrySource,
        sourcePrefix: String,
        insulinPresets: List<JournalInsulinPreset>,
        stringResource: (Int) -> String
    ): ParsedTreatment? {
        val timestamp = treatment.optTreatmentTimestampMillis()
        val baseId = treatment.sourceBaseId(timestamp) ?: return null
        val remoteId = treatment.optRemoteId()
        val nightscoutRemoteId = when (source) {
            JournalEntrySource.NIGHTSCOUT -> remoteId
            JournalEntrySource.CLONE,
            JournalEntrySource.CLONE_LOCAL_ICE,
            JournalEntrySource.CLONE_TURN -> treatment.optNonBlankString("nsRemoteId")
            else -> null
        }
        val candidateIds = allKinds.map { kind -> sourceRecordId(sourcePrefix, baseId, kind) }
        val isValid = treatment.optBoolean("isValid", true)
        if (!isValid) {
            return ParsedTreatment(
                inputs = emptyList(),
                candidateSourceRecordIds = candidateIds,
                remoteId = remoteId,
                deleteOnly = true
            )
        }

        val eventType = treatment.optNonBlankString("eventType", "eventtype", "event_type")
        val explicitType = treatment.optJournalEntryType()
        val note = buildNote(
            treatment.optNonBlankString("notes", "note"),
            treatment.optNonBlankString("enteredBy", "device", "app")
        )
        val originSource = treatment.optJournalOriginSource()
        val titleSuffix = treatment.optNonBlankString("journalTitle", "title", "food", "foodType")
            ?: eventType?.takeIf { !it.equals("Note", ignoreCase = true) }
        val inputs = ArrayList<JournalEntryInput>(3)
        val eventKey = eventType.orEmpty().lowercase(Locale.US)
        val safeTimestamp = timestamp ?: return null

        val carbs = treatment.optFiniteFloat("carbs", "carb", "enteredCarbs", "enteredcarbs", "grams", "carbsGrams")
            ?: treatment.optFiniteFloat("amount").takeIf {
                explicitType == JournalEntryType.CARBS || eventKey.contains("carb")
            }
        if (carbs != null && abs(carbs) >= 0.001f) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.CARBS,
                    title = titleSuffix ?: stringResource(R.string.journal_aaps_carbs_title),
                    note = note,
                    amount = carbs,
                    durationMinutes = treatment.optDurationMinutes(),
                    proteinGrams = treatment.optPositiveFloat("protein", "proteinGrams"),
                    fatGrams = treatment.optPositiveFloat("fat", "fatGrams"),
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_CARBS),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        val insulin = treatment.optPositiveFloat("insulin", "enteredInsulin", "enteredinsulin", "bolus")
            ?: treatment.optPositiveFloat("amount").takeIf { explicitType == JournalEntryType.INSULIN }
        if (insulin != null) {
            val preset = chooseInsulinPreset(insulinPresets, treatment)
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.INSULIN,
                    title = treatment.optNonBlankString("insulinType")
                        ?: preset?.displayName
                        ?: titleSuffix
                        ?: stringResource(R.string.journal_aaps_insulin_title),
                    note = note,
                    amount = insulin,
                    insulinPresetId = preset?.id,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_INSULIN),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        val glucoseMgdl = treatment.optGlucoseMgdl()
        if (glucoseMgdl != null &&
            (explicitType == JournalEntryType.FINGERSTICK ||
                eventKey.contains("bg check") ||
                eventKey.contains("finger") ||
                inputs.isEmpty())
        ) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.FINGERSTICK,
                    title = titleSuffix ?: stringResource(R.string.journal_type_fingerstick),
                    note = note,
                    glucoseValueMgDl = glucoseMgdl,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_FINGERSTICK),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (explicitType == JournalEntryType.ACTIVITY || eventKey.contains("exercise")) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.ACTIVITY,
                    title = titleSuffix ?: stringResource(R.string.journal_type_activity),
                    note = note,
                    durationMinutes = treatment.optDurationMinutes(),
                    intensity = treatment.optJournalIntensity(),
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_ACTIVITY),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (inputs.isEmpty() &&
            (explicitType == JournalEntryType.NOTE || eventKey.contains("note") || eventKey.contains("announcement") || note != null)
        ) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.NOTE,
                    title = titleSuffix ?: stringResource(R.string.journal_type_note),
                    note = note ?: eventType,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_NOTE),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (inputs.isEmpty()) return null
        return ParsedTreatment(
            inputs = inputs,
            candidateSourceRecordIds = candidateIds,
            remoteId = remoteId
        )
    }

    fun hasAnyRemoteIdentifier(treatment: JSONObject, remoteIds: Set<String>): Boolean {
        if (remoteIds.isEmpty()) return false
        return treatment.remoteIdentifiers().any { id -> id in remoteIds }
    }

    fun sourceRecordIdsForTreatment(treatment: JSONObject, sourcePrefix: String): List<String> {
        val baseId = treatment.sourceBaseId(treatment.optTreatmentTimestampMillis()) ?: return emptyList()
        return sourceRecordIdsForBaseId(sourcePrefix, baseId)
    }

    fun sourceRecordIdsForBaseId(sourcePrefix: String, baseId: String): List<String> =
        allKinds.map { kind -> sourceRecordId(sourcePrefix, baseId, kind) }

    internal fun sourceRecordIdForBaseId(
        sourcePrefix: String,
        baseId: String,
        type: JournalEntryType,
    ): String = sourceRecordId(sourcePrefix, baseId, type.storageValue)

    private fun sourceRecordId(sourcePrefix: String, baseId: String, kind: String): String =
        "$sourcePrefix:$baseId:$kind"

    private fun chooseInsulinPreset(
        presets: List<JournalInsulinPreset>,
        treatment: JSONObject
    ): JournalInsulinPreset? {
        val text = listOfNotNull(
            treatment.optNonBlankString("eventType", "eventtype"),
            treatment.optNonBlankString("notes", "note"),
            treatment.optNonBlankString("insulinType", "type"),
            treatment.optNonBlankString("enteredBy", "device", "app")
        ).joinToString(" ").lowercase(Locale.US)
        val isBasal = treatment.optBoolean("isBasalInsulin", false) ||
            text.contains("basal") ||
            text.contains("long") ||
            text.contains("nph")
        val candidates = presets.filter { !it.isArchived }
            .ifEmpty { presets }
        return if (isBasal) {
            candidates.filter { !it.countsTowardIob }.minByOrNull { it.sortOrder }
                ?: candidates.minByOrNull { it.sortOrder }
        } else {
            candidates.filter { it.countsTowardIob }.minByOrNull { it.sortOrder }
                ?: candidates.minByOrNull { it.sortOrder }
        }
    }

    private fun JSONObject.optRemoteId(): String? =
        remoteIdentifiers().firstOrNull()

    private fun JSONObject.remoteIdentifiers(): List<String> =
        listOfNotNull(
            optNonBlankString("identifier"),
            optNonBlankString("_id"),
            optNonBlankString("id"),
            optNonBlankString("NSCLIENT_ID"),
            optNonBlankString("pumpId")
        ).distinct()

    private fun JSONObject.sourceBaseId(timestamp: Long?): String? {
        optRemoteId()?.let { return it }
        if (timestamp == null) return null
        val fingerprint = listOf(
            timestamp,
            optNonBlankString("eventType", "eventtype", "type").orEmpty(),
            optFiniteFloat("carbs", "carb", "enteredCarbs", "enteredcarbs", "grams", "amount")?.toString().orEmpty(),
            optPositiveFloat("insulin", "enteredInsulin", "enteredinsulin", "bolus", "amount")?.toString().orEmpty(),
            optGlucoseMgdl()?.toString().orEmpty(),
            optNonBlankString("notes", "note").orEmpty()
        ).joinToString("|")
        return "hash:${fingerprint.sha256Short()}"
    }

    private fun JSONObject.optTreatmentTimestampMillis(): Long? {
        for (key in listOf("date", "mills", "millis", "timestamp", "time", "createdAt", "created_at_millis")) {
            val normalized = optEpochMillis(key)
            if (normalized != null) return normalized
        }
        for (key in listOf("created_at", "dateString", "createdAt", "timestamp", "time")) {
            val parsed = optNonBlankString(key)?.let(::parseDateString)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optEpochMillis(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val longValue = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
            is String -> value.trim().toDoubleOrNull()?.toLong()
            else -> null
        } ?: return null
        val millis = when (longValue) {
            in 1L until 10_000_000_000L -> longValue * 1000L
            in 10_000_000_000L until 100_000_000_000_000L -> longValue
            in 100_000_000_000_000L until 100_000_000_000_000_000L -> longValue / 1000L
            else -> longValue
        }
        return millis.takeIf { it >= MIN_VALID_EPOCH_MS }
    }

    private fun parseDateString(text: String): Long? {
        try {
            return Instant.parse(text).toEpochMilli().takeIf { it >= MIN_VALID_EPOCH_MS }
        } catch (ignored: DateTimeParseException) {
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(text)?.time?.takeIf { it >= MIN_VALID_EPOCH_MS }
            } catch (ignored: ParseException) {
            }
        }
        return null
    }

    private fun JSONObject.optDurationMinutes(): Int? {
        optFiniteFloat("durationInMilliseconds")?.let { millis ->
            if (millis > 0f) return (millis / 60_000f).toInt().coerceIn(1, 24 * 60)
        }
        optFiniteFloat("durationMinutes", "absorptionTime", "absorptionMinutes")?.let { minutes ->
            if (minutes > 0f) return minutes.toInt().coerceIn(1, 24 * 60)
        }
        val duration = optFiniteFloat("duration") ?: return null
        if (duration <= 0f) return null
        val minutes = if (duration > 24f * 60f) duration / 60_000f else duration
        return minutes.toInt().coerceIn(1, 24 * 60)
    }

    private fun JSONObject.optJournalEntryType(): JournalEntryType? {
        for (key in listOf("type", "entryType", "journalType")) {
            val value = optNonBlankString(key)?.lowercase(Locale.US) ?: continue
            JournalEntryType.entries.firstOrNull { it.storageValue == value }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optJournalOriginSource(): JournalEntrySource? {
        val value = optNonBlankString("journalSource", "originSource") ?: return null
        return JournalEntrySource.entries.firstOrNull { it.storageValue == value.lowercase(Locale.US) }
    }

    private fun JSONObject.optJournalIntensity(): JournalIntensity? {
        val value = optNonBlankString("intensity")?.lowercase(Locale.US) ?: return null
        return JournalIntensity.fromStorage(value)
    }

    private fun JSONObject.optGlucoseMgdl(): Float? {
        firstFiniteField("glucoseValueMgDl", "glucose_mgdl", "mgdl", "mbg")?.let { return it }
        firstFiniteField("glucose_mmol", "mmol")?.let { return it * MGDL_PER_MMOLL }
        val glucose = firstFiniteField("glucose") ?: return null
        val units = optNonBlankString("units", "unit")
            ?.lowercase(Locale.US)
            .orEmpty()
        return if (units.contains("mmol")) glucose * MGDL_PER_MMOLL else glucose
    }

    private fun JSONObject.firstFiniteField(vararg keys: String): Float? =
        keys.asSequence()
            .mapNotNull { key -> optFiniteFloat(key) }
            .firstOrNull { it > 0f }

    private fun JSONObject.optPositiveFloat(vararg keys: String): Float? =
        optFiniteFloat(*keys)?.takeIf { it > 0.0001f }

    private fun JSONObject.optFiniteFloat(vararg keys: String): Float? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            val parsed = when (value) {
                is Number -> value.toFloat()
                is String -> value.trim().replace(',', '.').toFloatOrNull()
                else -> null
            }
            if (parsed != null && parsed.isFinite()) return parsed
        }
        return null
    }

    private fun JSONObject.optNonBlankString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val text = optString(key, "").trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return null
    }

    private fun positiveAmount(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > 0f }

    private fun putFinite(json: JSONObject, key: String, value: Float?) {
        if (value != null && value.isFinite()) {
            json.put(key, value.toDouble())
        }
    }

    private fun mergedNotes(vararg parts: String?): String? {
        val unique = LinkedHashSet<String>()
        parts
            .flatMap { it.orEmpty().split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(unique::add)
        return unique.joinToString(" | ").takeIf { it.isNotBlank() }
    }

    private fun buildNote(vararg parts: String?): String? = mergedNotes(*parts)

    private fun defaultAbsorptionMinutes(grams: Float, protein: Float?, fat: Float?): Int {
        val macroExtra = ((protein ?: 0f) * 1.5f) + ((fat ?: 0f) * 2.5f)
        return (60f + grams * 2f + macroExtra).toInt().coerceIn(30, 360)
    }

    private val isoFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun formatIso8601(epochMillis: Long): String =
        isoFormatter.get()!!.format(Date(epochMillis))

    private fun String.sha256Short(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
