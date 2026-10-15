package tk.glucodata.alerts

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class)
class DualSensorSettingsTests {
    @Before fun prepare() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        Applic.app.getSharedPreferences("tk.glucodata.alerts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun controlsPersistIndependentlyOfAlertEnablementAndUnits() {
        val save = AlertRepository::class.java.getDeclaredMethod("saveToPrefs", AlertConfig::class.java)
            .apply { isAccessible = true }
        val load = AlertRepository::class.java.getDeclaredMethod("loadFromPrefs", AlertType::class.java, AlertConfig::class.java)
            .apply { isAccessible = true }
        for (mmol in listOf(false, true)) for (enabled in listOf(false, true)) {
            val defaults = AlertDefaults.defaultConfig(AlertType.SENSOR_DISAGREEMENT, mmol)
            val config = defaults.copy(enabled = enabled, disagreementPercent = 35,
                durationMinutes = 10, experimentalEstimateEnabled = true)
            save.invoke(AlertRepository, config)
            assertEquals(config, load.invoke(AlertRepository, config.type, defaults))
            save.invoke(AlertRepository, config.copy(disagreementPercent = 900))
            assertEquals(50, (load.invoke(AlertRepository, config.type, defaults) as AlertConfig).disagreementPercent)
        }
    }

    @Test fun disagreementPercentageSurvivesNotificationFormatting() {
        val message = "Sensors differ by 27%. Check with a blood glucose meter."
        assertEquals(message, AlertDisplayText.notificationBadge(AlertType.SENSOR_DISAGREEMENT, false, message))
    }
}
