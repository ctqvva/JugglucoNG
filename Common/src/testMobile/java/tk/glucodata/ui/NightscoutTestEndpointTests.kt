package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.drivers.nightscout.NightscoutModePreference

class NightscoutTestEndpointTests {

    @Test
    fun `v3 setups are tested against the v3 status endpoint`() {
        assertEquals("api/v3/status", nightscoutTestEndpointPath(useV3 = true))
    }

    @Test
    fun `v1 setups keep the classic status endpoint`() {
        assertEquals("api/v1/status.json", nightscoutTestEndpointPath(useV3 = false))
    }

    @Test
    fun `connection test follows the selected direction when API settings differ`() {
        for (uploadV3 in listOf(false, true)) {
            for (followV3 in listOf(false, true)) {
                assertEquals(uploadV3, nightscoutTestUsesV3(NightscoutModePreference.Mode.UPLOAD, uploadV3, followV3))
                assertEquals(followV3, nightscoutTestUsesV3(NightscoutModePreference.Mode.FOLLOW, uploadV3, followV3))
            }
        }
    }
}
