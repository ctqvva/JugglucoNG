package tk.glucodata.data.journal

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.HistoryDatabase

class JournalRepository {
    private companion object {
        const val PREFS_NAME = "tk.glucodata_preferences"
        const val DEFAULT_PRESETS_SEEDED_KEY = "journal_default_presets_seeded_v4"
        const val DEFAULT_FOODS_SEEDED_KEY = "journal_default_foods_seeded_v2"
    }

    private val database = HistoryDatabase.getInstance(Applic.app)
    private val dao = database.journalDao()
    private val prefs = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeEntries(): Flow<List<JournalEntry>> {
        return dao.observeEntries().map { entries -> entries.map(JournalEntryEntity::toModel) }
    }

    fun observeInsulinPresets(): Flow<List<JournalInsulinPreset>> {
        return dao.observeInsulinPresets().map { presets -> presets.map(JournalInsulinPresetEntity::toModel) }
    }

    fun observeFoods(): Flow<List<JournalFood>> {
        return dao.observeFoods().map { foods -> foods.map(JournalFoodEntity::toModel) }
    }

    suspend fun ensureDefaultInsulinPresets() {
        if (prefs.getBoolean(DEFAULT_PRESETS_SEEDED_KEY, false)) return
        database.withTransaction {
            val existing = dao.getInsulinPresets()
            if (existing.isEmpty()) {
                dao.insertInsulinPresets(defaultPresets())
            } else {
                dao.insertInsulinPresets(mergeBuiltInPresets(existing))
            }
            prefs.edit().putBoolean(DEFAULT_PRESETS_SEEDED_KEY, true).apply()
        }
    }

    suspend fun upsertEntry(input: JournalEntryInput): Long {
        val sourceRecordId = input.sourceRecordId?.takeIf { it.isNotBlank() }
        val existing = input.id?.let { dao.getEntryById(it) }
            ?: sourceRecordId?.let { dao.getEntryBySourceRecordId(it) }
        val now = System.currentTimeMillis()
        val entity = JournalEntryEntity(
            id = existing?.id ?: (input.id ?: 0L),
            timestamp = input.timestamp,
            sensorSerial = input.sensorSerial?.takeIf { it.isNotBlank() },
            entryType = input.type.storageValue,
            title = input.title.trim(),
            note = input.note?.trim()?.takeIf { it.isNotBlank() },
            amount = input.amount,
            glucoseValueMgDl = input.glucoseValueMgDl,
            durationMinutes = input.durationMinutes,
            intensity = input.intensity?.storageValue,
            insulinPresetId = input.insulinPresetId,
            source = input.source.storageValue,
            sourceRecordId = sourceRecordId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            foodId = input.foodId,
            proteinGrams = input.proteinGrams?.coerceAtLeast(0f),
            fatGrams = input.fatGrams?.coerceAtLeast(0f),
            nsUploadedAt = existing?.nsUploadedAt,
            nsRemoteId = input.nsRemoteId?.takeIf { it.isNotBlank() } ?: existing?.nsRemoteId
        )
        val id = dao.upsertEntry(entity)
        if (affectsIob(entity.entryType) || affectsIob(existing?.entryType)) {
            tk.glucodata.OutboundApiJournalSnapshot.journalChanged()
        }
        if (entity.glucoseValueMgDl != null || existing?.glucoseValueMgDl != null) {
            tk.glucodata.data.calibration.JournalCalibrationSync.onJournalChanged()
        }
        return id
    }

    /** Lets an importer skip a record it already wrote instead of overwriting later edits. */
    suspend fun hasEntryWithSourceRecordId(sourceRecordId: String): Boolean {
        val id = sourceRecordId.trim().takeIf { it.isNotBlank() } ?: return false
        return dao.getEntryBySourceRecordId(id) != null
    }

    /** What one importer has already written in a stretch of time, so it can reconcile. */
    suspend fun entriesFromSourceBetween(
        source: JournalEntrySource,
        startMillis: Long,
        endMillis: Long
    ): List<JournalEntry> {
        return dao.getEntriesBySourceBetween(source.storageValue, startMillis, endMillis)
            .map(JournalEntryEntity::toModel)
    }

