package tk.glucodata.data.journal

import org.json.JSONObject
import tk.glucodata.CloneRecoveryRecord

internal data class CloneJournalEntryRecord(
    val recoveryId: String,
    val legacyStableId: String,
    val entry: JournalEntryEntity,
    val insulinPreset: JournalInsulinPresetEntity?,
    val food: JournalFoodEntity?,
)

internal data class CloneJournalTombstoneRecord(
    val recoveryId: String?,
    val legacyStableBaseId: String,
    val deletedAt: Long,
)

internal object CloneJournalRecoveryRecords {
    const val TOMBSTONE = "journal_tombstone"
    const val ENTRY = "journal_entry"

    val recordTypes: Set<String> = linkedSetOf(TOMBSTONE, ENTRY)

    fun encode(record: CloneJournalTombstoneRecord): JSONObject = JSONObject()
        .put("recoveryId", record.recoveryId ?: JSONObject.NULL)
        .put("legacyStableBaseId", record.legacyStableBaseId)
        .put("deletedAt", record.deletedAt)

    fun encode(record: CloneJournalEntryRecord): JSONObject = JSONObject()
        .put("recoveryId", record.recoveryId)
        .put("legacyStableId", record.legacyStableId)
        .put("timestamp", record.entry.timestamp)
        .put("sensorSerial", record.entry.sensorSerial ?: JSONObject.NULL)
        .put("entryType", record.entry.entryType)
        .put("title", record.entry.title)
        .put("note", record.entry.note ?: JSONObject.NULL)
        .put("amount", record.entry.amount?.toDouble() ?: JSONObject.NULL)
        .put(
            "glucoseValueMgDl",
            record.entry.glucoseValueMgDl?.toDouble() ?: JSONObject.NULL,
        )
        .put("durationMinutes", record.entry.durationMinutes ?: JSONObject.NULL)
        .put("intensity", record.entry.intensity ?: JSONObject.NULL)
        .put("proteinGrams", record.entry.proteinGrams?.toDouble() ?: JSONObject.NULL)
        .put("fatGrams", record.entry.fatGrams?.toDouble() ?: JSONObject.NULL)
        .put("source", record.entry.source)
        .put("originSource", record.entry.originSource ?: JSONObject.NULL)
        .put("sourceRecordId", record.entry.sourceRecordId ?: JSONObject.NULL)
        .put("createdAt", record.entry.createdAt)
        .put("updatedAt", record.entry.updatedAt)
        .put("nsRemoteId", record.entry.nsRemoteId ?: JSONObject.NULL)
        .put("insulinPreset", record.insulinPreset?.let(::encodePreset) ?: JSONObject.NULL)
        .put("food", record.food?.let(::encodeFood) ?: JSONObject.NULL)

    fun decodeTombstone(payload: JSONObject): CloneJournalTombstoneRecord {
        payload.requireExactKeys("recoveryId", "legacyStableBaseId", "deletedAt")
        val recoveryId = payload.optionalString("recoveryId", RECOVERY_ID_LENGTH)?.also { value ->
            require(CloneJournalIdentity.isValidRecoveryId(value)) {
                "Invalid Clone recovery journal identity"
            }
        }
        val legacyStableBaseId = payload.requireString(
            "legacyStableBaseId",
            MAX_STABLE_ID_LENGTH,
        )
        CloneJournalIdentity.entryIdsForTombstoneBase(legacyStableBaseId)
        return CloneJournalTombstoneRecord(
            recoveryId = recoveryId,
            legacyStableBaseId = legacyStableBaseId,
            deletedAt = payload.requirePositiveLong("deletedAt"),
        )
    }

