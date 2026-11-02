package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.SettingsExporter

class SettingsExporterSecretPrefsTests {

    @Test
    fun vendorCredentialPreferencesAreNeverExported() {
        assertTrue(SettingsExporter.isSecretPreferenceFile("lumiflex_cloud"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("ican_cloud"))
        assertTrue(SettingsExporter.isSecretPreferenceFile("dexcom_share"))
        assertFalse(SettingsExporter.isSecretPreferenceFile("tk.glucodata_preferences"))
    }
}
