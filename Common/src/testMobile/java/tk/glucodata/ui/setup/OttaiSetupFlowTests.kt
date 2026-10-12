package tk.glucodata.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import tk.glucodata.drivers.ottai.OttaiCloudClient

class OttaiSetupFlowTests {
    @Test
    fun `expired account session returns account list to sign in`() {
        assertEquals(
            OttaiSetupStep.SENSOR,
            ottaiSetupStepAfterSessionInvalidation(OttaiSetupStep.ACCOUNT_SENSORS),
        )
    }

    @Test
    fun `late account refresh rejection preserves connection completion steps`() {
        // The saved-sensor refresh began in the picker, but can return after Connect has
        // advanced. Decide from the step at rejection time so its completion effect survives.
        listOf(OttaiSetupStep.CONNECTING, OttaiSetupStep.SUCCESS).forEach { currentStep ->
            assertEquals(currentStep, ottaiSetupStepAfterSessionInvalidation(currentStep))
        }
    }

    @Test
    fun `session rejection leaves sensor and registration pages in place`() {
        listOf(OttaiSetupStep.SENSOR, OttaiSetupStep.REGISTER).forEach { currentStep ->
            assertEquals(currentStep, ottaiSetupStepAfterSessionInvalidation(currentStep))
        }
    }

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
    fun `current binding absent from history is offered for unbind while replacement stays selected`() {
        val snapshot = OttaiCloudClient.accountDevicesResult(
            OttaiCloudClient.CloudRequestResult(JSONObject("""{"data":{"items":[]}}"""), null),
            OttaiCloudClient.CloudRequestResult(
                JSONObject("""{"data":{"cgmDeviceRespVO":{"mac":"70D07E2552DB"}}}"""), null,
            ),
        )
        assertEquals("70D07E2552DB", ottaiActiveCloudUnbindTarget("70D07E2552DB", snapshot.devices)?.mac)
        assertNull(ottaiActiveCloudUnbindTarget("6CA04230E260", snapshot.devices))
    }

    @Test
    fun `successful unbind clears authoritative binding as well as historical timestamp`() {
        val bound = device("70D07E2552DB", unbindTime = 123L).copy(boundToAccount = true)
        assertEquals(bound, ottaiActiveCloudUnbindTarget(bound.mac, listOf(bound)))
        val released = bound.copy(unbindTime = 456L, boundToAccount = false)
        assertNull(ottaiActiveCloudUnbindTarget(bound.mac, listOf(released)))
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

    private fun device(mac: String, unbindTime: Long) = OttaiCloudClient.DeviceSummary(
        mac = mac,
        serialNo = mac,
        deviceType = "cgm",
        deviceVersion = "E1.1.4(V1.7.S2530.1)",
        bindTime = 1_787_400_000_000L,
        unbindTime = unbindTime,
    )
}
