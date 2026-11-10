package tk.glucodata.data.food

import java.text.Normalizer
import java.util.Locale

/**
 * Matching typed words against food names, the same way on every shelf.
 *
 * A name matches when every typed word starts one of its words ("гре" finds "Гречка отварная",
 * "boil pot" finds "Potato, boiled"), with accents and letter case ignored. Words found only
 * in the middle of a name word still match, but rank below.
 */
object FoodSearch {
    private val combiningMarks = Regex("[\\p{Mn}]+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, without accents, with ё and й-like pairs folded the way people type them. */
    fun fold(text: String): String {
        val lowered = text.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace('ı', 'i')
            .replace('ł', 'l')
            .replace('ø', 'o')
            .replace("ß", "ss")
            .replace("æ", "ae")
        // й and ї are letters of their own, not accented и and і: keep them through the fold.
        val shielded = lowered.replace('й', '\u0001').replace('ї', '\u0002')
        val stripped = combiningMarks.replace(Normalizer.normalize(shielded, Normalizer.Form.NFD), "")
        return stripped.replace('\u0001', 'й').replace('\u0002', 'ї')
    }

    fun words(text: String): List<String> = fold(text).split(separators).filter { it.isNotEmpty() }

    /** A name prepared once, so a keystroke does not fold thousands of names again. */
    class IndexedName(val name: String) {
        val folded: String = fold(name)
        val words: List<String> = folded.split(separators).filter { it.isNotEmpty() }
    }

    /**
     * How well [name] answers [queryWords]; lower is better, null is no match.
     * 0 the whole name, 1 the name starts with the query, 2 every word starts a name word,
     * 3 some word is found only inside a name word.
     */
    fun rank(name: IndexedName, queryWords: List<String>, foldedQuery: String): Int? {
        if (queryWords.isEmpty()) return null
        var inside = false
        for (word in queryWords) {
            when {
                name.words.any { it.startsWith(word) } -> Unit
                name.folded.contains(word) -> inside = true
                else -> return null
            }
        }
        return when {
            name.words.joinToString(" ") == foldedQuery -> 0
            name.words.joinToString(" ").startsWith(foldedQuery) -> 1
            !inside -> 2
            else -> 3
        }
    }

    /** The best [limit] of [items] for [query], best first; shorter names win a tie. */
    fun <T> search(
        items: List<T>,
        query: String,
        limit: Int,
        preference: (T) -> Int = { 0 },
        name: (T) -> IndexedName
    ): List<T> {
        val queryWords = words(query)
        if (queryWords.isEmpty()) return emptyList()
        val foldedQuery = queryWords.joinToString(" ")
        return items.asSequence()
            .mapNotNull { item ->
                val indexed = name(item)
                rank(indexed, queryWords, foldedQuery)?.let { Triple(item, it, indexed) }
            }
            .sortedWith(
                compareBy<Triple<T, Int, IndexedName>> { it.second }
                    .thenByDescending { preference(it.first) }
                    .thenBy { it.third.folded.length }
                    .thenBy { it.third.folded }
            )
            .take(limit)
            .map { it.first }
            .toList()
    }
}
