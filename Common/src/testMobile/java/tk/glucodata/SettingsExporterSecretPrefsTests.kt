package tk.glucodata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.data.SettingsExporter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsExporterSecretPrefsTests.TestApplication::class)
class SettingsExporterSecretPrefsTests {

    class TestApplication : Applic() {
        override fun onCreate() { /* No native initialization in the JVM harness. */ }
    }

    private fun stringEntry(value: String): JSONObject =
        JSONObject().put("type", "string").put("value", value)

    @Test
    fun vendorCredentialPreferencesAreNeverExported() {
        assertTrue(SettingsExporter.isSecretPreferenceFile("lumiflex_cloud"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("ican_cloud"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("dexcom_share"))
        assertTrue(SettingsExporter.isSecretPreferenceFile(tk.glucodata.RestrictedMode.PREFS_NAME))
        assertFalse(SettingsExporter.isSecretPreferenceFile("tk.glucodata_preferences"))
    }

    @Test
    fun legacyBackupCannotRestoreSecretPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()

        val payload = JSONObject()
            .put(
                "lumiflex_cloud",
                JSONObject().put(
                    "values",
                    JSONObject().put("lumiflex_cloud_token", stringEntry("s3cret")),
                ),
            )
            .put(
                "tk.glucodata_preferences",
                JSONObject().put(
                    "values",
                    JSONObject().put("some_key", stringEntry("keep")),
                ),
            )

        SettingsExporter.importSharedPreferences(context, payload)

        assertFalse(
            context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE)
                .contains("lumiflex_cloud_token"),
        )
        assertEquals(
            "keep",
            context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
                .getString("some_key", ""),
        )
    }
}
