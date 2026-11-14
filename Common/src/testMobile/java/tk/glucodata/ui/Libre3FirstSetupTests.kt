package tk.glucodata.ui

import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic
import tk.glucodata.AutoSensorSwitch
import tk.glucodata.R
import tk.glucodata.WatchInterop
import tk.glucodata.drivers.libre3.Libre3Persistence
import tk.glucodata.drivers.libre3.Libre3ReviewApplication
import tk.glucodata.drivers.libre3.Libre3ReviewNatives

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Libre3ReviewApplication::class, shadows = [Libre3ReviewNatives::class])
class Libre3FirstSetupTests {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Applic

    @Before fun freshInstall() {
        app = RuntimeEnvironment.getApplication() as Applic
        Applic.app = app
        for (name in listOf(Libre3Persistence.PREFS_NAME, Libre3Persistence.SETTINGS_PREFS_NAME, "tk.glucodata_preferences")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun freshSetupNeedsNoLibreViewAccountAndHidesManualSourceFields() {
        compose.setContent { JugglucoTheme(ThemeMode.DARK) { Libre3SettingsScreen(rememberNavController()) } }
        compose.onNodeWithText(app.getString(R.string.libre3_managed_enable)).assertExists()
        compose.onNodeWithText(app.getString(R.string.libre3_managed_receiver_account)).assertExists()
        compose.onNodeWithText(app.getString(R.string.libre3_managed_source_url)).assertDoesNotExist()
        val entries = SettingsSearchIndex.entries.filter { it.title in setOf(R.string.libre3_managed_title, R.string.libre3_managed_enable, R.string.libre3_managed_receiver_account) }
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.condition == null })
    }

    @Test fun automaticSwitchPreferencePersistsEvenIfWatchPushIsUnavailable() {
        WatchInterop.setAutoSensorSwitch("no-watch", true)
        assertTrue(AutoSensorSwitch.isEnabled())
        WatchInterop.setAutoSensorSwitch("no-watch", false)
        assertFalse(AutoSensorSwitch.isEnabled())
    }
}