    fun decodeEntry(payload: JSONObject): CloneJournalEntryRecord {
        payload.requireExactKeys(
            "recoveryId",
            "legacyStableId",
            "timestamp",
            "sensorSerial",
            "entryType",
            "title",
            "note",
            "amount",
            "glucoseValueMgDl",
            "durationMinutes",
            "intensity",
            "proteinGrams",
            "fatGrams",
            "source",
            "originSource",
            "sourceRecordId",
            "createdAt",
            "updatedAt",
            "nsRemoteId",
            "insulinPreset",
            "food",
        )
        val typeValue = payload.requireString("entryType", MAX_ENUM_LENGTH)
        val type = JournalEntryType.entries.firstOrNull { it.storageValue == typeValue }
            ?: throw IllegalArgumentException("Invalid Clone recovery journal entry type")
        val sourceValue = payload.requireString("source", MAX_ENUM_LENGTH)
        require(JournalEntrySource.entries.any { it.storageValue == sourceValue }) {
            "Invalid Clone recovery journal source"
        }
        val originSource = payload.optionalString("originSource", MAX_ENUM_LENGTH)
        require(originSource == null || JournalEntrySource.entries.any { it.storageValue == originSource }) {
            "Invalid Clone recovery journal origin source"
        }
        val intensity = payload.optionalString("intensity", MAX_ENUM_LENGTH)
        require(intensity == null || JournalIntensity.entries.any { it.storageValue == intensity }) {
            "Invalid Clone recovery journal intensity"
        }
        val glucose = payload.optionalFiniteFloat("glucoseValueMgDl")
        require(glucose == null || glucose > 0f) { "Invalid Clone recovery journal glucose value" }
        val duration = payload.optionalInt("durationMinutes")
        require(duration == null || duration >= 0) { "Invalid Clone recovery journal duration" }
        val protein = payload.optionalFiniteFloat("proteinGrams")
        val fat = payload.optionalFiniteFloat("fatGrams")
        require(protein == null || protein >= 0f) { "Invalid Clone recovery journal protein" }
        require(fat == null || fat >= 0f) { "Invalid Clone recovery journal fat" }
        val recoveryId = payload.requireString("recoveryId", RECOVERY_ID_LENGTH)
        require(CloneJournalIdentity.isValidRecoveryId(recoveryId)) {
            "Invalid Clone recovery journal identity"
        }
        val legacyStableId = payload.requireString("legacyStableId", MAX_STABLE_ID_LENGTH)
        require(legacyStableId.endsWith(":${type.storageValue}")) {
            "Clone recovery journal identity does not match its type"
        }
        val createdAt = payload.requirePositiveLong("createdAt")
        val updatedAt = payload.requirePositiveLong("updatedAt")
        val entry = JournalEntryEntity(
            timestamp = payload.requirePositiveLong("timestamp"),
            sensorSerial = payload.optionalString("sensorSerial", MAX_SENSOR_SERIAL_LENGTH),
            entryType = type.storageValue,
            title = payload.requireStringAllowEmpty("title", MAX_TITLE_LENGTH),
            note = payload.optionalStringAllowEmpty("note", MAX_NOTE_LENGTH),
            amount = payload.optionalFiniteFloat("amount"),
            glucoseValueMgDl = glucose,
            durationMinutes = duration,
            intensity = intensity,
            insulinPresetId = null,
            foodId = null,
            proteinGrams = protein,
            fatGrams = fat,
            source = sourceValue,
            originSource = originSource,
            sourceRecordId = payload.optionalString("sourceRecordId", MAX_STABLE_ID_LENGTH),
            recoveryId = recoveryId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            nsUploadedAt = null,
            nsRemoteId = payload.optionalString("nsRemoteId", MAX_REMOTE_ID_LENGTH),
        )
        return CloneJournalEntryRecord(
            recoveryId = recoveryId,
            legacyStableId = legacyStableId,
            entry = entry,
            insulinPreset = payload.optionalObject("insulinPreset")?.let(::decodePreset),
            food = payload.optionalObject("food")?.let(::decodeFood),
        )
    }

    fun orderedValidator(): suspend (CloneRecoveryRecord) -> Unit {
        var lastOrder = -1
        return { record ->
            val order = when (record.type) {
                TOMBSTONE -> {
                    decodeTombstone(record.payload)
                    0
                }
                ENTRY -> {
                    decodeEntry(record.payload)
                    1
                }
                else -> throw IllegalArgumentException(
                    "Unsupported Clone journal recovery record type",
                )
            }
            require(order >= lastOrder) { "Clone journal recovery records are out of order" }
            lastOrder = order
        }
    }

