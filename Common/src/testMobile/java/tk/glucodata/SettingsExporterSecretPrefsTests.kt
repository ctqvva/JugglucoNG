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
import tk.glucodata.drivers.bioe.BioeRegistry
import tk.glucodata.drivers.novo.NovoRegistry
import tk.glucodata.drivers.scgm.ScgmRegistry

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
        assertTrue(SettingsExporter.isSecretPreferenceFile("tk.glucodata_bioe_secrets"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("tk.glucodata_scgm_secrets"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("tk.glucodata_novo_secrets"))
        assertFalse(SettingsExporter.isSecretPreferenceFile("tk.glucodata_preferences"))
    }

    @Test
    fun newVendorSecretsAreStoredOnlyInExcludedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BioeRegistry.setApiToken(context, "<token>")
        ScgmRegistry.setApiToken(context, "<token>")
        NovoRegistry.setPasswordBytes(context, 1, 2)

        assertEquals("<token>", BioeRegistry.getApiToken(context))
        assertEquals("<token>", ScgmRegistry.getApiToken(context))
        assertEquals(1.toByte() to 2.toByte(), NovoRegistry.getPasswordBytes(context))
        assertFalse(
            context.getSharedPreferences("tk.glucodata_bioe_prefs", Context.MODE_PRIVATE)
                .contains("bioe_api_token"),
        )
        assertFalse(
            context.getSharedPreferences("tk.glucodata_scgm_prefs", Context.MODE_PRIVATE)
                .contains("scgm_api_token"),
        )
        assertFalse(
            context.getSharedPreferences("tk.glucodata_novo_prefs", Context.MODE_PRIVATE)
                .contains("novo_password_lo"),
        )
    }

    @Test
    fun newVendorSecretPreferencesCannotBeRestoredFromABackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = listOf(
            "tk.glucodata_bioe_secrets" to "bioe_api_token",
            "tk.glucodata_scgm_secrets" to "scgm_api_token",
            "tk.glucodata_novo_secrets" to "novo_password_lo",
        )
        val payload = JSONObject()
        secrets.forEach { (name, key) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            payload.put(
                name,
                JSONObject().put("values", JSONObject().put(key, stringEntry("s3cret"))),
            )
        }

        SettingsExporter.importSharedPreferences(context, payload)

        secrets.forEach { (name, key) ->
            assertFalse(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(key),
            )
        }
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
