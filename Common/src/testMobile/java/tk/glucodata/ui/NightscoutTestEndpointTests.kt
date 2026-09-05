package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NightscoutTestEndpointTests {

    @Test
    fun `v3 setups are tested against the v3 status endpoint`() {
        assertEquals("api/v3/status", nightscoutTestEndpointPath(useV3 = true))
    }

    @Test
    fun `v1 setups keep the classic status endpoint`() {
        assertEquals("api/v1/status.json", nightscoutTestEndpointPath(useV3 = false))
    }
}
