package tk.glucodata.ui

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayQrTurnConfigTests {
    @Test
    fun mirrorQrUsesMediumErrorCorrection() {
        assertEquals(
            ErrorCorrectionLevel.M,
            MIRROR_QR_ENCODE_HINTS[EncodeHintType.ERROR_CORRECTION]
        )
    }

    @Test
    fun hybridQrParsesItsTurnConfiguration() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","turn":["turn.example.test",3478,"clone","s3cret"]} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrTurnConfig("turn.example.test", 3478, "clone", "s3cret"),
            parseHybridQrTurnConfig(json)
        )
    }

    @Test
    fun objectTurnConfigurationFromEarlierHybridBuildRemainsValid() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","turn":{"host":"turn.example.test","port":3478,"username":"clone","password":"s3cret"}} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrTurnConfig("turn.example.test", 3478, "clone", "s3cret"),
            parseHybridQrTurnConfig(json)
        )
    }

    @Test
    fun olderIceQrWithoutTurnConfigurationRemainsValid() {
        val payload = """{"ICElabel":"pair","side":true} MirrorJuggluco"""
        val json = parseMirrorQrJson(payload)

        assertNull(parseHybridQrTurnConfig(json))
        assertNull(parseHybridQrIceConfig(json, null))
        assertFalse(mirrorQrContainsTurnConfig(payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun turnConfigurationRejectsInvalidPort() {
        parseHybridQrTurnConfig(
            parseMirrorQrJson(
                """{"ICElabel":"pair","turn":{"host":"turn.example.test","port":70000}} MirrorJuggluco"""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localQrCannotReplaceTurnConfiguration() {
        parseHybridQrTurnConfig(
            parseMirrorQrJson(
                """{"names":["192.0.2.1"],"turn":{"host":"turn.example.test","port":3478}} MirrorJuggluco"""
            )
        )
    }

    @Test
    fun turnConfigurationIsVisibleBeforeImport() {
        assertTrue(
            mirrorQrContainsTurnConfig(
                """{"ICElabel":"pair","turn":["turn.example.test",3478,"",""]} MirrorJuggluco"""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun compactTurnConfigurationRequiresAllFourValues() {
        parseHybridQrTurnConfig(
            parseMirrorQrJson(
                """{"ICElabel":"pair","turn":["turn.example.test",3478]} MirrorJuggluco"""
            )
        )
    }

    @Test
    fun hybridQrReusesTurnEndpointForStunAndRendezvous() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","turn":["turn.example.test",3478,"clone","s3cret"],"stun":true,"rv":6789} MirrorJuggluco"""
        )
        val turn = parseHybridQrTurnConfig(json)

        assertEquals(
            HybridQrIceConfig(true, "turn.example.test", 6789, true, true),
            parseHybridQrIceConfig(json, turn),
        )
    }

    @Test
    fun hybridQrCarriesAnExplicitRendezvousEndpoint() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","turn":["turn.example.test",3478,"clone","s3cret"],"stun":false,"rv":["connect.example.test",6789]} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrIceConfig(false, "connect.example.test", 6789, true, true),
            parseHybridQrIceConfig(json, parseHybridQrTurnConfig(json)),
        )
    }

    @Test
    fun hybridQrCanExplicitlySelectTheAppDefaults() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":0} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrIceConfig(false, "", 6789, true, true),
            parseHybridQrIceConfig(json, null),
        )
    }

    @Test
    fun hybridQrCanCarryTrustedSelfSignedCertificateMode() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":["connect.example.test",6789],"cv":false} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrIceConfig(false, "connect.example.test", 6789, false, true),
            parseHybridQrIceConfig(json, null),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun certificateVerificationChoiceMustBeBoolean() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":["connect.example.test",6789],"cv":"false"} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }

    @Test
    fun hybridQrCanDisableLocalDiscovery() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":0,"ld":false} MirrorJuggluco"""
        )

        assertEquals(
            HybridQrIceConfig(false, "", 6789, true, false),
            parseHybridQrIceConfig(json, null),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localDiscoveryChoiceMustBeBoolean() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":0,"ld":"false"} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun turnForStunRequiresTurnCredentialsInTheQr() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":true,"rv":0} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun compactRendezvousRequiresTurnConfiguration() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":6789} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun iceNetworkConfigurationMustBeComplete() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun explicitRendezvousPortMustBeNumeric() {
        val json = parseMirrorQrJson(
            """{"ICElabel":"pair","stun":false,"rv":["connect.example.test","6789"]} MirrorJuggluco"""
        )

        parseHybridQrIceConfig(json, null)
    }
}
