package tk.glucodata.ui.alerts

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.alerts.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class, shadows = [PreviewSoundNativeInputs::class])
class AlertSoundPreviewUsageTests {
    @Before fun prepare() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        configureUsage(AudioAttributes.USAGE_NOTIFICATION)
    }

    private fun configureUsage(usage: Int) {
        PreviewSoundNativeInputs.configuredUsage = usage
        Notify.makenotification_audio()
    }

    @Test fun standardPreviewKeepsMediaVolumeForNotificationOnlyAlerts() {
        for (usage in listOf(0, AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ALARM)) {
            configureUsage(usage)
            for (mode in AlertDeliveryMode.entries) for (override in listOf(false, true)) {
                val config = AlertConfig(AlertType.LOW, deliveryMode = mode, overrideDND = override)
                val expected = when {
                    override || mode != AlertDeliveryMode.NOTIFICATION_ONLY -> AudioAttributes.USAGE_ALARM
                    usage == 0 -> AudioAttributes.USAGE_NOTIFICATION
                    else -> usage
                }
                assertEquals("usage=$usage mode=$mode override=$override", expected,
                    config.soundPickerRequest {}.audioUsage)
            }
        }
    }

    @Test fun customPreviewUsesTheSameMediaAndAlarmRouting() {
        configureUsage(AudioAttributes.USAGE_MEDIA)
        for (style in listOf("notification", "NOTIFICATION_ONLY", "alarm", "SYSTEM_ALARM", "both")) {
            for (override in listOf(false, true)) {
                val config = CustomAlertConfig(style = style, overrideDnd = override)
                val expected = if (override || style in listOf("alarm", "SYSTEM_ALARM", "both")) {
                    AudioAttributes.USAGE_ALARM
                } else AudioAttributes.USAGE_MEDIA
                assertEquals("style=$style override=$override", expected, config.soundPickerRequest {}.audioUsage)
            }
        }
    }

    @Test fun previewUsesActivePlaybackAttributesUntilSettingsRefresh() {
        configureUsage(AudioAttributes.USAGE_MEDIA)
        val config = AlertConfig(AlertType.LOW, deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY)
        PreviewSoundNativeInputs.configuredUsage = AudioAttributes.USAGE_NOTIFICATION
        assertEquals(AudioAttributes.USAGE_MEDIA, config.soundPickerRequest {}.audioUsage)
        Notify.makenotification_audio()
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, config.soundPickerRequest {}.audioUsage)
    }

    @Test fun previewUsesTheSelectedOverallIntensityAndVolumeAdjustment() {
        val standard = AlertConfig(AlertType.LOW, hapticProfile = HapticProfile.SOFT,
            soundVolumePercent = 55).soundPickerRequest {}
        assertEquals("SOFT", standard.intensityProfile)
        assertEquals(.22f, AlertSoundVolume.gain(standard.volumePercent, standard.intensityProfile), .000001f)
        val custom = CustomAlertConfig(hapticProfile = "steady", soundVolumePercent = 55).soundPickerRequest {}
        assertEquals("steady", custom.intensityProfile)
        assertEquals(.385f, AlertSoundVolume.gain(custom.volumePercent, custom.intensityProfile), .000001f)
    }

    @Test fun legacyNotificationOnlyKindsKeepTheirConfiguredUsage() {
        configureUsage(AudioAttributes.USAGE_MEDIA)
        for (type in listOf(AlertType.AVAILABLE, AlertType.AMOUNT)) {
            assertEquals(AudioAttributes.USAGE_MEDIA,
                AlertConfig(type, overrideDND = true).soundPickerRequest {}.audioUsage)
        }
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class PreviewSoundNativeInputs {
    companion object {
        var configuredUsage = AudioAttributes.USAGE_NOTIFICATION
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getSoundType(): Int = configuredUsage
    }
}