    private fun encodePreset(preset: JournalInsulinPresetEntity): JSONObject = JSONObject()
        .put("displayName", preset.displayName)
        .put("onsetMinutes", preset.onsetMinutes)
        .put("durationMinutes", preset.durationMinutes)
        .put("accentColor", preset.accentColor)
        .put("curveJson", preset.curveJson)
        .put("isBuiltIn", preset.isBuiltIn)
        .put("isArchived", preset.isArchived)
        .put("countsTowardIob", preset.countsTowardIob)
        .put("sortOrder", preset.sortOrder)
        .put("useForCalculation", preset.useForCalculation)

    private fun decodePreset(payload: JSONObject): JournalInsulinPresetEntity {
        payload.requireExactKeys(
            "displayName",
            "onsetMinutes",
            "durationMinutes",
            "accentColor",
            "curveJson",
            "isBuiltIn",
            "isArchived",
            "countsTowardIob",
            "sortOrder",
            "useForCalculation",
        )
        val onset = payload.requireInt("onsetMinutes")
        val duration = payload.requireInt("durationMinutes")
        require(onset in 0..MAX_DEPENDENCY_DURATION_MINUTES) {
            "Invalid Clone recovery insulin onset"
        }
        require(duration in onset..MAX_DEPENDENCY_DURATION_MINUTES) {
            "Invalid Clone recovery insulin duration"
        }
        return JournalInsulinPresetEntity(
            displayName = payload.requireString("displayName", MAX_DEPENDENCY_NAME_LENGTH),
            onsetMinutes = onset,
            durationMinutes = duration,
            accentColor = payload.requireInt("accentColor"),
            curveJson = payload.requireStringAllowEmpty("curveJson", MAX_CURVE_LENGTH),
            isBuiltIn = payload.requireBoolean("isBuiltIn"),
            isArchived = payload.requireBoolean("isArchived"),
            countsTowardIob = payload.requireBoolean("countsTowardIob"),
            sortOrder = payload.requireInt("sortOrder"),
            useForCalculation = payload.requireBoolean("useForCalculation"),
        )
    }

    private fun encodeFood(food: JournalFoodEntity): JSONObject = JSONObject()
        .put("displayName", food.displayName)
        .put("carbsGrams", food.carbsGrams.toDouble())
        .put("proteinGrams", food.proteinGrams?.toDouble() ?: JSONObject.NULL)
        .put("fatGrams", food.fatGrams?.toDouble() ?: JSONObject.NULL)
        .put("absorptionMinutes", food.absorptionMinutes)
        .put("accentColor", food.accentColor)
        .put("isBuiltIn", food.isBuiltIn)
        .put("isArchived", food.isArchived)
        .put("sortOrder", food.sortOrder)
        .put("createdAt", food.createdAt)
        .put("updatedAt", food.updatedAt)

    private fun decodeFood(payload: JSONObject): JournalFoodEntity {
        payload.requireExactKeys(
            "displayName",
            "carbsGrams",
            "proteinGrams",
            "fatGrams",
            "absorptionMinutes",
            "accentColor",
            "isBuiltIn",
            "isArchived",
            "sortOrder",
            "createdAt",
            "updatedAt",
        )
        val carbs = payload.requireFiniteFloat("carbsGrams")
        val protein = payload.optionalFiniteFloat("proteinGrams")
        val fat = payload.optionalFiniteFloat("fatGrams")
        val absorption = payload.requireInt("absorptionMinutes")
        require(carbs >= 0f && (protein == null || protein >= 0f) && (fat == null || fat >= 0f)) {
            "Invalid Clone recovery food nutrition"
        }
        require(absorption in 1..MAX_DEPENDENCY_DURATION_MINUTES) {
            "Invalid Clone recovery food absorption"
        }
        return JournalFoodEntity(
            displayName = payload.requireString("displayName", MAX_DEPENDENCY_NAME_LENGTH),
            carbsGrams = carbs,
            proteinGrams = protein,
            fatGrams = fat,
            absorptionMinutes = absorption,
            accentColor = payload.requireInt("accentColor"),
            isBuiltIn = payload.requireBoolean("isBuiltIn"),
            isArchived = payload.requireBoolean("isArchived"),
            sortOrder = payload.requireInt("sortOrder"),
            createdAt = payload.requirePositiveLong("createdAt"),
            updatedAt = payload.requirePositiveLong("updatedAt"),
        )
    }

