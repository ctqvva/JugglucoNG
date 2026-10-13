package tk.glucodata.alerts

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class)
class AlertSoundVolumePersistenceTests {
    @Before fun prepare() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        Applic.app.getSharedPreferences("tk.glucodata.alerts", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun repositorySaveReloadUsesThePlaybackPreferenceKey() {
        // Exercise actual preference persistence without unrelated JNI/sensor setup.
        val save = AlertRepository::class.java.getDeclaredMethod("saveToPrefs", AlertConfig::class.java)
            .apply { isAccessible = true }
        val load = AlertRepository::class.java.getDeclaredMethod("loadFromPrefs", AlertType::class.java, AlertConfig::class.java)
            .apply { isAccessible = true }
        val prefs = Applic.app.getSharedPreferences("tk.glucodata.alerts", Context.MODE_PRIVATE)
        for (type in AlertType.entries.filter { it != AlertType.SENSOR_EXPIRY }) {
            val defaults = AlertDefaults.defaultConfig(type, false)
            save.invoke(AlertRepository, defaults.copy(soundVolumePercent = 55, hapticProfile = HapticProfile.SOFT))
            assertEquals(55, prefs.getInt(AlertSoundVolume.preferenceKey(type.id), 100))
            val restored = load.invoke(AlertRepository, type, defaults) as AlertConfig
            assertEquals(55, restored.soundVolumePercent)
            assertEquals(HapticProfile.SOFT, restored.hapticProfile)
            save.invoke(AlertRepository, defaults.copy(soundVolumePercent = 100))
            assertEquals(100, (load.invoke(AlertRepository, type, defaults) as AlertConfig).soundVolumePercent)
            prefs.edit().remove(AlertSoundVolume.preferenceKey(type.id)).commit()
            assertEquals(80, (load.invoke(AlertRepository, type, defaults) as AlertConfig).soundVolumePercent)
            prefs.edit().putInt(AlertSoundVolume.preferenceKey(type.id), 0).commit()
            assertEquals(30, (load.invoke(AlertRepository, type, defaults) as AlertConfig).soundVolumePercent)
        }
    }
}

class AlertVolumeTestApplication : Applic() {
    override fun onCreate() {}
}
