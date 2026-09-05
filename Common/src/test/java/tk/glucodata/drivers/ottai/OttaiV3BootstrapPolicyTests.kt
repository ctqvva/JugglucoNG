package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test

class OttaiV3BootstrapPolicyTests {
    @Test
    fun `stored credentials always use normal auth`() {
        assertEquals(
            OttaiAuthEntryMode.STORED_MATERIAL_AUTH,
            ottaiAuthEntryMode(
                hasAuthKeys = true,
                bootstrapPending = true,
                cnSessionAvailable = true,
                validatedDeviceVersion = "E1.1.4(V1.7.S2530.1)",
            ),
        )
    }

    @Test
    fun `fresh validated sensor enters V3 credential bootstrap`() {
        assertEquals(
            OttaiAuthEntryMode.V3_CREDENTIAL_BOOTSTRAP,
            ottaiAuthEntryMode(
                hasAuthKeys = false,
                bootstrapPending = true,
                cnSessionAvailable = true,
                validatedDeviceVersion = "E1.1.4(V1.7.S2530.1)",
            ),
        )
    }

    @Test
    fun `missing authorization context is blocked`() {
        listOf(
            ottaiAuthEntryMode(false, false, true, "E1.1.4(V1.7.S2530.1)"),
            ottaiAuthEntryMode(false, true, false, "E1.1.4(V1.7.S2530.1)"),
            ottaiAuthEntryMode(false, true, true, ""),
        ).forEach { mode ->
            assertEquals(OttaiAuthEntryMode.BLOCKED, mode)
        }
    }
}
