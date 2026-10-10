package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent new settings help from silently falling back to English in a supported locale. */
class SettingsCopyLocalizationTests {
    private val resDir = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
    private val files = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
    private val defaultFile = File(resDir, "values/strings.xml")

    private fun value(file: File, key: String): String? =
        Regex("""<string name="$key"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    @Test
    fun everySupportedLanguageTranslatesTheNewSettingsCopy() {
        val keys = listOf(
            "data_smoothing_window_desc", "data_smoothing_scope_all",
            "data_smoothing_graph_only_collapse_desc", "nightscout_send_treatments",
            "nightscout_permissions_title", "nightscout_permissions_summary",
            "nightscout_permissions_upload", "nightscout_permissions_follow", "nightscout_resend_data"
        )
        for (file in files.filter { it != defaultFile }) {
            for (key in keys) {
                val translated = value(file, key)
                assertNotNull("${file.parentFile.name}: missing $key", translated)
                assertTrue("${file.parentFile.name}: $key is still English", translated != value(defaultFile, key))
            }
        }
    }

    @Test
    fun permissionHelpPreservesTheExactRolesInEveryLanguage() {
        val role = Regex("api:[a-z]+:[a-z]+")
        for (key in listOf("nightscout_permissions_upload", "nightscout_permissions_follow")) {
            val expected = role.findAll(value(defaultFile, key)!!).map { it.value }.toSet()
            for (file in files) {
                val translated = value(file, key)
                assertNotNull("${file.parentFile.name}: missing $key", translated)
                assertEquals(
                    "${file.parentFile.name}: $key changed token roles",
                    expected, role.findAll(translated!!).map { it.value }.toSet()
                )
            }
        }
    }
}
