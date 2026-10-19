package tk.glucodata

import androidx.annotation.Keep
import org.json.JSONObject
import java.util.Locale

/**
 * Per-insulin-type template tokens for API destinations.
 *
 * `{iob}` cannot tell a basal shot from a bolus, so a Telegram chat watching a
 * template had no way to say *which* insulin was just logged. Every insulin
 * type the journal has active gets its own token family instead, named after
 * the type itself — the stock English presets yield `{rapid_acting_insulin}`
 * and `{long_acting_insulin}` — so a template can read
 * "Long acting: {long_acting_insulin} U at {long_acting_insulin_time}".
 *
 * The slug is derived from the display name in whatever script it is written
 * in (a Russian install gets `{инсулин_длительного_действия}`); the settings
 * screen lists the live tokens as chips so nobody has to guess the spelling.
 *
 * The values come from the journal snapshot JSON, which is the only channel
 * src/main has into the mobile-only journal.
 */
@Keep
object OutboundApiInsulinTokens {

    /** Array key inside the journal snapshot JSON. */
    const val JSON_ARRAY = "insulin_types"

    private const val KEY_SLUG = "slug"
    private const val KEY_NAME = "name"
    private const val KEY_IOB = "iob"
    private const val KEY_LAST_UNITS = "last_units"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_TODAY_UNITS = "today_units"

    /** Used when a display name has no letters or digits to slug at all. */
    private const val FALLBACK_SLUG = "insulin"

    /**
     * Suffixes appended to a type's slug, in the order the settings screen
     * offers them. The bare slug (no suffix) is the last dose in units.
     */
    private val SUFFIXES = listOf("", "_time", "_ago", "_iob", "_today")

    /** One insulin type as the template engine sees it. */
    data class TypeSnapshot(
        val slug: String,
        val displayName: String,
        val lastUnits: Float,
        val lastTimestampMs: Long,
        val iobUnits: Float,
        val todayUnits: Float
    )

    /**
     * Slug for one display name: letters and digits survive lowercased, every
     * other run collapses into a single underscore. Works for any script, so
     * localized preset names keep a usable token.
     */
    fun slugFor(displayName: String): String {
        val slug = StringBuilder()
        var pendingSeparator = false
        displayName.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) {
                if (pendingSeparator && slug.isNotEmpty()) slug.append('_')
                pendingSeparator = false
                slug.append(ch)
            } else {
                pendingSeparator = true
            }
        }
        return slug.toString()
    }

    /**
     * Slugs for a list of display names, in order, with collisions (two types
     * named alike, or two names that slug the same) broken by a numeric suffix
     * so every type keeps a token of its own.
     */
    fun assignSlugs(displayNames: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return displayNames.map { name ->
            val base = slugFor(name).ifEmpty { FALLBACK_SLUG }
            var candidate = base
            var index = 2
            while (!used.add(candidate)) {
                candidate = "${base}_$index"
                index++
            }
            candidate
        }
    }

    /** Every token one slug offers, brace-wrapped, for the settings chips. */
    fun tokensForSlug(slug: String): List<String> = SUFFIXES.map { suffix -> "{$slug$suffix}" }

    /** Every token a set of display names offers, in display order. */
    fun tokensForNames(displayNames: List<String>): List<String> =
        assignSlugs(displayNames).flatMap(::tokensForSlug)

    /** Reads the `insulin_types` array out of a journal snapshot document. */
    fun parse(journalJson: JSONObject?): List<TypeSnapshot> {
        val array = journalJson?.optJSONArray(JSON_ARRAY) ?: return emptyList()
        val types = ArrayList<TypeSnapshot>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val slug = item.optString(KEY_SLUG).takeIf { it.isNotBlank() } ?: continue
            types.add(
                TypeSnapshot(
                    slug = slug,
                    displayName = item.optString(KEY_NAME),
                    lastUnits = item.optDouble(KEY_LAST_UNITS, Double.NaN).toFloat(),
                    lastTimestampMs = item.optLong(KEY_LAST_TIMESTAMP, 0L),
                    iobUnits = item.optDouble(KEY_IOB, Double.NaN).toFloat(),
                    todayUnits = item.optDouble(KEY_TODAY_UNITS, Double.NaN).toFloat()
                )
            )
        }
        return types
    }

    /**
     * Replaces every insulin-type token in [template].
     *
     * A type with no logged dose renders the dose tokens empty rather than
     * "0" — the same way `{auto}` and `{raw}` stay empty when there is nothing
     * to report — while the running totals render "0", matching `{iob}`.
     */
    fun expand(
        template: String,
        types: List<TypeSnapshot>,
        atMillis: Long,
        formatTime: (Long) -> String
    ): String {
        if (types.isEmpty()) return template
        var rendered = template
        types.forEach { type ->
            val hasDose = type.lastTimestampMs > 0L && type.lastUnits.isFinite() && type.lastUnits > 0f
            rendered = rendered
                .replace("{${type.slug}}", if (hasDose) formatUnits(type.lastUnits) else "")
                .replace("{${type.slug}_time}", if (hasDose) formatTime(type.lastTimestampMs) else "")
                .replace(
                    "{${type.slug}_ago}",
                    if (hasDose) minutesSince(type.lastTimestampMs, atMillis).toString() else ""
                )
                .replace("{${type.slug}_iob}", formatUnits(type.iobUnits.orZero()))
                .replace("{${type.slug}_today}", formatUnits(type.todayUnits.orZero()))
        }
        return rendered
    }

    private fun minutesSince(timestampMs: Long, atMillis: Long): Long =
        ((atMillis - timestampMs) / 60_000L).coerceAtLeast(0L)

    private fun Float.orZero(): Float = if (isFinite()) this else 0f

    /** Whole units stay whole: "12", not "12.0"; halves keep one decimal. */
    private fun formatUnits(units: Float): String {
        if (!units.isFinite()) return "0"
        val rounded = Math.round(units * 100f) / 100f
        return if (rounded == rounded.toLong().toFloat()) {
            rounded.toLong().toString()
        } else {
            "%.2f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
        }
    }
}
