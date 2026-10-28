package tk.glucodata.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.ui.components.CardPosition

class OttaiSetupFlowTests {
    @Test
    fun `stored session region follows its backend`() {
        assertEquals(OttaiRegion.CN, ottaiRegionForApiBase(OttaiConstants.API_BASE))
        assertEquals(OttaiRegion.GLOBAL, ottaiRegionForApiBase(OttaiConstants.API_BASE_GLOBAL))
        // A Syai session used to be presented as Global.
        assertEquals(OttaiRegion.SYAI, ottaiRegionForApiBase(OttaiConstants.API_BASE_SYAI))
    }

    @Test
    fun `list rows take grouped card positions`() {
        assertEquals(CardPosition.SINGLE, ottaiRowPosition(0, 1))
        assertEquals(CardPosition.TOP, ottaiRowPosition(0, 3))
        assertEquals(CardPosition.MIDDLE, ottaiRowPosition(1, 3))
        assertEquals(CardPosition.BOTTOM, ottaiRowPosition(2, 3))
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
    fun `binding no managed sensor accounts for is surfaced once`() {
        val stale = device("54DCE9C96891", unbindTime = 0L)
        val inUse = device("70D07E2552DB", unbindTime = 0L)
        val past = device("18690ADED9B3", unbindTime = 1_787_500_000_000L)
        val devices = listOf(stale, inUse, past, stale.copy(mac = "54:DC:E9:C9:68:91"))

        assertEquals(listOf(stale), ottaiUnusedCloudBindings(devices, listOf("70D07E2552DB")))
        assertEquals(emptyList<Any>(), ottaiUnusedCloudBindings(devices, listOf("54DCE9C96891", "70D07E2552DB")))
        assertEquals(emptyList<Any>(), ottaiUnusedCloudBindings(null, emptyList()))
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
