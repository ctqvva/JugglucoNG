package tk.glucodata

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.settings.DisplayDeltaSettings
import tk.glucodata.settings.SettingsRegistry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeltaIntervalSyncTests {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private fun process(name: String) = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(file: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$name-$file", mode)
    }

    @Test fun anIntervalEditPublishesTheSavedWireValueAndRefreshesWidgetsWithoutMirroringOptIn() {
        val phone = process("phone")
        val phonePrefs = phone.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)
        phonePrefs.edit().putBoolean(DisplayDeltaSource.WEAR_ENABLED, true).apply()
        var payload: ByteArray? = null
        val settings = DisplayDeltaSettings(phone) { source -> payload = WearPrefsSync.encode(source) }
        val tickBefore = GlucoseUpdateBroadcaster.tick.value
        settings.setIntervalMinutes(1)

        val wire = requireNotNull(payload).decodeToString()
        assertTrue(wire.contains("i:${DisplayDeltaSource.INTERVAL}=1\n"))
        assertFalse(wire.contains(DisplayDeltaSource.WEAR_ENABLED))
        assertTrue(SettingsRegistry.mirrored.any { it.key == DisplayDeltaSource.INTERVAL })
        assertEquals(tickBefore + 1, GlucoseUpdateBroadcaster.tick.value)

        settings.setIntervalMinutes(2) // Unsupported intervals normalize to five minutes.
        assertTrue(requireNotNull(payload).decodeToString().contains("i:${DisplayDeltaSource.INTERVAL}=5\n"))
        assertEquals(5, phonePrefs.getInt(DisplayDeltaSource.INTERVAL, 1))
        assertEquals(tickBefore + 2, GlucoseUpdateBroadcaster.tick.value)
        assertTrue(phonePrefs.getBoolean(DisplayDeltaSource.WEAR_ENABLED, false))
    }
}
