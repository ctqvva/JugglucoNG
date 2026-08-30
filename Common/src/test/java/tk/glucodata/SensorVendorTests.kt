package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.drivers.ManagedSensorUiFamily

class SensorVendorTests {
    @Test
    fun managedFamiliesMapToTheirManufacturers() {
        assertEquals(SensorVendor.MICROTECH, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.AIDEX))
        assertEquals(SensorVendor.SINOCARE, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.ICAN))
        assertEquals(SensorVendor.GLUTEC, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.MQ))
        assertEquals(SensorVendor.YUWELL, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.ANYTIME))
        assertEquals(SensorVendor.OTTAI, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.OTTAI))
        assertEquals(SensorVendor.SIBIONICS, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.SIBIONICS))
        assertEquals(SensorVendor.UNKNOWN, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.GENERIC))
    }

    @Test
    fun nativeKindsMapToTheirManufacturers() {
        assertEquals(SensorVendor.ABBOTT, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_LIBRE2))
        assertEquals(SensorVendor.ABBOTT, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_LIBRE3))
        assertEquals(SensorVendor.SIBIONICS, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_SIBIONICS))
        assertEquals(SensorVendor.DEXCOM, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_DEXCOM))
        assertEquals(SensorVendor.ROCHE, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_ACCUCHEK))
        assertEquals(SensorVendor.MICROTECH, SensorVendor.fromNativeKind(SensorSourceResolver.SENSOR_KIND_AIDEX))
    }

    @Test
    fun legacyAiDexStreamAndUnknownKindsHaveExplicitHandling() {
        assertEquals(SensorVendor.MICROTECH, SensorVendor.fromNativeKind(0x100))
        assertEquals(SensorVendor.UNKNOWN, SensorVendor.fromNativeKind(-1))
        assertEquals(SensorVendor.UNKNOWN, SensorVendor.fromNativeKind(0))
    }
}
