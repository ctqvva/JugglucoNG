package tk.glucodata.ui

import java.text.Normalizer

/**
 * Ranks settings against a typed query. Pure Kotlin so it can be tested without resources.
 *
 * Every word of the query has to appear somewhere in the entry (title, where it lives, or
 * its extra search terms), ignoring case and accents, so "notif arrow" finds "Show trend
 * arrow" under "Glucose notification". The order is by how the entry matched: its title
 * (from the start, at a word's start, anywhere), then another of its own names (an old
 * name, an abbreviation), then only what is around it: the place it lives in, or the words
 * describing it. Within that, a place (a screen
 * or dialog, [Candidate.level] 0) comes before what is inside one, then index order, which
 * is the order rows appear on screen.
 */
internal object SettingsSearchMatcher {

    class Candidate<T>(
        val item: T,
        val title: String,
        /** Where the setting lives, as the result shows it. Matched, weighted below the title. */
        val location: String,
        /** Other names of the setting itself (an abbreviation, a product, an old name). */
        val terms: List<String> = emptyList(),
        /** Words about it: its description, what it holds. They find it, but only as nearby. */
        val descriptions: List<String> = emptyList(),
        /** 0 for a place of its own (screen, dialog); 1 for a setting inside one. */
        val level: Int = 0,
    ) {
        internal val normalizedTitle = normalize(title)
        internal val normalizedLocation = normalize(location)
        internal val normalizedTerms = terms.map { normalize(it) }
        internal val extras = terms + descriptions
        internal val normalizedExtras = normalizedTerms + descriptions.map { normalize(it) }
    }

    /**
     * One hit. [via] is the term that brought it in when the title and location alone do
     * not hold every word of the query: the result shows it, so a match is never a mystery.
     * [direct] is false for an entry found only because of where it lives (everything inside
     * "Journal" for "journal"): it is not what was asked for, only near it.
     */
    class Match<T>(val item: T, val via: String?, val direct: Boolean)

    fun <T> search(query: String, candidates: List<Candidate<T>>): List<T> =
        matches(query, candidates).map { it.item }

    fun <T> matches(query: String, candidates: List<Candidate<T>>): List<Match<T>> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val phrase = tokens.joinToString(" ")
        return candidates
            .mapIndexedNotNull { index, candidate ->
                val shown = candidate.normalizedTitle + " " + candidate.normalizedLocation
                val missing = tokens.filterNot { shown.contains(it) }
                val via = if (missing.isEmpty()) {
                    null
                } else {
                    // Every missing word has to be in a term or description; name the one that
                    // holds most, a term (another name) before a description on a tie.
                    val extras = candidate.normalizedExtras
                    if (!missing.all { token -> extras.any { it.contains(token) } }) {
                        return@mapIndexedNotNull null
                    }
                    candidate.extras[extras.indices.maxBy { i -> missing.count { extras[i].contains(it) } }]
                }
                val rank = rank(candidate, phrase, tokens)
                Ranked(Match(candidate.item, via, direct = rank < LOCATION_ONLY), rank, candidate.level, index)
            }
            .sortedWith(compareBy({ it.rank }, { it.level }, { it.index }))
            .map { it.match }
    }

    private class Ranked<T>(val match: Match<T>, val rank: Int, val level: Int, val index: Int)

    private const val LOCATION_ONLY = 4

    private fun rank(candidate: Candidate<*>, phrase: String, tokens: List<String>): Int {
        val title = candidate.normalizedTitle
        val words = title.split(' ')
        return when {
            title.startsWith(phrase) -> 0
            tokens.all { token -> words.any { it.startsWith(token) } } -> 1
            tokens.all { title.contains(it) } -> 2
            tokens.all { token -> title.contains(token) || candidate.normalizedTerms.any { it.contains(token) } } -> 3
            else -> LOCATION_ONLY
        }
    }

    internal fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Where the words of [query] sit in [text], as index ranges into [text] itself, so the
     * result can mark them. Each word marks its first place in the text, preferring the
     * start of a word ("arr" marks "arrow", not "narrow"). Overlaps are merged.
     */
    fun highlights(text: String, query: String): List<IntRange> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val (normalized, origin) = normalizeMapped(text)
        val ranges = tokens.mapNotNull { token ->
            var at = -1
            var from = normalized.indexOf(token)
            while (from >= 0) {
                if (at < 0) at = from
                if (from == 0 || normalized[from - 1] == ' ') {
                    at = from
                    break
                }
                from = normalized.indexOf(token, from + 1)
            }
            if (at < 0) null else origin[at]..origin[at + token.length - 1]
        }.sortedBy { it.first }
        val merged = ArrayList<IntRange>(ranges.size)
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * Lower case, accents dropped, and every run of non-letters (spaces, punctuation,
     * "·", "›", "+") collapsed to one space. Turkish dotless "ı" folds to "i" as well:
     * "BAĞLANTI" lowercases to a dotted "i" whatever the locale, so the two would never meet.
     * Written as a loop rather than a regex: Android's ICU patterns reject constructs the
     * JVM accepts (IcuRegexCompatibilityTests).
     */
    internal fun normalize(text: String): String = normalizeMapped(text).first

    /**
     * [normalize], plus for every character of the result the index in [text] it came from.
     * Each source character is decomposed on its own: a precomposed "é" and an "e" followed
     * by a combining accent both come out as "e", as they do for the whole string at once.
     */
    private fun normalizeMapped(text: String): Pair<String, IntArray> {
        val out = StringBuilder(text.length)
        var origin = IntArray(text.length + 8)
        var separated = false
        fun append(ch: Char, index: Int) {
            // Some characters decompose into several letters (Hangul syllables into jamo).
            if (out.length == origin.size) origin = origin.copyOf(origin.size * 2)
            origin[out.length] = index
            out.append(ch)
        }
        for ((index, source) in text.withIndex()) {
            val decomposed = if (source.code < 0x80) source.toString() else Normalizer.normalize(source.toString(), Normalizer.Form.NFD)
            for (ch in decomposed) {
                when {
                    Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                    ch.isLetterOrDigit() -> {
                        if (separated && out.isNotEmpty()) append(' ', index)
                        separated = false
                        append(if (ch == 'ı') 'i' else ch.lowercaseChar(), index)
                    }
                    else -> separated = true
                }
            }
        }
        return out.toString() to origin.copyOf(out.length)
    }
}
