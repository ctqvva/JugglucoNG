package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayQrTurnConfigTests {
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

        assertNull(parseHybridQrTurnConfig(parseMirrorQrJson(payload)))
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
}
