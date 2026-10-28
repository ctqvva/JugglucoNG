package tk.glucodata.ui

import java.text.Normalizer

/**
 * Ranks settings against a typed query. Pure Kotlin so it can be tested without resources.
 *
 * Every word of the query has to appear somewhere in the entry (title, where it lives, or
 * its extra search terms), ignoring case and accents, so "notif arrow" finds "Show trend
 * arrow" under "Glucose notification". Results whose title carries the match come
 * before results that only match on location or terms; ties keep index order, which is the
 * order rows appear on screen.
 */
internal object SettingsSearchMatcher {

    class Candidate<T>(
        val item: T,
        val title: String,
        /** Breadcrumb and extra terms: matched, but weighted below the title. */
        val context: List<String>,
    ) {
        internal val normalizedTitle = normalize(title)
        internal val normalizedContext = context.joinToString(" ") { normalize(it) }
    }

    fun <T> search(query: String, candidates: List<Candidate<T>>): List<T> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val phrase = tokens.joinToString(" ")
        return candidates
            .mapIndexedNotNull { index, candidate ->
                val haystack = candidate.normalizedTitle + " " + candidate.normalizedContext
                if (tokens.all { haystack.contains(it) }) {
                    Triple(candidate.item, rank(candidate.normalizedTitle, phrase, tokens), index)
                } else {
                    null
                }
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    private fun rank(title: String, phrase: String, tokens: List<String>): Int {
        val words = title.split(' ')
        return when {
            title.startsWith(phrase) -> 0
            tokens.all { token -> words.any { it.startsWith(token) } } -> 1
            tokens.all { title.contains(it) } -> 2
            else -> 3
        }
    }

    internal fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Lower case, accents dropped, and every run of non-letters (spaces, punctuation,
     * "·", "›", "+") collapsed to one space. Turkish dotless "ı" folds to "i" as well:
     * "BAĞLANTI" lowercases to a dotted "i" whatever the locale, so the two would never meet.
     * Written as a loop rather than a regex: Android's ICU patterns reject constructs the
     * JVM accepts (IcuRegexCompatibilityTests).
     */
    internal fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var separated = false
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                ch.isLetterOrDigit() -> {
                    if (separated && out.isNotEmpty()) out.append(' ')
                    separated = false
                    out.append(if (ch == 'ı') 'i' else ch.lowercaseChar())
                }
                else -> separated = true
            }
        }
        return out.toString()
    }
}
