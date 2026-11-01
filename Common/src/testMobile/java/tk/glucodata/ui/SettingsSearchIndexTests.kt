package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.app.Application
import tk.glucodata.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsSearchIndexTests {
    @Test
    fun protectedSettingsAreRemovedFromSearchWithoutHidingTheModeOrRestart() {
        val protectedConditions = setOf(
            SettingsSearchCondition.CALIBRATION_CONTROLS_VISIBLE,
            SettingsSearchCondition.SMOOTHING_CONTROLS_VISIBLE,
            SettingsSearchCondition.DATA_CLEARING_CONTROLS_VISIBLE,
        )
        val candidates = SettingsSearchIndex.resolve(RuntimeEnvironment.getApplication()) {
            it !in protectedConditions
        }
        val titles = candidates.map { it.item.entry.title }.toSet()
        for (title in listOf(R.string.settings_calibration_title, R.string.graph_smoothing_title,
            R.string.clear_history, R.string.clear_app_data, R.string.factory_reset)) {
            assertFalse("Protected title $title remains searchable", title in titles)
        }
        assertTrue(R.string.child_friendly_mode_title in titles)
        assertTrue(R.string.restart_app in titles)
    }

    @Test
    fun childFriendlyModeSearchRevealsItsSettingsRow() {
        val context = RuntimeEnvironment.getApplication()
        val candidates = SettingsSearchIndex.resolve(context) { true }
        val result = SettingsSearchMatcher.search("child-friendly mode", candidates)
            .firstOrNull { it.entry.title == R.string.child_friendly_mode_title }

        assertNotNull(result)
        assertEquals(
            SettingsSearchTarget.Action(SettingsSearchAction.CHILD_FRIENDLY_MODE),
            result!!.entry.target,
        )
        assertEquals(SettingsSearchAnchors.CHILD_FRIENDLY_MODE, result.entry.anchor)
    }
}
