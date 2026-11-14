package tk.glucodata.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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
        Libre3ReviewNatives.reset()
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
    @Test fun receiverSaveFailureIsShownAndCanBeRetriedWithoutCrashingSettings() {
        Libre3Persistence.setReceiverSource(app, Libre3Persistence.RECEIVER_OWN)
        val original = app.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        var failWrites = true
        fun failedStorageContext(base: Context) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name != Libre3Persistence.PREFS_NAME) super.getSharedPreferences(name, mode) else object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = original.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun putLong(key: String, value: Long): SharedPreferences.Editor { delegate.putLong(key, value); return this }
                            override fun commit(): Boolean { val saved = delegate.commit(); return saved && !failWrites }
                        }
                    }
                }
        }
        lateinit var context: Context
        compose.setContent {
            val activityContext = LocalContext.current
            context = remember { failedStorageContext(activityContext) }
            CompositionLocalProvider(LocalContext provides context) {
                JugglucoTheme(ThemeMode.DARK) { Libre3SettingsScreen(rememberNavController()) }
            }
        }
        val error = app.getString(R.string.libre3_managed_receiver_save_failed)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).assertExists()
        assertTrue(runCatching { tk.glucodata.drivers.libre3.Libre3ManagedNfc.receiverId(context) }.isFailure)
        failWrites = false
        compose.onNodeWithText(app.getString(R.string.ottai_retry)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isEmpty() }
        assertTrue(tk.glucodata.drivers.libre3.Libre3ManagedNfc.receiverId(context) != 0L)
    }

    @Test fun fetchedAndManualAccountChangesRefreshTheOpenReceiverDisplay() {
        compose.setContent { JugglucoTheme(ThemeMode.DARK) { Libre3ManagedDriverSection() } }
        val missing = app.getString(R.string.libre3_managed_receiver_none)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(missing, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { tk.glucodata.Natives.setlibreAccountID("fetched-test-account") }
        val fetched = tk.glucodata.drivers.libre3.Libre3ReceiverId.toWireHex(0x11223344L)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(fetched, substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0x11223344L, tk.glucodata.drivers.libre3.Libre3ManagedNfc.receiverId(app))
        compose.runOnIdle { tk.glucodata.Natives.setlibreAccountIDnumber(0x55667788L) }
        val manual = tk.glucodata.drivers.libre3.Libre3ReceiverId.toWireHex(0x55667788L)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(manual, substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0x55667788L, tk.glucodata.drivers.libre3.Libre3ManagedNfc.receiverId(app))
    }

}
