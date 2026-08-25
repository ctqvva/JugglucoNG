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
        const val DEFAULT_PRESETS_SEEDED_KEY = "journal_default_presets_seeded_v5"
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
        val isInsulin = input.type == JournalEntryType.INSULIN
        val preserveCurveSnapshot = isInsulin &&
            existing?.entryType == JournalEntryType.INSULIN.storageValue &&
            existing.amount == input.amount &&
            existing.insulinPresetId == input.insulinPresetId &&
            !existing.insulinCurveJsonSnapshot.isNullOrBlank()
        val resolvedCurve = if (isInsulin && !preserveCurveSnapshot) {
            val amount = input.amount?.takeIf { it.isFinite() && it > 0f }
            val preset = input.insulinPresetId?.let { dao.getInsulinPresetById(it) }?.toModel()
            if (amount != null && preset != null) {
                preset.resolveCurveForDose(amount, JournalHumanProfile.bodyWeightKg(Applic.app))
            } else {
                null
            }
        } else {
            null
        }
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
            nsRemoteId = input.nsRemoteId?.takeIf { it.isNotBlank() } ?: existing?.nsRemoteId,
            insulinCurveJsonSnapshot = when {
                !isInsulin -> null
                preserveCurveSnapshot -> existing?.insulinCurveJsonSnapshot
                else -> resolvedCurve?.points?.let(::serializeJournalCurve)
            },
            insulinCurveProfileId = when {
                !isInsulin -> null
                preserveCurveSnapshot -> existing?.insulinCurveProfileId
                else -> resolvedCurve?.profileId
            },
            insulinCurveModelVersion = when {
                !isInsulin -> null
                preserveCurveSnapshot -> existing?.insulinCurveModelVersion
                else -> resolvedCurve?.modelVersion
            },
            insulinCurveEvidence = when {
                !isInsulin -> null
                preserveCurveSnapshot -> existing?.insulinCurveEvidence
                else -> resolvedCurve?.evidence?.storageValue
            },
            insulinBodyWeightKg = when {
                !isInsulin -> null
                preserveCurveSnapshot -> existing?.insulinBodyWeightKg
                else -> resolvedCurve?.usedBodyWeightKg
            },
            insulinCurveWasApproximated = when {
                !isInsulin -> false
                preserveCurveSnapshot -> existing?.insulinCurveWasApproximated ?: true
                else -> resolvedCurve?.approximated ?: true
            }
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
        val requestedProfile = input.curveProfileId?.let(JournalBuiltInCurveProfile::fromStorage)
        val requestedEvidence = requestedProfile
            ?.let { JournalInsulinCurveCatalogue.definition(it).evidence }
            ?: JournalCurveEvidence.UNVERIFIED
        val forceZeroEndpoints = requestedEvidence.requiresZeroEndpoints
        val normalizedCurveJson = input.curveJson
            .takeIf { it.isNotBlank() }
            ?.let(::parseJournalCurve)
            ?.takeIf { it.isNotEmpty() }
            ?.let { serializeJournalCurve(it, forceZeroEndpoints) }
            ?: serializeJournalCurve(defaultJournalCurve(input.onsetMinutes, input.durationMinutes))
        val matchesRequestedProfile = requestedProfile != null &&
            normalizedCurveJson == serializeJournalCurve(
                builtInJournalCurve(requestedProfile),
                forceZeroEndpoints
            )
        val storedProfile = requestedProfile?.takeIf { matchesRequestedProfile }
        val storedEvidence = if (storedProfile == null) {
            JournalCurveEvidence.UNVERIFIED
        } else {
            JournalInsulinCurveCatalogue.definition(storedProfile).evidence
        }
        val entity = JournalInsulinPresetEntity(
            id = existing?.id ?: (input.id ?: 0L),
            displayName = input.displayName.trim(),
            onsetMinutes = input.onsetMinutes.coerceAtLeast(0),
            durationMinutes = input.durationMinutes.coerceAtLeast(input.onsetMinutes.coerceAtLeast(0)),
            accentColor = input.accentColor,
            curveJson = normalizedCurveJson,
            isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
            isArchived = input.isArchived,
            countsTowardIob = input.countsTowardIob &&
                storedEvidence != JournalCurveEvidence.SOURCE_STEADY_STATE &&
                storedEvidence != JournalCurveEvidence.SOURCE_REFERENCE,
            sortOrder = input.sortOrder,
            useForCalculation = input.useForCalculation &&
                storedEvidence != JournalCurveEvidence.SOURCE_STEADY_STATE &&
                storedEvidence != JournalCurveEvidence.SOURCE_REFERENCE,
            curveProfileId = storedProfile?.storageValue,
            curveModelVersion = if (storedProfile == null) 0 else JournalInsulinCurveCatalogue.MODEL_VERSION,
            curveEvidence = storedEvidence.storageValue
        )
        val id = dao.upsertInsulinPreset(entity)
        // Entries snapshot their resolved curve, so edits affect future doses only.
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
        fun preset(
            nameRes: Int,
            profile: JournalBuiltInCurveProfile,
            color: Int,
            sortOrder: Int,
            archived: Boolean = true,
            countsTowardIob: Boolean = false
        ): JournalInsulinPresetEntity {
            val definition = JournalInsulinCurveCatalogue.definition(profile)
            val curve = definition.variants.minByOrNull { kotlin.math.abs(it.dose - definition.referenceDose) }
                ?.points
                ?: emptyList()
            val onset = curve.firstOrNull { it.activity > 0.01f }?.minute ?: 0
            val duration = curve.lastOrNull()?.minute ?: 0
            return JournalInsulinPresetEntity(
                displayName = app.getString(nameRes),
                onsetMinutes = onset,
                durationMinutes = duration,
                accentColor = color,
                curveJson = serializeJournalCurve(
                    curve,
                    forceZeroEndpoints = definition.evidence.requiresZeroEndpoints
                ),
                isBuiltIn = true,
                isArchived = archived,
                countsTowardIob = countsTowardIob,
                sortOrder = sortOrder,
                useForCalculation = countsTowardIob && definition.evidence.supportsPerDoseCalculation,
                curveProfileId = profile.storageValue,
                curveModelVersion = JournalInsulinCurveCatalogue.MODEL_VERSION,
                curveEvidence = definition.evidence.storageValue
            )
        }
        return listOf(
            preset(R.string.journal_preset_rapid_generic, JournalBuiltInCurveProfile.RAPID_GENERIC, 0xFF1565C0.toInt(), 0, archived = false, countsTowardIob = true),
            preset(R.string.journal_preset_long_generic, JournalBuiltInCurveProfile.LONG_BASAL_GENERIC, 0xFF6B7C3B.toInt(), 1, archived = false),
            preset(R.string.journal_preset_regular_u100, JournalBuiltInCurveProfile.HUMAN_REGULAR, 0xFF6A1B9A.toInt(), 2, countsTowardIob = true),
            preset(R.string.journal_preset_aspart, JournalBuiltInCurveProfile.ASPART, 0xFF1976D2.toInt(), 3, countsTowardIob = true),
            preset(R.string.journal_preset_lispro, JournalBuiltInCurveProfile.LISPRO, 0xFF00897B.toInt(), 4, countsTowardIob = true),
            preset(R.string.journal_preset_glulisine, JournalBuiltInCurveProfile.GLULISINE, 0xFF00838F.toInt(), 5, countsTowardIob = true),
            preset(R.string.journal_preset_fiasp, JournalBuiltInCurveProfile.FIASP, 0xFF2E7D32.toInt(), 6, countsTowardIob = true),
            preset(R.string.journal_preset_lyumjev, JournalBuiltInCurveProfile.URLI, 0xFF00695C.toInt(), 7, countsTowardIob = true),
            preset(R.string.journal_preset_afrezza, JournalBuiltInCurveProfile.AFREZZA, 0xFFEF6C00.toInt(), 8, countsTowardIob = true),
            preset(R.string.journal_preset_nph_named, JournalBuiltInCurveProfile.NPH, 0xFFE67E22.toInt(), 9),
            preset(R.string.journal_preset_ultra_long_generic, JournalBuiltInCurveProfile.ULTRA_LONG_BASAL, 0xFF3949AB.toInt(), 10),
            preset(R.string.journal_preset_lantus, JournalBuiltInCurveProfile.GLARGINE_U100, 0xFF5E6C3B.toInt(), 11),
            preset(R.string.journal_preset_toujeo, JournalBuiltInCurveProfile.GLARGINE_U300, 0xFF4E6041.toInt(), 12),
            preset(R.string.journal_preset_levemir, JournalBuiltInCurveProfile.DETEMIR, 0xFF7B6A3C.toInt(), 13),
            preset(R.string.journal_preset_tresiba, JournalBuiltInCurveProfile.DEGLUDEC, 0xFF3949AB.toInt(), 14),
            preset(R.string.journal_preset_awiqli, JournalBuiltInCurveProfile.ICODEC, 0xFF512DA8.toInt(), 15),
            preset(R.string.journal_preset_regular_u500, JournalBuiltInCurveProfile.HUMAN_REGULAR_U500, 0xFF8E244D.toInt(), 16),
            preset(R.string.journal_preset_ryzodeg, JournalBuiltInCurveProfile.RYZODEG_70_30, 0xFF6D4C41.toInt(), 17),
            preset(R.string.journal_preset_aspart_mix_7030, JournalBuiltInCurveProfile.ASPART_MIX_70_30, 0xFF795548.toInt(), 18),
            preset(R.string.journal_preset_lispro_mix_5050, JournalBuiltInCurveProfile.LISPRO_MIX_50_50, 0xFF8D6E63.toInt(), 19),
            preset(R.string.journal_preset_lispro_mix_7525, JournalBuiltInCurveProfile.LISPRO_MIX_75_25, 0xFFA1887F.toInt(), 20),
            preset(R.string.journal_preset_human_mix_7030, JournalBuiltInCurveProfile.HUMAN_MIX_70_30, 0xFF5D4037.toInt(), 21)
        )
    }

    private fun mergeBuiltInPresets(
        existing: List<JournalInsulinPresetEntity>
    ): List<JournalInsulinPresetEntity> {
        return defaultPresets().map { preset ->
            val existingMatch = matchExistingBuiltInPreset(preset, existing)
            existingMatch?.copy(
                displayName = preset.displayName,
                accentColor = preset.accentColor,
                sortOrder = preset.sortOrder
            ) ?: preset
        }
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

internal fun matchExistingBuiltInPreset(
    newPreset: JournalInsulinPresetEntity,
    existing: List<JournalInsulinPresetEntity>
): JournalInsulinPresetEntity? {
    val builtIns = existing.filter { it.isBuiltIn }
    builtIns.firstOrNull { it.displayName == newPreset.displayName }?.let { return it }
    val bySortOrder = builtIns.associateBy { it.sortOrder }
    if ((0..10).all(bySortOrder::containsKey) && newPreset.sortOrder <= 10) {
        return bySortOrder[newPreset.sortOrder]
    }
    val legacySortOrder = when (newPreset.sortOrder) {
        0 -> 1
        1 -> 4
        2 -> 2
        7 -> 0
        9 -> 3
        10 -> 5
        else -> null
    }
    return legacySortOrder?.let(bySortOrder::get)
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
        updatedAt = updatedAt,
        insulinCurveJsonSnapshot = insulinCurveJsonSnapshot,
        insulinCurveProfileId = insulinCurveProfileId,
        insulinCurveModelVersion = insulinCurveModelVersion,
        insulinCurveEvidence = insulinCurveEvidence?.let { JournalCurveEvidence.fromStorage(it) },
        insulinBodyWeightKg = insulinBodyWeightKg,
        insulinCurveWasApproximated = insulinCurveWasApproximated
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
        useForCalculation = useForCalculation,
        curveProfileId = curveProfileId,
        curveModelVersion = curveModelVersion,
        curveEvidence = JournalCurveEvidence.fromStorage(curveEvidence)
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
