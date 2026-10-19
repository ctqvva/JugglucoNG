package tk.glucodata.data.journal

private const val PROTEIN_KCAL_PER_GRAM = 4f
private const val FAT_KCAL_PER_GRAM = 9f
private const val FAT_PROTEIN_UNIT_KCAL = 100f
private const val CARBS_PER_FAT_PROTEIN_UNIT = 10f

fun journalFoodFatProteinUnits(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val protein = proteinGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val fat = fatGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return ((protein * PROTEIN_KCAL_PER_GRAM) + (fat * FAT_KCAL_PER_GRAM)) / FAT_PROTEIN_UNIT_KCAL
}

fun journalFoodTailEquivalentCarbs(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val units = journalFoodFatProteinUnits(proteinGrams, fatGrams)
    if (units <= 0f) return 0f

    // Fat/protein units model delayed glucose impact; keep it conservative because this is a
    // decision-support curve, not an automatic insulin command.
    val delayedFraction = when {
        units < 1f -> 0.30f
        units < 2f -> 0.42f
        units < 4f -> 0.55f
        else -> 0.65f
    }
    return (units * CARBS_PER_FAT_PROTEIN_UNIT * delayedFraction).coerceIn(0f, 80f)
}

fun journalFoodDoseCarbs(
    carbsGrams: Float?,
    proteinGrams: Float?,
    fatGrams: Float?,
    macrosEnabled: Boolean
): Float? {
    val carbs = carbsGrams?.takeIf { it.isFinite() && it > 0f } ?: return null
    return if (macrosEnabled) {
        carbs + journalFoodTailEquivalentCarbs(proteinGrams, fatGrams)
    } else {
        carbs
    }
}

fun journalFoodTailDelayMinutes(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val protein = proteinGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val fat = fatGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val units = journalFoodFatProteinUnits(protein, fat)
    return when {
        fat >= 30f || units >= 4f -> 110f
        fat >= 15f || protein >= 25f || units >= 2f -> 80f
        else -> 50f
    }
}

fun journalFoodTailDurationMinutes(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val fat = fatGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val units = journalFoodFatProteinUnits(proteinGrams, fat)
    return (110f + units * 55f + fat * 3.5f).coerceIn(90f, 480f)
}
