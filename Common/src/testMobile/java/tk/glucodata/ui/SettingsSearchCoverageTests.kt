package tk.glucodata.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.R

/**
 * Keeps search in step with the screens, by reading their source.
 *
 * A settings row that search cannot find is a row nobody looks for by name, so every one
 * has to be in [SettingsSearchIndex] or listed in [notSearchable] with the reason. And a
 * result that should land on a row only does if that row wears the same anchor, so the
 * anchors on both sides have to agree.
 */
class SettingsSearchCoverageTests {

    private val uiSources: List<File> =
        listOf(File("src/mobile/java/tk/glucodata/ui"), File("Common/src/mobile/java/tk/glucodata/ui"))
            .first { it.isDirectory }
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private val rowCall = Regex(
        """\b(?:SettingsSwitchItem|SettingsItem|SettingsNavSwitchItem|MasterSwitchCard|MasterToggleRow|""" +
            """SettingsSlider|SettingsChoice|PredictiveSimulationParameterRow|ExpandableSettingsCard|""" +
            """DisclosingSwitchCard)\s*\(\s*(?:title|label)\s*=\s*stringResource\(\s*R\.string\.(\w+)"""
    )
    // Worn as a row's `searchAnchor =` or through `Modifier.settingsSearchAnchor(…)`; a mention in
    // a RevealSettingsSearchAnchor set is not a row wearing it.
    private val wearing = """(?:searchAnchor\s*=\s*|settingsSearchAnchor\(\s*)"""
    private val rowAnchor = Regex(wearing + """SettingsSearchAnchors\.forRow\(R\.string\.(\w+)\)""")
    private val namedAnchor = Regex(wearing + """SettingsSearchAnchors\.([A-Z][A-Z0-9_]+)\b""")

    /** Titled rows that are deliberately not search results, by why. */
    private val notSearchable: Map<String, String> = buildMap {
        fun because(reason: String, vararg names: String) = names.forEach { put(it, reason) }
        because(
            "the screen's on/off card: searching its name finds the screen, which opens on it",
            "active", "debug_record_title", "dexcom_share_active", "enable_overlay", "enable_overlay_service",
            "insulin_pens_enable_title", "libreview_active", "mirror_clone_master", "mq_sync_title",
            "mq_follower_title",
        )
        because(
            "a status line or a list entry, not a setting",
            "app_updates_installed_title", "app_updates_latest_title", "config", "status", "watch_none_found",
            "garmin_sdk_ready", "garmin_registered", "garmin_last_send", "garmin_last_received",
            "garmin_queued_messages", "glucose_meters_none", "insulin_pens_none", "insulin_pen_name",
        )
        because(
            "an action or a way into a sub-list, found through the screen that holds it",
            "add_connection", "api_source_settings_title", "lumiflex_manage_followers",
            "mq_follower_settings_title", "sensor_handover_old_action_title",
        )
        because(
            "slider controls are reached through their parent display screen",
            "arrow_size_label", "background_opacity_label",
            "chart_size_label", "corner_radius_label",
            "font_size_label", "gap_width_label",
            "opacity_label", "size_label",
            "status_bar_icon_size_label", "text_size_label",
            "offset",
        )
        because(
            "part of a control that is indexed itself (times of the voice schedule, colours of the range card, …)",
            "voice_schedule_from", "voice_schedule_until", "chart_limits_title", "glucose_range_title",
            "glucose_arrow_forecast_title", "glucose_chart_range_colors_title", "glucose_app_chart_range_colors_title",
            "prediction_analysis_past_title", "settings_section_display",
        )
        because(
            "inside the scheduled-backup dialog, which search opens as a whole",
            "scheduled_backup_enabled", "scheduled_backup_folder", "scheduled_backup_time",
            "scheduled_backup_retention",
        )
        because(
            "on a mirror connection's own sheet, not a screen search can open",
            "mirror_auto_detect_ip", "mirror_send_data", "mirror_receive_data",
        )
        because(
            "on a sensor's card or in a setup wizard, outside Settings",
            "calibration", "sibionics_uncertainty_ribbon", "aidex_key_management_title", "libre_setup_step_libreview",
            "ottai_account_title", "ottai_saved_sensors_title",
        )
    }

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun stringName(id: Int): String = stringNames.getValue(id)

    private val indexedTitles: Set<String> by lazy {
        SettingsSearchIndex.entries.map { stringName(it.title) }.toSet()
    }

    @Test
    fun everySettingsRowIsSearchableOrExcludedOnPurpose() {
        val missing = uiSources.flatMap { file ->
            rowCall.findAll(file.readText()).map { it.groupValues[1] }
                .filter { it !in indexedTitles && it !in notSearchable }
                .map { "$it (${file.name})" }
                .toList()
        }.distinct()
        assertTrue(
            "Settings rows search cannot find. Add each to SettingsSearchIndex, or to notSearchable with why:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun exclusionsAreStillRows() {
        val titles = uiSources.flatMap { rowCall.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }.toSet()
        val stale = notSearchable.keys.filter { it !in titles || it in indexedTitles }
        assertTrue("Exclusions that are gone or indexed after all: $stale", stale.isEmpty())
    }

    @Test
    fun everyAnchorAResultUsesIsWornByARow() {
        val screens = uiSources.filter { it.name != "SettingsSearchIndex.kt" }.map { it.readText() }
        val wornByRow = screens.flatMap { text -> rowAnchor.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        val wornByName = screens.flatMap { text -> namedAnchor.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        val named = SettingsSearchAnchors::class.java.declaredFields
            .filter { it.type == String::class.java }
            .associate { it.isAccessible = true; it.get(null) as String to it.name }

        // Both the row itself and, while it is hidden, the control a result leads to instead.
        val unworn = SettingsSearchIndex.entries.flatMap { entry ->
            listOfNotNull(entry.anchor, entry.otherwise).mapNotNull { anchor ->
                val worn = if (anchor.startsWith("row/")) {
                    stringName(anchor.removePrefix("row/").toInt()) in wornByRow
                } else {
                    named[anchor] in wornByName
                }
                if (worn) null else "${stringName(entry.title)} → $anchor"
            }
        }
        assertTrue("Results whose row does not wear their anchor (they would open at the top):\n" +
            unworn.joinToString("\n"), unworn.isEmpty())
    }

    @Test
    fun onlyAHiddenRowLeadsElsewhere() {
        val loose = SettingsSearchIndex.entries
            .filter { it.otherwise != null && (it.condition == null || it.anchor == null || it.otherwise == it.anchor) }
            .map { stringName(it.title) }
        assertTrue("A fallback needs a condition that hides the entry's own row: $loose", loose.isEmpty())
    }

    @Test
    fun everyAnchoredRowIsAResult() {
        val anchoredInIndex = SettingsSearchIndex.entries.mapNotNull { it.anchor }.toSet()
        val orphans = uiSources.filter { it.name != "SettingsSearchIndex.kt" }.flatMap { file ->
            rowAnchor.findAll(file.readText()).map { it.groupValues[1] }
                .filter { name -> SettingsSearchAnchors.forRow(R.string::class.java.getField(name).getInt(null)) !in anchoredInIndex }
                .map { "$it (${file.name})" }
                .toList()
        }
        assertTrue("Rows anchored for a result that does not exist: $orphans", orphans.isEmpty())
    }
}
