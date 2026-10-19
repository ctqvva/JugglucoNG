package tk.glucodata.drivers.sibionics

import org.junit.Assert.*
import org.junit.Test

class SibionicsProbeSensitivityTest {
    private val variant = SibionicsConstants.Variant.SIBIONICS2

    @Test
    fun matchesOriginalNativeBinaryAcrossSupportedSensitivities() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/sibionics/probe-sensitivity-native.tsv"))
        fixture.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split('\t')
                assertEquals(fields[0], fields[1].toInt() / 100f,
                    checkNotNull(SibionicsProbeSensitivity.tryDecode(fields[0])), 0.00001f)
            }
        }
    }

    @Test
    fun observedProbeCodesDecodeWithoutPrintedSerials() {
        assertEquals(1.73f, SibionicsProbeSensitivity.tryDecode("EU2VCZUQPSHD5Q")!!, 0.00001f)
        assertEquals(1.75f, SibionicsProbeSensitivity.tryDecode("145TUMXYK4S46V")!!, 0.00001f)
        assertEquals(1.26f, SibionicsProbeSensitivity.tryDecode("XPT1EEX2NRU16U")!!, 0.00001f)
    }

    @Test
    fun probeCalibrationPrecedesTheVariantFallback() {
        assertEquals(1.73f, SibionicsSensitivity.sensitivityFor("VCZUQPSH", variant, "EU2VCZUQPSHD5Q"), 0.00001f)
        assertEquals(1.75f, SibionicsSensitivity.sensitivityFor("TUMXYK4S", variant, "145TUMXYK4S46V"), 0.00001f)
    }

    @Test
    fun legacyAndUnrecognizedCodesKeepTheirExistingCalibration() {
        val shortCode = "0683013A"
        assertEquals(SibionicsSensitivity.tryDecode(shortCode)!!,
            SibionicsSensitivity.sensitivityFor(shortCode, variant, "invalid"), 0.00001f)
        assertEquals(1.44f, SibionicsSensitivity.sensitivityFor("VCZUQPSH", variant), 0.00001f)
        val eu = SibionicsConstants.Variant.EU
        assertEquals(SibionicsSensitivity.sensitivityFor(shortCode, eu),
            SibionicsSensitivity.sensitivityFor(shortCode, eu, "EU2VCZUQPSHD5Q"), 0.00001f)
    }

    @Test
    fun malformedTokensAreRejected() {
        for (code in listOf(null, "", "EU2VCZUQPSHD5", "EU2VCZUQPSHD5QQ", "eu2vczuqpshd5q",
            "EU2VCZUQPSHD50", "EU2VCZUQPSHD5I", "P2260201675KKW78", "J45TUMXYK4S46V", "EU2VYYTQPSHD69", "145TGCFYK4S46Q")) {
            assertNull(code, SibionicsProbeSensitivity.tryDecode(code))
        }
    }

    @Test
    fun everySingleCharacterCorruptionOfObservedCodesIsRejected() {
        val alphabet = "123456789ACDEFGHJKLMNPQRSTUVWXYZ"
        for (code in listOf("EU2VCZUQPSHD5Q", "145TUMXYK4S46V")) {
            code.indices.forEach { index ->
                alphabet.filter { it != code[index] }.forEach { replacement ->
                    val corrupt = code.replaceRange(index, index + 1, replacement.toString())
                    assertNull(corrupt, SibionicsProbeSensitivity.tryDecode(corrupt))
                }
            }
        }
    }

    @Test
    fun framedAndSeparatorStrippedLabelsRetainProbeAndSensorIdentity() {
        for ((qr, probe, id) in listOf(
            Triple("\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q", "EU2VCZUQPSHD5Q", "SIBI:VCZUQPSHD5Q"),
            Triple("\u001D0106972831641476112507151726071410LT46250651C\u001D21145TUMXYK4S46V", "145TUMXYK4S46V", "SIBI:TUMXYK4S46V"),
        )) {
            for (payload in listOf(qr, qr.replace("\u001D", ""), "]d2" + qr.lowercase())) {
                val identity = SibionicsRegistry.buildIdentity(payload, null, variant)
                assertEquals(probe, identity.probeCode)
                assertEquals(id, identity.sensorId)
            }
            assertEquals("", SibionicsRegistry.buildIdentity(qr, null, SibionicsConstants.Variant.EU).probeCode)
            assertEquals("", SibionicsRegistry.buildIdentity(qr.replace("1476", "1477"), null, variant).probeCode)
        }
    }
}
