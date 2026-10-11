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
    private val keys = listOf(
        "data_smoothing_window_title", "data_smoothing_window_desc",
        "data_smoothing_scope_title", "data_smoothing_scope_all", "data_smoothing_scope_all_desc",
        "data_smoothing_scope_graph_and_sent", "data_smoothing_graph_only_title", "data_smoothing_graph_only_desc",
        "data_smoothing_graph_only_collapse_desc", "data_smoothing_exchange_only_title", "data_smoothing_exchange_only_desc",
        "data_smoothing_collapse_title", "data_smoothing_collapse_desc", "data_smoothing_collapse_desc_match",
        "data_smoothing_collapse_desc_capped", "data_smoothing_collapse_summary_format", "data_smoothing_preserve_originals",
        "nightscout_desc", "nightscout_settings_title", "nightscout_url_label", "nightscout_url_placeholder",
        "nightscout_secret_label", "nightscout_send_treatments", "nightscout_send_amounts_desc",
        "nightscout_send_long_insulin", "nightscout_send_long_insulin_desc",
        "nightscout_receive_amounts", "nightscout_receive_amounts_desc", "nightscout_upload_iob", "nightscout_upload_iob_desc",
        "nightscout_use_v3_api", "nightscout_use_v3_api_desc", "nightscout_follow_use_v3_api", "nightscout_follow_use_v3_api_desc",
        "nightscout_follow_interval_title", "nightscout_follow_interval_desc", "nightscout_follow_interval_doze",
        "nightscout_mode_upload", "nightscout_mode_follow", "nightscout_permissions_title", "nightscout_permissions_summary",
        "nightscout_permissions_upload", "nightscout_permissions_follow", "nightscout_resend_data",
        "nightscout_status_response_invalid_url", "close", "nightscout_follow_desc",
        "nightscout_api_help", "nightscout_send_treatments_help", "nightscout_long_insulin_help",
        "nightscout_receive_treatments_help", "nightscout_iob_help"
    )

    private fun value(file: File, key: String): String? =
        Regex("""<string name="$key"[^>]*>(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    @Test
    fun everySupportedLanguageTranslatesTheNewSettingsCopy() {
        val sharedText = setOf("nightscout_settings_title", "nightscout_url_placeholder")
        for (file in files.filter { it != defaultFile }) {
            for (key in keys) {
                val translated = value(file, key)
                assertNotNull("${file.parentFile.name}: missing $key", translated)
                if (key !in sharedText) {
                    assertTrue("${file.parentFile.name}: $key is still English", translated != value(defaultFile, key))
                }
                val placeholders = Regex("%[0-9]+\\\$[ds]")
                assertEquals(
                    "${file.parentFile.name}: $key changed format arguments",
                    placeholders.findAll(value(defaultFile, key)!!).map { it.value }.toList(),
                    placeholders.findAll(translated!!).map { it.value }.toList()
                )
            }
        }
    }

    @Test
    fun permissionHelpPreservesTheExactRolesInEveryLanguage() {
        val role = Regex("api:[a-z]+:[a-z]+")
        val expectedRoles = mapOf(
            "nightscout_permissions_upload" to setOf(
                "api:entries:create", "api:entries:update", "api:treatments:create", "api:treatments:update",
                "api:treatments:delete", "api:treatments:read", "api:devicestatus:create"
            ),
            "nightscout_permissions_follow" to setOf("api:entries:read", "api:treatments:read", "api:devicestatus:read")
        )
        for ((key, expected) in expectedRoles) {
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

    @Test
    fun shortSettingsSubtitlesDoNotEndInFullStops() {
        val subtitles = listOf(
            "nightscout_send_amounts_desc", "nightscout_send_long_insulin_desc", "nightscout_receive_amounts_desc",
            "nightscout_upload_iob_desc", "nightscout_use_v3_api_desc", "nightscout_follow_use_v3_api_desc",
            "data_smoothing_scope_all_desc", "data_smoothing_graph_only_desc", "data_smoothing_exchange_only_desc",
            "data_smoothing_collapse_desc_match", "data_smoothing_collapse_desc_capped"
        )
        for (file in files) {
            for (key in subtitles) {
                val text = value(file, key)
                assertNotNull("${file.parentFile.name}: missing $key", text)
                assertTrue("${file.parentFile.name}: $key ends in a full stop", text!!.trim().last() !in ".。．")
            }
        }
    }

    @Test
    fun detailedHelpRetainsApiPathsAndExtraIobFields() {
        for (file in files) {
            val apiHelp = value(file, "nightscout_api_help")!!
            val iobHelp = value(file, "nightscout_iob_help")!!
            for (path in listOf("/api/v1", "/api/v3")) {
                assertTrue("${file.parentFile.name}: API help lost $path", apiHelp.contains(path))
            }
            for (field in listOf("eIOB", "COB")) {
                assertTrue("${file.parentFile.name}: IOB help lost $field", iobHelp.contains(field))
            }
        }
    }

}
