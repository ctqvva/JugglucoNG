package tk.glucodata.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.drivers.ottai.OttaiRegistry

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

    @Test
    fun `sensor selection fetches only missing credentials and never implies connect`() {
        assertEquals(false, ottaiSetupSelectionFetchesCredentials(hasAuthKeys = true, signedIn = true))
        assertEquals(false, ottaiSetupSelectionFetchesCredentials(hasAuthKeys = true, signedIn = false))
        assertEquals(true, ottaiSetupSelectionFetchesCredentials(hasAuthKeys = false, signedIn = true))
        assertEquals(false, ottaiSetupSelectionFetchesCredentials(hasAuthKeys = false, signedIn = false))
    }

    @Test
    fun `cloud unbind is available only for the exact selected active binding`() {
        val active = device("70D07E2552DB", unbindTime = 0L)
        val past = device("18690ADED9B3", unbindTime = 1_787_500_000_000L)
        val devices = listOf(active, past)

        assertEquals(active, ottaiActiveCloudUnbindTarget("70:D0:7E:25:52:DB", devices))
        assertNull(ottaiActiveCloudUnbindTarget("18690ADED9B3", devices))
        assertNull(ottaiActiveCloudUnbindTarget("6CA04230E260", devices))
        assertNull(ottaiActiveCloudUnbindTarget("", devices))
    }

    @Test
    fun `cloud binding feedback is scoped to the selected sensor and exact active row`() {
        val active = device("70D07E2552DB", unbindTime = 0L)
        val past = device("18690ADED9B3", unbindTime = 1_787_500_000_000L)
        val devices = listOf(active, past)

        assertEquals(
            OttaiCloudBindingUiState.CHECKING,
            ottaiCloudBindingUiState(true, active.mac, active.mac, "", "", devices),
        )
        assertEquals(
            OttaiCloudBindingUiState.BOUND,
            ottaiCloudBindingUiState(true, active.mac, "", active.mac, "", devices),
        )
        assertEquals(
            OttaiCloudBindingUiState.NOT_BOUND,
            ottaiCloudBindingUiState(true, past.mac, "", past.mac, "", devices),
        )
        assertEquals(
            OttaiCloudBindingUiState.ERROR,
            ottaiCloudBindingUiState(true, active.mac, "", "", active.mac, devices),
        )
        assertEquals(
            OttaiCloudBindingUiState.HIDDEN,
            ottaiCloudBindingUiState(true, active.mac, "", past.mac, "", devices),
        )
        assertEquals(
            OttaiCloudBindingUiState.HIDDEN,
            ottaiCloudBindingUiState(false, active.mac, "", active.mac, "", devices),
        )
    }

    @Test
    fun wizardLabelIgnoresProvisionalStartAndAcceptedMaxActiveUntilCloudStart() {
        val materials = OttaiRegistry.DeviceMaterials(
            keyAHex = "a".repeat(192),
            method = "x",
            coefficient = "1",
            activeTimeMs = 0L,
            deviceVersion = "V2",
            deviceId = 1,
            activeExpireTimeMs = 14L * 24L * 60L * 60L * 1000L,
        )
        assertEquals(
            OttaiMaterialState.READY_TO_ACTIVATE,
            ottaiMaterialState(
                materials,
                recoveredStartMs = 0L,
                activatedLifetimeMs = 28L * 24L * 60L * 60L * 1000L,
            ),
        )
        assertEquals(
            OttaiMaterialState.READY_TO_ACTIVATE,
            ottaiMaterialState(
                materials,
                recoveredStartMs = 1_700_000_000_000L,
                activatedLifetimeMs = 28L * 24L * 60L * 60L * 1000L,
            ),
        )
        val started = materials.copy(activeTimeMs = 1_700_000_000_000L)
        assertEquals(
            OttaiMaterialState.ACTIVE,
            ottaiMaterialState(
                started,
                recoveredStartMs = 0L,
                activatedLifetimeMs = 28L * 24L * 60L * 60L * 1000L,
                nowMs = 1_700_000_000_000L + OttaiConstants.DEFAULT_PREHEAT_PERIOD_MS + 60_000L,
            ),
        )
    }

    private fun device(mac: String, unbindTime: Long) = OttaiCloudClient.DeviceSummary(
        mac = mac,
        serialNo = mac,
        deviceType = "cgm",
        deviceVersion = "E1.1.4(V1.7.S2530.1)",
        bindTime = 1_787_400_000_000L,
        unbindTime = unbindTime,
    )
}
