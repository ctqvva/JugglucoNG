package tk.glucodata.drivers.libre3

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity provisioning checks and NFC/data-plane regressions from review. Synthetic keys only. */
class Libre3ProvisioningTest {
    private fun h(s: String) = Libre3Bytes.parseHex(s)!!

    // RoundWhiteDiscKit's synthetic test identity (scalar 0x4444), not a real credential.
    private val certHex = "03000102030405060708090a0b0c0d0e0f1000005f92a79201000000000000000004a85b3aad6f3a346d85523141cb434e1caf4c642b2b3cc952cb07a635cb6a1df1bcbdadb66bae8eb25eca92f8b08b67be02cc736150dd7ffcd3f019c875f9ffde" + "0".repeat(128)
    private val json = """{"format":1,"curve":"P-256","identities":[{"label":"test","privateKeyHex":"${"0".repeat(60)}4444","certificateHex":"$certHex"}]}"""
    private val bytes = json.toByteArray()

    @Test fun sourceSpecParsesOnlyPinnedHttpsSources() {
        val digest = "a".repeat(64)
        val s = Libre3IdentitySource.parse("app-identities-1|$digest|https://a.example/x https://b.example/x")!!
        assertEquals(listOf("https://a.example/x", "https://b.example/x"), s.urls)
        assertEquals(s, Libre3IdentitySource.parse(s.toSpec()))
        assertNull(Libre3IdentitySource.parse(""))
        assertNull(Libre3IdentitySource.parse("v|$digest|http://plain.example/x"))
        assertNull(Libre3IdentitySource.parse("v|abc|https://a.example/x"))
        assertNull(Libre3IdentitySource.parse("bad version|$digest|https://a.example/x"))
        assertEquals("custom-aaaaaaaaaaaa", Libre3IdentitySource.custom("https://a.example/x", digest.uppercase())!!.version)
    }

    @Test fun digestIsCheckedBeforeParsing() {
        val sha = Libre3IdentityProvisioning.sha256Hex(bytes)
        assertEquals("test", Libre3IdentityProvisioning.validate(bytes, sha).single().label)
        val wrong = runCatching { Libre3IdentityProvisioning.validate(bytes, "0".repeat(64)) }
        assertTrue(wrong.exceptionOrNull() is Libre3IdentityProvisioning.ProvisioningException)
    }

    @Test fun fetchSkipsMirrorsServingOtherBytes() {
        val sha = Libre3IdentityProvisioning.sha256Hex(bytes)
        val source = Libre3IdentitySource("v1", sha, listOf("https://bad.example/f", "https://down.example/f", "https://good.example/f"))
        val served = Libre3IdentityProvisioning.fetch(source) { url ->
            when {
                url.contains("bad") -> "{}".toByteArray()
                url.contains("down") -> error("offline")
                else -> bytes
            }
        }
        assertTrue(served.contentEquals(bytes))
        val none = runCatching { Libre3IdentityProvisioning.fetch(source.copy(urls = listOf("https://bad.example/f"))) { "{}".toByteArray() } }
        assertTrue(none.isFailure)
    }

    @Test fun boundedReadRefusesOversizedInput() {
        assertEquals(10, Libre3IdentityProvisioning.readBounded(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertTrue(runCatching { Libre3IdentityProvisioning.readBounded(ByteArrayInputStream(ByteArray(11)), 10) }.isFailure)
    }

    @Test fun nfcRejectsImplausibleMetadataLikeLegacy() {
        // warmup byte 0 and wear <= warmup are refused, as NfcMetadata::valid does.
        assertNull(Libre3Nfc.parsePatchInfo(h("00A500010001000000C04E1E0D01010400043036385A4D524631382F97")))
        assertNull(Libre3Nfc.parsePatchInfo(h("00A50001000100000032001E0D0101040C043036385A4D524631382F97")))
        assertNotNull(Libre3Nfc.parsePatchInfo(h("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97")))
        // Nonzero but implausible activation time: malformed. Zero: falls back to now, flagged.
        val bad = Libre3Nfc.parseActivationResponse(h("00A500111DB71932189A948F6E01000000A2FA"), 1_700_000_000L)
        assertTrue(bad is Libre3Nfc.ActivationResult.Malformed)
        val zero = Libre3Nfc.parseActivationResponse(h("00A500111DB71932189A948F6E00000000A2FA"), 1_700_000_000L)
            as Libre3Nfc.ActivationResult.Success
        assertEquals(1_700_000_000L, zero.activationTimeSeconds)
        assertTrue(!zero.reportedActivationTime)
    }

    @Test fun frameSealedForAnotherChannelIsRejected() {
        val s = Libre3DataPlaneSession(h("7c536d2ec3c76c741a776de85596959d"), h("00000000bcc696a2"))
        val status = h("4e6fe9573b6365476193e9c42009acd20100")
        assertNotNull(s.decrypt(Libre3Channel.PATCH_STATUS, status))
        assertNull(s.decrypt(Libre3Channel.CLINICAL_DATA, status))
    }

    @Test fun hexParserRejectsNonAsciiDigits() {
        assertNull(Libre3Bytes.parseHex("０１"))
        assertNotNull(Libre3Bytes.parseHex("0a:0B"))
    }
}
