package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dexcom Clarity's copy (an unofficial-upload warning, what changing the start date does, sign-in
 * recovery) must not fall back to English in a supported locale: docs/ui-style-guide.md asks for
 * every string in every `values-*` locale. The key list is read from the default file, so a new
 * `clarity_*` string is covered without editing this test.
 */
class ClarityLocalizationTests {
    private val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
    private val defaultFile = File(resDir, "values/strings.xml")
    private val locales = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
        .map { File(it, "strings.xml") }
        .filter { it.isFile }

    private fun strings(file: File): Map<String, String> =
        Regex("""<string name="(clarity_[a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val placeholders = Regex("%[0-9]+\\$[ds]")

    @Test
    fun everySupportedLanguageTranslatesClarity() {
        val english = strings(defaultFile)
        assertTrue("no clarity_* strings found in $defaultFile", english.size >= 30)
        // A product name reads the same in every language.
        val sharedText = setOf("clarity_title")
        assertTrue(locales.size >= 15)
        for (file in locales) {
            val locale = file.parentFile?.name ?: file.path
            val translated = strings(file)
            assertEquals("$locale: clarity_* keys unknown in English", emptySet<String>(), translated.keys - english.keys)
            for ((key, text) in english) {
                val value = translated[key]
                assertNotNull("$locale: missing $key", value)
                if (key !in sharedText) assertTrue("$locale: $key is still English", value != text)
                assertEquals(
                    "$locale: $key changed format arguments",
                    placeholders.findAll(text).map { it.value }.toList(),
                    placeholders.findAll(value!!).map { it.value }.toList(),
                )
            }
        }
    }
}
