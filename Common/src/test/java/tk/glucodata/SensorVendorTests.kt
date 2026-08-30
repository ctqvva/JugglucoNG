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
        assertEquals(SensorVendor.NIGHTSCOUT, SensorVendor.fromManagedFamily(ManagedSensorUiFamily.NIGHTSCOUT))
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

    @Test
    fun nativeKindsMapToConcreteSensorTypes() {
        assertEquals(SensorTypeName.LIBRE_2, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_LIBRE2))
        assertEquals(SensorTypeName.LIBRE_3, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_LIBRE3))
        assertEquals(SensorTypeName.SIBIONICS_GS1, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_SIBIONICS))
        assertEquals(SensorTypeName.SIBIONICS_2, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_SIBIONICS, isSibionics2 = true))
        assertEquals(SensorTypeName.DEXCOM_G7, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_DEXCOM))
        assertEquals(SensorTypeName.ACCUCHEK_SMARTGUIDE, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_ACCUCHEK))
        assertEquals(SensorTypeName.AIDEX_LINX, SensorTypeName.fromNativeKind(SensorSourceResolver.SENSOR_KIND_AIDEX))
        assertEquals(SensorTypeName.AIDEX_LINX, SensorTypeName.fromNativeKind(0x100))
        assertEquals(SensorTypeName.UNKNOWN, SensorTypeName.fromNativeKind(-1))
    }

    @Test
    fun managedFamiliesMapToConcreteSensorTypes() {
        assertEquals(SensorTypeName.AIDEX_LINX, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.AIDEX))
        assertEquals(SensorTypeName.ICAN_I3, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.ICAN))
        assertEquals(SensorTypeName.MQ, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.MQ))
        assertEquals(SensorTypeName.ANYTIME, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.ANYTIME))
        assertEquals(SensorTypeName.OTTAI_CGM, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.OTTAI))
        assertEquals(SensorTypeName.SIBIONICS_GS1, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.SIBIONICS))
        assertEquals(SensorTypeName.SIBIONICS_2, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.SIBIONICS, "Sibionics 2"))
        assertEquals(SensorTypeName.SIBIONICS_GS3, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.SIBIONICS, "Sibionics GS3"))
        assertEquals(SensorTypeName.NIGHTSCOUT, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.NIGHTSCOUT))
        assertEquals(SensorTypeName.UNKNOWN, SensorTypeName.fromManagedFamily(ManagedSensorUiFamily.GENERIC))
    }
}
