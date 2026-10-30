package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchIndexTests {
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
