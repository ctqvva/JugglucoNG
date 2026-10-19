package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapse setting's text is translated into a dozen locales by hand. These guard what a
 * translator can silently break: the format argument the summary lines depend on.
 *
 * There used to be a note here explaining that the xDrip/xInfuus loop feed is exempt from
 * collapse. It no longer is (see ExchangeOutputPolicy's LOOP_FEED destination), so the note was
 * removed from every locale rather than left behind making a claim that is no longer true.
 */
class DataSmoothingStringsTests {
    private val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }

    private fun stringFiles(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }

    private fun value(file: File, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    @Test
    fun noLocaleStillHasTheRetiredLoopFeedNote() {
        for (file in stringFiles()) {
            assertTrue(
                "${file.parentFile.name}: data_smoothing_collapse_loop_note should have been removed",
                value(file, "data_smoothing_collapse_loop_note") == null
            )
        }
    }

    @Test
    fun theIntervalIsFilledInByEveryLocaleThatTranslatesTheSummaryLines() {
        val checked = ArrayList<String>()
        for (file in stringFiles()) {
            val match = value(file, "data_smoothing_collapse_desc_match")
            val capped = value(file, "data_smoothing_collapse_desc_capped")
            match?.let { assertTrue("${file.parentFile.name}: _desc_match lost its %1\$d", it.contains("%1\$d")) }
            capped?.let { assertTrue("${file.parentFile.name}: _desc_capped lost its %1\$d", it.contains("%1\$d")) }
            checked += file.parentFile.name
        }
        assertTrue("expected the default locale and the translations, got $checked", checked.size >= 15)
    }

    @Test
    fun noLocaleStillShowsTheSummaryLinesInEnglish() {
        val english = listOf("data_smoothing_collapse_desc_match", "data_smoothing_collapse_desc_capped", "data_smoothing_collapse_summary_format")
            .associateWith { value(File(resDir, "values/strings.xml"), it) }
        for (file in stringFiles().filter { it.parentFile.name != "values" }) {
            for ((key, en) in english) {
                val translated = value(file, key) ?: continue
                assertTrue("${file.parentFile.name}: $key is still the English text", translated != en)
            }
        }
    }
}
