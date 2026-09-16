package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthRegistryTests {

    private val record = ICanHealthRegistry.SensorRecord(
        sensorId = "0123456789ABCDEF",
        address = "AA:BB:CC:DD:EE:FF",
        displayName = "Sensor",
    )

    @Test
    fun nativeAliasComesFromConstants() {
        assertEquals("56789ABCDEF", record.nativeAlias)
        assertNull(record.copy(sensorId = "ICN-PENDING").nativeAlias)
    }

    @Test
    fun matchesCanonicalNativeAndLegacyAliases() {
        assertTrue(record.matchesId("0123456789ABCDEF"))
        assertTrue(record.matchesId("56789ABCDEF"))
        assertTrue(record.matchesId("ABCDEF"))
        assertTrue(record.matchesId("0123456789abcdef"))
        assertFalse(record.matchesId("UNRELATED"))
        assertFalse(record.matchesId(null))
        assertFalse(record.matchesId(""))
    }
}
