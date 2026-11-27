package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.drivers.ManagedSensorUiFamily

class InstaraWiringTests {

    @Test
    fun familyCodeIsNineAndRoundTrips() {
        assertEquals(9, ManagedSensorUiFamily.INSTARA.nativeCode)
        assertEquals(ManagedSensorUiFamily.INSTARA, ManagedSensorUiFamily.fromNativeCode(9))
    }

    @Test
    fun kindAndSourceMappings() {
        assertEquals(
            SensorSourceResolver.SENSOR_KIND_INSTARA,
            SensorSourceResolver.kindForManagedFamily(ManagedSensorUiFamily.INSTARA),
        )
        assertEquals(
            "Instara",
            SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_INSTARA, false),
        )
    }

    @Test
    fun vendorAndTypeNameMappings() {
        assertEquals(SensorVendor.INSTARA, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.INSTARA))
        assertEquals(SensorTypeName.INSTARA_G1, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.INSTARA))
    }

    @Test
    fun identityRegistryIncludesInstaraAdapter() {
        assertTrue(ManagedSensorIdentityRegistry.all.any { it.uiFamily == ManagedSensorUiFamily.INSTARA })
    }
}
