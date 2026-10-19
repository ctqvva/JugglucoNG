package tk.glucodata

object GlucoseValueParser {
    private val NUMBER_REGEX = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    @JvmStatic
    fun parseFirst(text: String?): Float? {
        val token = NUMBER_REGEX.find(text.orEmpty())?.value ?: return null
        return token.replace(',', '.').toFloatOrNull()
    }

    @JvmStatic
    fun parseFirstOrZero(text: String?): Float = parseFirst(text) ?: 0f
}
