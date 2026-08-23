package tk.glucodata.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class OttaiSetupFlowTests {
    @Test
    fun `saved materials connect through normal managed flow`() {
        assertEquals(
            OttaiSetupConnectRoute.STORED_MATERIALS,
            ottaiSetupConnectRoute(
                hasAuthKeys = true,
                requiresV3Bootstrap = true,
                signedIn = true,
            ),
        )
    }

    @Test
    fun `fresh signed in V3 sensor uses wizard credential bootstrap`() {
        assertEquals(
            OttaiSetupConnectRoute.V3_CREDENTIAL_BOOTSTRAP,
            ottaiSetupConnectRoute(
                hasAuthKeys = false,
                requiresV3Bootstrap = true,
                signedIn = true,
            ),
        )
        assertEquals(
            false,
            ottaiSetupPublishesManagedSensor(OttaiSetupConnectRoute.V3_CREDENTIAL_BOOTSTRAP),
        )
    }

    @Test
    fun `missing materials without a signed V3 route remain blocked`() {
        listOf(
            ottaiSetupConnectRoute(false, true, false),
            ottaiSetupConnectRoute(false, false, true),
        ).forEach { route ->
            assertEquals(OttaiSetupConnectRoute.BLOCKED, route)
        }
    }
}