    /**
     * Renames an entry an importer can now identify more stably, leaving everything else
     * alone — the point is to keep the row, including edits made to it, not to rewrite it.
     *
     * @return false when the entry is gone or the name is already taken
     */
    suspend fun retagSourceRecordId(entryId: Long, sourceRecordId: String): Boolean {
        val id = sourceRecordId.trim().takeIf { it.isNotBlank() } ?: return false
        return database.withTransaction {
            val existing = dao.getEntryById(entryId) ?: return@withTransaction false
            if (existing.sourceRecordId == id) return@withTransaction true
            if (dao.getEntryBySourceRecordId(id) != null) return@withTransaction false
            dao.upsertEntry(existing.copy(sourceRecordId = id, updatedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun deleteEntriesBySourceRecordIds(sourceRecordIds: List<String>) {
        val ids = sourceRecordIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (ids.isNotEmpty()) {
            dao.deleteEntriesBySourceRecordIds(ids)
            tk.glucodata.OutboundApiJournalSnapshot.journalChanged()
            tk.glucodata.data.calibration.JournalCalibrationSync.onJournalChanged()
        }
    }

    suspend fun deleteEntry(entryId: Long) {
        var deletedType: String? = null
        var deletedGlucose: Float? = null
        database.withTransaction {
            val existing = dao.getEntryById(entryId)
            deletedType = existing?.entryType
            deletedGlucose = existing?.glucoseValueMgDl
            val remoteId = existing?.nightscoutDeleteRemoteId()
            if (remoteId != null) {
                dao.enqueuePendingNightscoutDelete(
                    JournalPendingDeleteEntity(
                        entryId = entryId,
                        nsRemoteId = remoteId,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
            dao.deleteEntryById(entryId)
        }
        if (affectsIob(deletedType)) {
            tk.glucodata.OutboundApiJournalSnapshot.journalChanged()
        }
        if (deletedGlucose != null) {
            tk.glucodata.data.calibration.JournalCalibrationSync.onJournalChanged()
        }
    }

    suspend fun upsertInsulinPreset(input: JournalInsulinPresetInput): Long {
        val existing = input.id?.let { dao.getInsulinPresetById(it) }
        val entity = JournalInsulinPresetEntity(
            id = existing?.id ?: (input.id ?: 0L),
            displayName = input.displayName.trim(),
            onsetMinutes = input.onsetMinutes.coerceAtLeast(0),
            durationMinutes = input.durationMinutes.coerceAtLeast(input.onsetMinutes.coerceAtLeast(0)),
            accentColor = input.accentColor,
            curveJson = input.curveJson.ifBlank {
                serializeJournalCurve(defaultJournalCurve(input.onsetMinutes, input.durationMinutes))
            },
            isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
            isArchived = input.isArchived,
            countsTowardIob = input.countsTowardIob,
            sortOrder = input.sortOrder,
            useForCalculation = input.useForCalculation
        )
        val id = dao.upsertInsulinPreset(entity)
        // Curve or countsTowardIob edits reshape the IOB of existing doses.
        // Calculation eligibility is persisted alongside them for journal dose assist.
        tk.glucodata.OutboundApiJournalSnapshot.journalChanged()
        return id
    }

    suspend fun deleteInsulinPreset(presetId: Long) {
        dao.deleteInsulinPresetById(presetId)
        tk.glucodata.OutboundApiJournalSnapshot.journalChanged()
    }

    private fun affectsIob(entryType: String?): Boolean {
        return entryType == JournalEntryType.INSULIN.storageValue ||
            entryType == JournalEntryType.CARBS.storageValue
    }

    suspend fun getInsulinPresetsSnapshot(): List<JournalInsulinPreset> {
        return dao.getInsulinPresets().map(JournalInsulinPresetEntity::toModel)
    }

    /** Entries carrying a blood-glucose value, oldest first. */
    suspend fun getGlucoseEntriesSince(startMillis: Long): List<JournalEntry> {
        return dao.getGlucoseEntriesSince(startMillis).map(JournalEntryEntity::toModel)
    }

    suspend fun getEntriesBetweenSnapshot(startMillis: Long, endMillis: Long): List<JournalEntry> {
        return dao.getEntriesBetween(startMillis, endMillis).map(JournalEntryEntity::toModel)
    }

    suspend fun ensureDefaultFoods() {
        if (prefs.getBoolean(DEFAULT_FOODS_SEEDED_KEY, false)) return
        database.withTransaction {
            val existing = dao.getFoods()
            if (existing.isEmpty()) {
                dao.insertFoods(defaultFoods())
            } else {
                val builtIns = existing.filter { it.isBuiltIn }
                val missingDefaults = defaultFoods().filter { preset ->
                    builtIns.none { existingFood ->
                        existingFood.sortOrder == preset.sortOrder ||
                            existingFood.displayName.equals(preset.displayName, ignoreCase = true)
                    }
                }
                if (missingDefaults.isNotEmpty()) {
                    dao.insertFoods(missingDefaults)
                }
            }
            prefs.edit().putBoolean(DEFAULT_FOODS_SEEDED_KEY, true).apply()
        }
    }

    suspend fun upsertFood(input: JournalFoodInput): Long {
        val existing = input.id?.let { dao.getFoodById(it) }
        val now = System.currentTimeMillis()
        val entity = JournalFoodEntity(
            id = existing?.id ?: (input.id ?: 0L),
            displayName = input.displayName.trim(),
            carbsGrams = input.carbsGrams.coerceAtLeast(0f),
            proteinGrams = input.proteinGrams?.coerceAtLeast(0f),
            fatGrams = input.fatGrams?.coerceAtLeast(0f),
            absorptionMinutes = input.absorptionMinutes.coerceIn(15, 480),
            accentColor = input.accentColor,
            isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
            isArchived = input.isArchived,
            sortOrder = input.sortOrder,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        return dao.upsertFood(entity)
    }

    suspend fun deleteFood(foodId: Long) {
        dao.deleteFoodById(foodId)
    }

    private fun defaultPresets(): List<JournalInsulinPresetEntity> {
        val app = Applic.app
        return listOf(
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.rapidinsulin),
                onsetMinutes = 14,
                durationMinutes = 360,
                accentColor = 0xFF1565C0.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.RAPID_GENERIC)),
                isBuiltIn = true,
                isArchived = false,
                countsTowardIob = true,
                sortOrder = 0
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.longinsulin),
                onsetMinutes = 90,
                durationMinutes = 1440,
                accentColor = 0xFF6B7C3B.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.LONG_BASAL_GENERIC)),
                isBuiltIn = true,
                isArchived = false,
                countsTowardIob = false,
                sortOrder = 1,
                useForCalculation = false
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.humaninsulin),
                onsetMinutes = 28,
                durationMinutes = 510,
                accentColor = 0xFF6A1B9A.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.HUMAN_REGULAR)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 2
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.aspart),
                onsetMinutes = 14,
                durationMinutes = 360,
                accentColor = 0xFF1976D2.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.ASPART)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 3
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.lispro),
                onsetMinutes = 12,
                durationMinutes = 330,
                accentColor = 0xFF00897B.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.LISPRO)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 4
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.glulisine),
                onsetMinutes = 15,
                durationMinutes = 480,
                accentColor = 0xFF00838F.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.GLULISINE)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 5
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.fiasp),
                onsetMinutes = 10,
                durationMinutes = 360,
                accentColor = 0xFF2E7D32.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.FIASP)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 6
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.urli),
                onsetMinutes = 15,
                durationMinutes = 370,
                accentColor = 0xFF00695C.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.URLI)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 7
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.afrezza),
                onsetMinutes = 12,
                durationMinutes = 210,
                accentColor = 0xFFEF6C00.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.AFREZZA)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 8
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.journal_preset_nph),
                onsetMinutes = 90,
                durationMinutes = 720,
                accentColor = 0xFFE67E22.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.NPH)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 9
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.journal_preset_ultra_long_basal),
                onsetMinutes = 180,
                durationMinutes = 2160,
                accentColor = 0xFF3949AB.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.ULTRA_LONG_BASAL)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = false,
                sortOrder = 10,
                useForCalculation = false
            )
        )
    }

    private fun mergeBuiltInPresets(
        existing: List<JournalInsulinPresetEntity>
    ): List<JournalInsulinPresetEntity> {
        val builtInsByName = existing.filter { it.isBuiltIn }.associateBy { it.displayName }
        val builtInsBySortOrder = existing.filter { it.isBuiltIn }.associateBy { it.sortOrder }
        return defaultPresets().map { preset ->
            val existingMatch = builtInsByName[preset.displayName]
                ?: legacyBuiltInMatch(preset.sortOrder, builtInsBySortOrder)
            existingMatch?.copy(
                displayName = preset.displayName,
                onsetMinutes = preset.onsetMinutes,
                durationMinutes = preset.durationMinutes,
                accentColor = preset.accentColor,
                curveJson = preset.curveJson,
                isArchived = preset.isArchived,
                countsTowardIob = preset.countsTowardIob,
                sortOrder = preset.sortOrder,
                useForCalculation = preset.useForCalculation
            ) ?: preset
        }
    }

    private fun legacyBuiltInMatch(
        newSortOrder: Int,
        builtInsBySortOrder: Map<Int, JournalInsulinPresetEntity>
    ): JournalInsulinPresetEntity? {
        val legacySortOrder = when (newSortOrder) {
            0 -> 1
            1 -> 4
            2 -> 2
            7 -> 0
            9 -> 3
            10 -> 5
            else -> null
        } ?: return null
        return builtInsBySortOrder[legacySortOrder]
    }

    private fun defaultFoods(): List<JournalFoodEntity> {
        val app = Applic.app
        val now = System.currentTimeMillis()
        fun food(
            nameRes: Int,
            carbs: Float,
            protein: Float,
            fat: Float,
            minutes: Int,
            color: Int,
            sortOrder: Int,
            archived: Boolean = false
        ) = JournalFoodEntity(
            displayName = app.getString(nameRes),
            carbsGrams = carbs,
            proteinGrams = protein,
            fatGrams = fat,
            absorptionMinutes = minutes,
            accentColor = color,
            isBuiltIn = true,
            isArchived = archived,
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now
        )
        return listOf(
            food(R.string.journal_food_fast_carbs, 15f, 0f, 0f, 30, 0xFF4F7C58.toInt(), 0),
            food(R.string.journal_food_glucose_tabs, 15f, 0f, 0f, 25, 0xFF5B8A61.toInt(), 1),
            food(R.string.journal_food_balanced_meal, 45f, 20f, 15f, 150, 0xFF5F7D4B.toInt(), 2),
            food(R.string.journal_food_fruit_yogurt, 30f, 8f, 3f, 95, 0xFF4F7F6B.toInt(), 3),
            food(R.string.journal_food_oats_cereal, 45f, 12f, 8f, 150, 0xFF6F7E4C.toInt(), 4),
            food(R.string.journal_food_rice_pasta, 70f, 15f, 8f, 180, 0xFF7D6F46.toInt(), 5),
            food(R.string.journal_food_slow_meal, 60f, 25f, 25f, 270, 0xFF8A7347.toInt(), 6),
            food(R.string.journal_food_pizza, 60f, 25f, 30f, 330, 0xFF8A5F3F.toInt(), 7),
            food(R.string.journal_food_burger_fries, 75f, 30f, 35f, 360, 0xFF8A5838.toInt(), 8),
            food(R.string.journal_food_low_carb_plate, 8f, 45f, 25f, 300, 0xFF55705D.toInt(), 9),
            food(R.string.journal_food_dessert, 35f, 6f, 18f, 210, 0xFF7C5F72.toInt(), 10),
            food(R.string.journal_food_protein_snack, 10f, 25f, 8f, 180, 0xFF6F6650.toInt(), 11)
        )
    }
}

