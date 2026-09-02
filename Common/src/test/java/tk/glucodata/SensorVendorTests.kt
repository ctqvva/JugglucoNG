package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun everyRecognisedVendorHasABadgeBrandLine() {
        val brands = SensorVendor.entries
            .filter { it != SensorVendor.UNKNOWN }
            .map { sensorBadge(it, SensorTypeName.UNKNOWN).brand }
        assertTrue("blank brand line", brands.none { it.isBlank() })
        // Only a glyph is left when there is nothing to name.
        assertEquals("", sensorBadge(SensorVendor.UNKNOWN, SensorTypeName.UNKNOWN).brand)
    }

    @Test
    fun badgeModelLineComesFromTheDeviceNotTheFamilyDefault() {
        // The family default is i3; an i6 must not inherit it.
        assertEquals(
            SensorBadge("ICAN", "I6"),
            sensorBadge(SensorVendor.SINOCARE, SensorTypeName.ICAN_I3, "iCan i6"),
        )
        assertEquals(
            SensorBadge("ANYTIME", "CT5"),
            sensorBadge(SensorVendor.YUWELL, SensorTypeName.ANYTIME, "CT5"),
        )
        assertEquals(
            SensorBadge("AIDEX", "GX-01S"),
            sensorBadge(SensorVendor.MICROTECH, SensorTypeName.AIDEX_LINX, "GX-01S"),
        )
    }

    @Test
    fun shortBrandAndModelPairsStayOnOneLine() {
        assertEquals("SIBI 2", sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_2, "Sibionics 2").inlineText)
        assertTrue(!sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_2, "Sibionics 2").stacked)
        assertTrue(!sensorBadge(SensorVendor.ABBOTT, SensorTypeName.LIBRE_3).stacked)
        assertTrue(!sensorBadge(SensorVendor.DEXCOM, SensorTypeName.DEXCOM_G7).stacked)
        assertTrue(!sensorBadge(SensorVendor.SINOCARE, SensorTypeName.ICAN_I3, "iCan i6").stacked)
        // Long pairs still split across two rows.
        assertTrue(sensorBadge(SensorVendor.YUWELL, SensorTypeName.ANYTIME, "CT5").stacked)
        assertTrue(sensorBadge(SensorVendor.ROCHE, SensorTypeName.ACCUCHEK_SMARTGUIDE).stacked)
        assertTrue(sensorBadge(SensorVendor.NIGHTSCOUT, SensorTypeName.NIGHTSCOUT).stacked)
        // A lone brand has nothing to stack with.
        assertTrue(!sensorBadge(SensorVendor.OTTAI, SensorTypeName.OTTAI_CGM, "Ottai CGM").stacked)
    }

    @Test
    fun modelsThatNameNothingCollapseTheBadgeToOneLine() {
        // "Ottai CGM" says Ottai; "CGM" is not a model.
        assertEquals(SensorBadge("OTTAI", ""), sensorBadge(SensorVendor.OTTAI, SensorTypeName.OTTAI_CGM, "Ottai CGM"))
        assertEquals(SensorBadge("GLUTEC", "MQ"), sensorBadge(SensorVendor.GLUTEC, SensorTypeName.MQ, "Glutec CGM"))
    }

    @Test
    fun unreportedOrOversizedModelsLeaveTheSecondLineBlank() {
        assertEquals("", sensorBadge(SensorVendor.SINOCARE, SensorTypeName.ICAN_I3, "").model)
        assertEquals("", sensorBadge(SensorVendor.MICROTECH, SensorTypeName.AIDEX_LINX, "").model)
        assertEquals("", sensorBadge(SensorVendor.YUWELL, SensorTypeName.ANYTIME, "Unknown").model)
        // "CT3-Ultrasonic" reduces to its family rather than being cut mid-word.
        assertEquals(
            "CT3",
            sensorBadge(SensorVendor.YUWELL, SensorTypeName.ANYTIME, "CT3-Ultrasonic").model,
        )
    }

    @Test
    fun badgeUsesTheSibionicsNameTheWizardAndModelRowUse() {
        assertEquals(
            SensorBadge("SIBI", "2"),
            sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_2, "Sibionics 2"),
        )
        assertEquals(
            SensorBadge("SIBI", "GS3"),
            sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_GS3, "Sibionics GS3"),
        )
        assertEquals(
            SensorBadge("SIBI", "EU"),
            sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_GS1, "Sibionics EU"),
        )
        // Natively decoded Sibionics report no model string of their own.
        assertEquals("2", sensorBadge(SensorVendor.SIBIONICS, SensorTypeName.SIBIONICS_2).model)
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
