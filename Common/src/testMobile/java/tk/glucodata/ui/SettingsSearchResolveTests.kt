package tk.glucodata.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.R

/** A row its screen hides is no result, or leads to the control that brings it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsSearchResolveTests {

    private fun resolve(shown: Boolean) =
        SettingsSearchIndex.resolve(ApplicationProvider.getApplicationContext()) { shown }
            .map { it.item }

    private fun List<ResolvedSettingsSearchEntry>.of(title: Int) = singleOrNull { it.entry.title == title }

    @Test
    fun aShownRowIsFoundWhereItIs() {
        val results = resolve(shown = true)
        assertEquals(SettingsSearchAnchors.forRow(R.string.state_dose_hint_horizon),
            results.of(R.string.state_dose_hint_horizon)?.anchor)
        assertEquals(SettingsSearchAnchors.forRow(R.string.restricted_biometric_title),
            results.of(R.string.restricted_biometric_title)?.anchor)
    }

    @Test
    fun aHiddenRowLeadsToWhatBringsItBackOrIsNotFound() {
        val results = resolve(shown = false)
        assertEquals(SettingsSearchAnchors.forRow(R.string.state_dose_hint_title),
            results.of(R.string.state_dose_hint_horizon)?.anchor)
        assertEquals(SettingsSearchAnchors.forRow(R.string.watch_forwarding_title),
            results.of(R.string.watch_forwarding_interval_title)?.anchor)
        assertEquals(SettingsSearchAnchors.forRow(R.string.use_custom_rendezvous_server),
            results.of(R.string.verify_rendezvous_certificate)?.anchor)
        assertNull(results.of(R.string.restricted_biometric_title))
        assertNull(results.of(R.string.mirror_background_liveness))
        assertNull(results.of(R.string.app_updates_auto_title))
        // Rows without a condition are there either way.
        assertEquals(SettingsSearchAnchors.forRow(R.string.restricted_pin_title),
            results.of(R.string.restricted_pin_title)?.anchor)
    }
}
