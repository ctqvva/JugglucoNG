package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Test

class NightscoutModePreferenceTests {
    @Test
    fun savedFollowModeSurvivesBothServicesBeingDisabled() {
        assertEquals(
            NightscoutModePreference.Mode.FOLLOW,
            NightscoutModePreference.resolve(
                stored = "FOLLOW",
                legacyUploaderActive = false,
                legacyFollowerEnabled = false,
            ),
        )
    }

    @Test
    fun legacyInstallInfersTheOnlyActiveService() {
        assertEquals(
            NightscoutModePreference.Mode.FOLLOW,
            NightscoutModePreference.resolve(
                stored = null,
                legacyUploaderActive = false,
                legacyFollowerEnabled = true,
            ),
        )
        assertEquals(
            NightscoutModePreference.Mode.UPLOAD,
            NightscoutModePreference.resolve(
                stored = null,
                legacyUploaderActive = true,
                legacyFollowerEnabled = false,
            ),
        )
    }
}
