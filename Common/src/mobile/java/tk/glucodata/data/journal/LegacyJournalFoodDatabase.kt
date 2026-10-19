package tk.glucodata.data.journal

import java.util.Locale
import kotlin.math.roundToInt
import tk.glucodata.Natives

object LegacyJournalFoodDatabase {
    private const val LEGACY_ID_OFFSET = 10_000_000L

    data class FoodComponentDetail(
        val label: String,
        val rawValue: Int,
        val unit: String
    )

    data class FoodDetails(
        val title: String,
        val components: List<FoodComponentDetail>
    )

    fun search(query: String, limit: Int = 8): List<JournalFood> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()

        val terms = needle
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .distinct()

        return runCatching {
            val merged = linkedMapOf<Int, JournalFood>()
            fun collect(nativeQuery: String) {
                searchNative(nativeQuery, limit * 2).forEach { food ->
                    merged[legacyStorageId(food.id)] = food
                }
            }

            collect(needle)
            terms.filterNot { it.equals(needle, ignoreCase = true) }.forEach(::collect)

            val ranked = merged.values.mapNotNull { food ->
                val name = food.displayName.lowercase(Locale.ROOT)
                val allTermsMatch = terms.all { name.contains(it) }
                val anyTermMatches = terms.any { name.contains(it) }
                if (!anyTermMatches) return@mapNotNull null
                val score = when {
                    name == needle.lowercase(Locale.ROOT) -> 0
                    name.startsWith(needle.lowercase(Locale.ROOT)) -> 1
                    allTermsMatch -> 2
                    else -> 3
                }
                RankedFood(food, score, allTermsMatch)
            }.sortedWith(
                compareBy<RankedFood> { it.score }
                    .thenBy { it.food.displayName.length }
                    .thenBy { it.food.displayName.lowercase(Locale.ROOT) }
            )
            val strict = ranked.filter { it.allTermsMatch }
            (strict.ifEmpty { ranked }).map { it.food }.take(limit)
        }.getOrDefault(emptyList())
    }

    fun details(food: JournalFood, showZero: Boolean = false): FoodDetails {
        val nativeId = legacyNativeId(food.id)
        if (nativeId != null) {
            val nativeDetails = runCatching {
                val components = Natives.getcomponents(nativeId)
                val labels = Natives.getcomponentlabels()
                val units = Natives.getcomponentunits()
                val rows = components.indices.mapNotNull { index ->
                    val value = components[index]
                    val shouldShow = if (showZero) value != -1 else value > 0
                    if (!shouldShow) return@mapNotNull null
                    val label = labels.getOrNull(index).orEmpty().ifBlank { return@mapNotNull null }
                    FoodComponentDetail(
                        label = label,
                        rawValue = value,
                        unit = units.getOrNull(index).orEmpty()
                    )
                }
                FoodDetails(title = food.displayName, components = rows)
            }.getOrNull()
            if (nativeDetails != null && nativeDetails.components.isNotEmpty()) {
                return nativeDetails
            }
        }

        val fallback = buildList {
            add(FoodComponentDetail("Carbohydrate", (food.carbsGrams * 1000f).roundToInt(), "g"))
            food.proteinGrams?.let { add(FoodComponentDetail("Protein", (it * 1000f).roundToInt(), "g")) }
            food.fatGrams?.let { add(FoodComponentDetail("Fat", (it * 1000f).roundToInt(), "g")) }
        }
        return FoodDetails(title = food.displayName, components = fallback)
    }

    private fun searchNative(query: String, limit: Int): List<JournalFood> {
        return runCatching {
            val ptr = Natives.foodsearch(query)
            if (ptr == 0L) return@runCatching emptyList()
            try {
                val count = Natives.foodhitnr(ptr).coerceAtMost(limit).coerceAtLeast(0)
                List(count) { index ->
                    val id = Natives.getfoodid(ptr, index)
                    val label = Natives.foodlabel(ptr, index).orEmpty()
                    legacyFoodToJournalFood(id, label)
                }.filterNotNull()
            } finally {
                Natives.freefoodptr(ptr)
            }
        }.getOrDefault(emptyList())
    }

    private fun legacyStorageId(journalId: Long): Int {
        return (kotlin.math.abs(journalId) - LEGACY_ID_OFFSET).toInt()
    }

    fun isLegacyFood(food: JournalFood): Boolean = legacyNativeId(food.id) != null

    private fun legacyNativeId(journalId: Long): Int? {
        if (journalId >= 0) return null
        val nativeId = (kotlin.math.abs(journalId) - LEGACY_ID_OFFSET).toInt()
        return nativeId.takeIf { it >= 0 }
    }

    private fun legacyFoodToJournalFood(id: Int, label: String): JournalFood? {
        if (id < 0 || label.isBlank()) return null
        val components = runCatching { Natives.getcomponents(id) }.getOrNull() ?: return null
        val indexes = componentIndexes()
        val carbs = components.componentGrams(indexes.carbs) ?: return null
        val protein = components.componentGrams(indexes.protein)
        val fat = components.componentGrams(indexes.fat)
        val absorption = estimateAbsorptionMinutes(carbs, protein, fat)
        return JournalFood(
            id = -(LEGACY_ID_OFFSET + id),
            displayName = label,
            carbsGrams = carbs,
            proteinGrams = protein,
            fatGrams = fat,
            absorptionMinutes = absorption,
            accentColor = foodAccentColor(carbs, protein, fat),
            isBuiltIn = true,
            isArchived = false,
            sortOrder = id
        )
    }

    private fun IntArray.componentGrams(index: Int?): Float? {
        if (index == null || index !in indices) return null
        val raw = this[index]
        if (raw < 0) return null
        return (raw / 1000f).takeIf { it.isFinite() && it >= 0f }
    }

    private fun estimateAbsorptionMinutes(carbs: Float, protein: Float?, fat: Float?): Int {
        val carbWindow = 45f + carbs.coerceAtLeast(0f) * 1.4f
        val macroWindow = journalFoodTailDurationMinutes(protein, fat)
        return maxOf(carbWindow, macroWindow).roundToInt().coerceIn(45, 480)
    }

    private fun foodAccentColor(carbs: Float, protein: Float?, fat: Float?): Int {
        val p = protein ?: 0f
        val f = fat ?: 0f
        return when {
            f >= 18f -> 0xFF8A6A3B.toInt()
            p >= 25f -> 0xFF6F7B4C.toInt()
            carbs <= 12f -> 0xFF4F7F63.toInt()
            else -> 0xFF5F8A58.toInt()
        }
    }

    private fun componentIndexes(): ComponentIndexes {
        val labels = runCatching { Natives.getcomponentlabels().toList() }.getOrDefault(emptyList())
        fun find(vararg needles: String): Int? {
            return labels.indexOfFirst { label ->
                val normalized = label.lowercase(Locale.ROOT)
                needles.any { normalized.contains(it) }
            }.takeIf { it >= 0 }
        }
        return ComponentIndexes(
            carbs = find("carbohydrate", "carb") ?: 0,
            protein = find("protein"),
            fat = find("fat", "lipid")
        )
    }

    private data class ComponentIndexes(
        val carbs: Int,
        val protein: Int?,
        val fat: Int?
    )

    private data class RankedFood(
        val food: JournalFood,
        val score: Int,
        val allTermsMatch: Boolean
    )
}