    private fun JSONObject.requireExactKeys(vararg expected: String) {
        val expectedSet = expected.toSet()
        require(length() == expectedSet.size) { "Invalid Clone journal recovery record fields" }
        val names = keys()
        while (names.hasNext()) {
            require(names.next() in expectedSet) { "Invalid Clone journal recovery record fields" }
        }
        expectedSet.forEach { name ->
            require(has(name)) { "Missing Clone journal recovery field" }
        }
    }

    private fun JSONObject.requireString(name: String, maximumLength: Int): String =
        requireStringAllowEmpty(name, maximumLength).also { value ->
            require(value.isNotBlank()) { "Invalid Clone journal recovery $name" }
        }

    private fun JSONObject.requireStringAllowEmpty(name: String, maximumLength: Int): String {
        require(has(name) && !isNull(name)) { "Missing Clone journal recovery $name" }
        val value = get(name) as? String
            ?: throw IllegalArgumentException("Invalid Clone journal recovery $name")
        require(value.length <= maximumLength && '\u0000' !in value) {
            "Invalid Clone journal recovery $name"
        }
        return value
    }

    private fun JSONObject.optionalString(name: String, maximumLength: Int): String? =
        optionalStringAllowEmpty(name, maximumLength)?.takeIf(String::isNotBlank)

    private fun JSONObject.optionalStringAllowEmpty(name: String, maximumLength: Int): String? {
        require(has(name)) { "Missing Clone journal recovery $name" }
        if (isNull(name)) return null
        return requireStringAllowEmpty(name, maximumLength)
    }

    private fun JSONObject.requirePositiveLong(name: String): Long =
        requireLong(name).also { value ->
            require(value > 0L) { "Invalid Clone journal recovery $name" }
        }

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing Clone journal recovery $name" }
        val number = get(name) as? Number
            ?: throw IllegalArgumentException("Invalid Clone journal recovery $name")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Invalid Clone journal recovery $name"
        }
        return value
    }

    private fun JSONObject.requireInt(name: String): Int {
        val value = requireLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Invalid Clone journal recovery $name"
        }
        return value.toInt()
    }

    private fun JSONObject.optionalInt(name: String): Int? {
        require(has(name)) { "Missing Clone journal recovery $name" }
        if (isNull(name)) return null
        return requireInt(name)
    }

    private fun JSONObject.requireFiniteFloat(name: String): Float {
        require(has(name) && !isNull(name)) { "Missing Clone journal recovery $name" }
        val number = get(name) as? Number
            ?: throw IllegalArgumentException("Invalid Clone journal recovery $name")
        return number.toFloat().also { value ->
            require(value.isFinite()) { "Invalid Clone journal recovery $name" }
        }
    }

    private fun JSONObject.optionalFiniteFloat(name: String): Float? {
        require(has(name)) { "Missing Clone journal recovery $name" }
        if (isNull(name)) return null
        return requireFiniteFloat(name)
    }

    private fun JSONObject.requireBoolean(name: String): Boolean {
        require(has(name) && !isNull(name)) { "Missing Clone journal recovery $name" }
        return get(name) as? Boolean
            ?: throw IllegalArgumentException("Invalid Clone journal recovery $name")
    }

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        require(has(name)) { "Missing Clone journal recovery $name" }
        if (isNull(name)) return null
        return get(name) as? JSONObject
            ?: throw IllegalArgumentException("Invalid Clone journal recovery $name")
    }

    private const val MAX_STABLE_ID_LENGTH = 512
    private const val RECOVERY_ID_LENGTH = 32
    private const val MAX_SENSOR_SERIAL_LENGTH = 256
    private const val MAX_ENUM_LENGTH = 64
    private const val MAX_TITLE_LENGTH = 4_096
    private const val MAX_NOTE_LENGTH = 65_536
    private const val MAX_REMOTE_ID_LENGTH = 512
    private const val MAX_DEPENDENCY_NAME_LENGTH = 512
    private const val MAX_DEPENDENCY_DURATION_MINUTES = 14 * 24 * 60
    private const val MAX_CURVE_LENGTH = 131_072
}
