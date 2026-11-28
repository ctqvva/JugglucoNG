package tk.glucodata.drivers.caresens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CareSensAirProtocolTests {
    @Test
    fun canonicalQrPreservesLegacyIdentityAndPairingFields() {
        val qr = checkNotNull(CareSensAirQr.parse(QR))
        assertEquals("08806712000000", qr.gtin)
        assertEquals("291231", qr.expiry)
        assertEquals("SYNTHETIC123", qr.serial)
        assertEquals("2912SYNTHETIC123", qr.sensorId)
        assertEquals("ABCD0123456789EF", qr.sensorCode)
        val pin = "007ABC".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(pin, qr.pairingPin())
        qr.pairingPin().fill(0)
        assertArrayEquals(pin, qr.pairingPin())

        val shortScan = checkNotNull(CareSensAirQr.parse(QR.replace(qr.gtin, "00000000000000")))
        assertEquals(qr.sensorId, shortScan.sensorId)
        assertArrayEquals(pin, shortScan.pairingPin())
    }

    @Test
    fun qrRejectsMalformedFramingAndFields() {
        for (invalid in listOf(
            QR.drop(1), QR + "0",
            QR.replace("17" + "291231", "18" + "291231"),
            QR.replace("\u001d240", " 240"), QR.replace("\u001d250", "\u001d251"),
            QR.replace("08806712000000", "A8806712000000"),
            QR.replace("291231", "29-231"),
            QR.replace("SYNTHETIC123", "SYNTHETICé23"),
            QR.replace("007ABC", "007 BC"),
            QR.replace("ABCD0123456789EF", "\u0000BCD0123456789EF"),
        )) assertNull(CareSensAirQr.parse(invalid))
    }

    @Test
    fun versionComparisonsUseNumericComponents() {
        assertTrue(version("1.10") > CareSensAirVersion.V1_5)
        assertTrue(version("1.3.9") < CareSensAirVersion.V1_4)
        assertEquals(0, version("1.5.0.0").compareTo(CareSensAirVersion.V1_5))
        assertTrue(version("500.300.1.10") > version("500.300.1.9"))
        for (invalid in listOf("", "1.", ".5", "1..5", "1. 5", "1.+5", "1.5-beta", "1.2147483648")) {
            assertNull(CareSensAirVersion.parse(invalid))
        }
    }

    @Test
    fun authenticationMatchesLegacyPayloadAndIndependentAesVectors() {
        assertArrayEquals(
            wireBytes("c001102700000000010000"),
            CareSensAirProtocol.authentication(version("1.3"), "SYNTHETIC123"),
        )
        // Synthetic transmitter serials. Expected AES-256-CBC bytes generated with OpenSSL.
        assertArrayEquals(
            wireBytes("c001bb64c411b1e7681a23bf540c154ae6ad"),
            CareSensAirProtocol.authentication(version("1.4"), "SYNTHETIC123"),
        )
        assertArrayEquals(
            wireBytes("c00170bbb73cf89f6f48cdec6f0cb31eeb42"),
            CareSensAirProtocol.authentication(version("1.10"), "SYNTHETIC-TX-000123"),
        )
        assertNull(CareSensAirProtocol.authentication(version("1.4"), "12345"))
        assertNull(CareSensAirProtocol.authentication(version("1.4"), "SYNTHETICé23"))
    }

    @Test
    fun commandFramesPreserveOpcodesAndUnsignedLittleEndianValues() {
        assertArrayEquals(wireBytes("c00200000000"), CareSensAirProtocol.requestStartInfo())
        assertArrayEquals(wireBytes("c201"), CareSensAirProtocol.requestFactoryInfo())
        assertArrayEquals(wireBytes("c30298badcfe"), CareSensAirProtocol.syncTime(0xFEDCBA98L))
        assertArrayEquals(wireBytes("c401ffffffff"), CareSensAirProtocol.requestRecords(0xFFFFFFFFL))
        assertArrayEquals(wireBytes("c501"), CareSensAirProtocol.requestRecordCount())
        assertThrows(IllegalArgumentException::class.java) { CareSensAirProtocol.requestRecords(-1) }
        assertThrows(IllegalArgumentException::class.java) { CareSensAirProtocol.syncTime(0x100000000L) }
    }

    @Test
    fun registrationRequiresZeroStatusAndMatchingAppId() {
        val prefix = wireBytes("c0036373616972") + ByteArray(27)
        assertArrayEquals(prefix + 1.toByte(), CareSensAirProtocol.registerApp(unusedSensor = true))
        val frame = CareSensAirProtocol.registerApp(unusedSensor = false)
        assertArrayEquals(prefix + 0.toByte(), frame)
        assertTrue(checkNotNull(CareSensAirProtocol.parseRegistration(frame)).accepted)
        frame[2] = 'x'.code.toByte()
        assertFalse(checkNotNull(CareSensAirProtocol.parseRegistration(frame)).accepted)
        frame[2] = 'c'.code.toByte()
        frame[34] = 0xFF.toByte()
        val failure = checkNotNull(CareSensAirProtocol.parseRegistration(frame))
        assertFalse(failure.accepted)
        assertEquals(255, failure.statusCode)
        assertNull(CareSensAirProtocol.parseRegistration(frame.copyOf(34)))
    }

    @Test
    fun applicationInfoReadsUnsignedFieldsAndKeepsMissingChecksUnknown() {
        val base = wireBytes("c00198badcfeffffffff1e50c3")
        val expected = CareSensAirProtocol.ApplicationInfo(0xFEDCBA98L, 0xFFFFFFFFL, 30, 50000, null, null)
        assertEquals(expected, CareSensAirProtocol.parseApplicationInfo(base, version("1.5")))
        val modern = base.copyOf(32).apply {
            this[30] = 16
            this[31] = 0
        }
        assertEquals(expected, CareSensAirProtocol.parseApplicationInfo(modern, version("1.4")))
        assertEquals(
            expected.copy(qcResult = 16, keyCheckResult = 0),
            CareSensAirProtocol.parseApplicationInfo(modern, version("1.10")),
        )
        assertNull(CareSensAirProtocol.parseApplicationInfo(modern.copyOf(31), version("1.5")))
        assertNull(CareSensAirProtocol.parseApplicationInfo(base.copyOf(12), version("1.4")))
    }

    @Test
    fun startInfoUsesFirmwareSpecificNumberEncoding() {
        val expected = CareSensAirProtocol.StartInfo(0.125f, 1.5f, 0xFEDCBA98L)
        val fixed = wireBytes("c002d0121300c0e1e40098badcfe")
        val floats = wireBytes("c0020000003e0000c03f98badcfe")
        assertEquals(expected, CareSensAirProtocol.parseStartInfo(fixed, version("1.2")))
        assertEquals(expected, CareSensAirProtocol.parseStartInfo(floats, version("1.3")))
        assertNull(CareSensAirProtocol.parseStartInfo(floats.copyOf(13), version("1.3")))
        assertNull(CareSensAirProtocol.parseStartInfo(wireBytes("c0020000c07f0000c03f98badcfe"), version("1.3")))
    }

    @Test
    fun recordCountAndMeasurementUseDistinctFrames() {
        assertEquals(65535, CareSensAirProtocol.parseRecordCount(wireBytes("c401ffff")))
        assertEquals(0, CareSensAirProtocol.parseRecordCount(wireBytes("c40100001234")))
        assertNull(CareSensAirProtocol.parseRecordCount(wireBytes("c401ff")))
        assertNull(CareSensAirProtocol.parseRecordCount(wireBytes("c5010100")))

        val frame = wireBytes(
            "c501feffefcdab89ffffffff0100008098badcfe01ff5b0e" +
                "00000100020003000400050006000700080009000a000b000c000d000e000f00" +
                "10001100120013001400150016001700180019001a001b001c00ffff",
        )
        val reading = checkNotNull(CareSensAirProtocol.parseRecord(frame))
        assertEquals(-2, reading.deviceError)
        assertEquals(255, reading.rCount)
        assertEquals(0x89ABCDEFL, reading.aCount)
        assertEquals(0xFFFFFFFFL, reading.misc)
        assertEquals(0x80000001L, reading.sequence)
        assertEquals(0xFEDCBA98L, reading.sensorTimestampSeconds)
        assertEquals(0xFF01, reading.batteryRaw)
        assertEquals(36.75, reading.temperatureCelsius, 0.0)
        val samples = (0..28).toList().toIntArray() + 65535
        assertArrayEquals(samples, reading.adcSamples())
        assertNull(CareSensAirProtocol.parseRecordCount(frame))
        assertNull(CareSensAirProtocol.parseRecord(frame.copyOf(83)))
        assertNull(CareSensAirProtocol.parseRecord(frame.copyOf(85)))
        frame[1] = 2
        assertNull(CareSensAirProtocol.parseRecord(frame))
        frame[1] = 1
        frame[0] = 0xC4.toByte()
        assertNull(CareSensAirProtocol.parseRecord(frame))
        frame.fill(0)
        reading.adcSamples().fill(0)
        assertArrayEquals(samples, reading.adcSamples())
    }

    private fun version(value: String): CareSensAirVersion = checkNotNull(CareSensAirVersion.parse(value))

    private companion object {
        const val QR = "\u001d01088067120000001729123121SYNTHETIC123\u001d240007ABC\u001d250ABCD0123456789EF"
    }
}

internal fun wireBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
