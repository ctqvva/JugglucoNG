package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsDeviceBindingTest {
    private val sensor = SibionicsRegistry.SensorRecord(
        sensorId = "SIBI:45589JJQY72",
        address = "",
        displayName = "45589JJQY72",
        variant = SibionicsConstants.Variant.SIBIONICS2,
        shortCode = "45589JJQ",
    )

    @Test
    fun defaultSensorAlgorithmKeepsCalibrationEnabled() {
        assertEquals(
            SibionicsAlgorithmSelection.STOCK_CALIBRATED,
            SibionicsAlgorithmSelection.DEFAULT,
        )
    }

    @Test
    fun recognizesObservedSibionics2TransmitterName() {
        assertTrue(SibionicsConstants.isSibionics2TransmitterName("P225043JMV"))
        assertTrue(SibionicsConstants.isSibionics2TransmitterName("P2250671014ATR89"))
        assertFalse(SibionicsConstants.isSibionics2TransmitterName("LT260346HU"))
        assertFalse(SibionicsConstants.isSibionics2TransmitterName("P22"))
    }

    @Test
    fun managedMirrorIdentityMatchesItsShortNativeAlias() {
        assertTrue(
            SibionicsRegistry.matchesNativeMirrorIdentity(
                "SIBI:P225043JMV",
                "P225043JMV",
            ),
        )
        assertTrue(
            SibionicsRegistry.matchesNativeMirrorIdentity(
                "P225043JMV",
                "SIBI:P225043JMV",
            ),
        )
        assertFalse(
            SibionicsRegistry.matchesNativeMirrorIdentity(
                "SIBI:P225043JMV",
                "46HU804EBJ4",
            ),
        )
    }

    @Test
    fun sibionics2StartsWithItsKnownV120ProtocolInsteadOfUnknown() {
        assertEquals(
            SibionicsConstants.ProtocolMode.V120,
            SibionicsConstants.initialProtocolMode(
                SibionicsConstants.Variant.SIBIONICS2,
                SibionicsConstants.ProtocolMode.UNKNOWN,
            ),
        )
        assertEquals(
            SibionicsConstants.ProtocolMode.V120,
            SibionicsConstants.initialProtocolMode(
                SibionicsConstants.Variant.SIBIONICS2,
                SibionicsConstants.ProtocolMode.CHINESE,
            ),
        )
    }

    @Test
    fun sensorQrIdentityWinsWhileBleNameIsRetainedAsAlias() {
        val qr = "\u001D0106972831641476112512161727061510LT46251211C" +
            "\u001D21P2251211237GDR75"
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = qr,
            bleName = "P225043JMV",
            variant = SibionicsConstants.Variant.SIBIONICS2,
        )

        assertFalse(identity.sensorId.equals("SIBI:P225043JMV", ignoreCase = true))
        assertFalse(identity.displayName.equals("P225043JMV", ignoreCase = true))
        assertTrue(identity.bleName.equals("P225043JMV", ignoreCase = true))
        assertTrue(identity.qrDerived)
    }

    @Test
    fun v120FramedQrMatchesOfficialIdentityWindowExactly() {
        val qr = "\u001D0106972831641476112507301726072910LT46250683C" +
            "\u001D21P2250683013AQT98"
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = qr,
            bleName = "P225043JMV",
            variant = SibionicsConstants.Variant.SIBIONICS2,
        )

        assertEquals("0683013AQT9", identity.displayName)
        assertEquals("SIBI:0683013AQT9", identity.sensorId)
        assertEquals("0683013A", identity.shortCode)
        assertEquals("P225043JMV", identity.bleName)
        assertTrue(identity.qrDerived)
    }

    @Test
    fun v120XptAi21KeepsItsIdentityAndUsesVariantFallbackSensitivity() {
        val qr = "\u001D0106972831641476112512181727061710LT46251212C" +
            "\u001D21XPT1EEX2NRU16U"
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = qr,
            bleName = "P225043JMV",
            variant = SibionicsConstants.Variant.SIBIONICS2,
        )

        assertEquals("1EEX2NRU16U", identity.displayName)
        assertEquals("SIBI:1EEX2NRU16U", identity.sensorId)
        assertEquals("1EEX2NRU", identity.shortCode)
        assertEquals(null, SibionicsSensitivity.tryDecode(identity.shortCode))
        assertEquals(
            1.44f,
            SibionicsSensitivity.sensitivityFor(
                identity.shortCode,
                SibionicsConstants.Variant.SIBIONICS2,
            ),
            0.0001f,
        )
        assertTrue(identity.qrDerived)
    }

    @Test
    fun nearbyV120LotRetainsItsOwnQrSensitivity() {
        val qr = "\u001D0106972831641476112512131727061210LT46251210C" +
            "\u001D21P225121023GGFR60"
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = qr,
            bleName = "P2251210ABC",
            variant = SibionicsConstants.Variant.SIBIONICS2,
        )

        assertEquals("121023GGFR6", identity.displayName)
        assertEquals("121023GG", identity.shortCode)
        val sensitivity = SibionicsSensitivity.tryDecode(identity.shortCode)
        assertEquals(1.39f, sensitivity!!, 0.0001f)
        assertEquals(
            1.39f,
            SibionicsSensitivity.sensitivityFor(
                identity.shortCode,
                SibionicsConstants.Variant.SIBIONICS2,
            ),
            0.0001f,
        )
    }

    @Test
    fun structuredV120SerialDoesNotChangeChineseIdentityWindow() {
        val qr = "\u001D0106972831640165112312091724120810LT41231108C" +
            "\u001D21231108GEPD802JPP76"
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = qr,
            bleName = "LT2309GEPD",
            variant = SibionicsConstants.Variant.CHINESE,
        )

        assertEquals("GEPD802JPP7", identity.displayName)
        assertEquals("GEPD802J", identity.shortCode)
        assertTrue(identity.qrDerived)
    }

    @Test
    fun acceptsObservedGs1QrPayloadsAcrossManagedVariants() {
        val payloads = listOf(
            SibionicsConstants.Variant.EU to
                "\u001D0106972831641803112412191725121810LT4F241247J\u001D21241247YEZ1450HAJ02",
            SibionicsConstants.Variant.HEMATONIX to
                "\u001D0106972831641476112412231725122210LT46241219C\u001D21WD9QAXGA52WS4V",
            SibionicsConstants.Variant.CHINESE to
                "\u001D0106972831640165112312091724120810LT41231108C\u001D21231108GEPD802JPP76",
            SibionicsConstants.Variant.SIBIONICS2 to
                "\u001D0106972831641476112512181727061710LT46251212C\u001D21XPT1EEX2NRU16U",
        )

        payloads.forEach { (variant, payload) ->
            assertTrue(SibionicsRegistry.isSupportedQrPayload(payload))
            assertTrue(SibionicsRegistry.isSupportedQrPayload(payload, variant))
        }
        assertTrue(SibionicsRegistry.isSupportedQrPayload("]d2" + payloads.last().second.drop(1)))
    }

    @Test
    fun rejectsQrPayloadsFromTheWrongSibionicsGeneration() {
        val eu = "\u001D0106972831641803112412191725121810LT4F241247J\u001D21241247YEZ1450HAJ02"
        val hematonix = "\u001D0106972831641476112412231725122210LT46241219C\u001D21WD9QAXGA52WS4V"
        val chinese = "\u001D0106972831640165112312091724120810LT41231108C\u001D21231108GEPD802JPP76"
        val v120P = "\u001D0106972831641476112507301726072910LT46250683C\u001D21P2250683013AQT98"
        val v120Xpt = "\u001D0106972831641476112512181727061710LT46251212C\u001D21XPT1EEX2NRU16U"

        listOf(eu, hematonix, chinese).forEach {
            assertFalse(SibionicsRegistry.isSupportedQrPayload(it, SibionicsConstants.Variant.SIBIONICS2))
        }
        listOf(v120P, v120Xpt).forEach { payload ->
            listOf(
                SibionicsConstants.Variant.EU,
                SibionicsConstants.Variant.HEMATONIX,
                SibionicsConstants.Variant.CHINESE,
            ).forEach { variant ->
                assertFalse(SibionicsRegistry.isSupportedQrPayload(payload, variant))
            }
            assertTrue(SibionicsRegistry.isSupportedQrPayload(payload, SibionicsConstants.Variant.SIBIONICS2))
        }
        assertFalse(SibionicsRegistry.isSupportedQrPayload(v120P, SibionicsConstants.Variant.GS3))
    }

    @Test
    fun rejectsTextAndMalformedGs1AsSensorQrPayloads() {
        assertFalse(SibionicsRegistry.isSupportedQrPayload(null))
        assertFalse(SibionicsRegistry.isSupportedQrPayload("YAICOMVK1HE1F5EE"))
        assertFalse(
            SibionicsRegistry.isSupportedQrPayload(
                "https://example.invalid/0106972831641476112512181727061710LT46251212C21XPT1EEX2NRU16U",
            ),
        )
        assertFalse(
            SibionicsRegistry.isSupportedQrPayload(
                "\u001D0106972831641475112512181727061710LT46251212C\u001D21XPT1EEX2NRU16U",
            ),
        )
        assertFalse(
            SibionicsRegistry.isSupportedQrPayload(
                "\u001D0106972831641476112512181727061710LT46251212C\u001D21",
            ),
        )
    }

    @Test
    fun manualSerialEntryIsHeldToTheSameGenerationRuleAsTheScanner() {
        val singlePieceSerials = listOf("241247YEZ1450HAJ02", "WD9QAXGA52WS4V", "231108GEPD802JPP76")
        val v120Serials = listOf("P2250683013AQT98", "XPT1EEX2NRU16U")

        singlePieceSerials.forEach { serial ->
            assertTrue(
                serial,
                SibionicsRegistry.isManualSerialForVariant(serial, SibionicsConstants.Variant.EU),
            )
            assertFalse(
                serial,
                SibionicsRegistry.isManualSerialForVariant(serial, SibionicsConstants.Variant.SIBIONICS2),
            )
        }
        v120Serials.forEach { serial ->
            assertTrue(
                serial,
                SibionicsRegistry.isManualSerialForVariant(serial, SibionicsConstants.Variant.SIBIONICS2),
            )
            listOf(
                SibionicsConstants.Variant.EU,
                SibionicsConstants.Variant.HEMATONIX,
                SibionicsConstants.Variant.CHINESE,
            ).forEach { variant ->
                assertFalse(
                    "$serial/${variant.id}",
                    SibionicsRegistry.isManualSerialForVariant(serial, variant),
                )
            }
        }
    }

    @Test
    fun manualSerialEntryStillRejectsCodesOfTheWrongLength() {
        listOf(null, "", "P22506", "P2250683013AQT98P2250683013AQT98").forEach { serial ->
            assertFalse(
                serial.orEmpty(),
                SibionicsRegistry.isManualSerialForVariant(serial, SibionicsConstants.Variant.SIBIONICS2),
            )
        }
    }

    @Test
    fun scannedPayloadsReportWhichGenerationTheyBelongTo() {
        assertEquals(
            true,
            SibionicsRegistry.qrPayloadIsV120Identity(
                "0106972831641476112507301726072910LT46250683C21P2250683013AQT98",
            ),
        )
        assertEquals(
            false,
            SibionicsRegistry.qrPayloadIsV120Identity(
                "0106972831641803112412191725121810LT4F241247J21241247YEZ1450HAJ02",
            ),
        )
        assertEquals(null, SibionicsRegistry.qrPayloadIsV120Identity("YAICOMVK1HE1F5EE"))
    }

    @Test
    fun bleOnlyIdentityRetainsTheFullAdvertisedName() {
        val identity = SibionicsRegistry.buildIdentity(
            rawInput = "HEMATONIX42",
            bleName = "HEMATONIX42",
            variant = SibionicsConstants.Variant.HEMATONIX,
        )

        assertEquals("HEMATONIX42", identity.displayName)
        assertEquals("HEMATONIX42", identity.bleName)
        assertFalse(identity.qrDerived)
    }

    @Test
    fun soleUnboundSibionics2RecordCanClaimObservedTransmitter() {
        assertTrue(
            SibionicsRegistry.canClaimUnboundSibionics2Device(
                record = sensor,
                records = listOf(sensor),
                deviceName = "P225043JMV",
                address = "C7:C7:F9:69:D8:35",
            ),
        )
    }

    @Test
    fun ambiguousUnboundRecordsCannotClaimTransmitter() {
        val other = sensor.copy(sensorId = "SIBI:OTHER", displayName = "OTHER")
        assertFalse(
            SibionicsRegistry.canClaimUnboundSibionics2Device(
                record = sensor,
                records = listOf(sensor, other),
                deviceName = "P225043JMV",
                address = "C7:C7:F9:69:D8:35",
            ),
        )
    }

    @Test
    fun addressAlreadyOwnedByAnotherRecordCannotBeClaimed() {
        val bound = sensor.copy(
            sensorId = "SIBI:BOUND",
            displayName = "BOUND",
            address = "C7:C7:F9:69:D8:35",
        )
        assertFalse(
            SibionicsRegistry.canClaimUnboundSibionics2Device(
                record = sensor,
                records = listOf(sensor, bound),
                deviceName = "P225043JMV",
                address = "C7:C7:F9:69:D8:35",
            ),
        )
    }
}