private fun JournalEntryEntity.nightscoutDeleteRemoteId(): String? {
    nsRemoteId?.takeIf { it.isNotBlank() }?.let { return it }
    if (source != JournalEntrySource.NIGHTSCOUT.storageValue) return null
    val parts = sourceRecordId?.split(":") ?: return null
    if (parts.size != 4 || parts[0] != "nightscout") return null
    val baseId = parts[2].takeIf { it.isNotBlank() } ?: return null
    return baseId.takeUnless { it.startsWith("hash", ignoreCase = true) }
}

private fun JournalEntryEntity.toModel(): JournalEntry {
    return JournalEntry(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        type = JournalEntryType.fromStorage(entryType),
        title = title,
        note = note,
        amount = amount,
        glucoseValueMgDl = glucoseValueMgDl,
        durationMinutes = durationMinutes,
        intensity = JournalIntensity.fromStorage(intensity),
        insulinPresetId = insulinPresetId,
        foodId = foodId,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        source = JournalEntrySource.fromStorage(source),
        sourceRecordId = sourceRecordId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun JournalInsulinPresetEntity.toModel(): JournalInsulinPreset {
    return JournalInsulinPreset(
        id = id,
        displayName = displayName,
        onsetMinutes = onsetMinutes,
        durationMinutes = durationMinutes,
        accentColor = accentColor,
        curveJson = curveJson,
        isBuiltIn = isBuiltIn,
        isArchived = isArchived,
        countsTowardIob = countsTowardIob,
        sortOrder = sortOrder,
        useForCalculation = useForCalculation
    )
}

private fun JournalFoodEntity.toModel(): JournalFood {
    return JournalFood(
        id = id,
        displayName = displayName,
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        absorptionMinutes = absorptionMinutes,
        accentColor = accentColor,
        isBuiltIn = isBuiltIn,
        isArchived = isArchived,
        sortOrder = sortOrder
    )
}
