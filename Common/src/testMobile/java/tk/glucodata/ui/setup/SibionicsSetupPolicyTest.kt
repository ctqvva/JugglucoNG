package tk.glucodata.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSetupPolicyTest {
    @Test
    fun `sibionics 2 accepts only transmitter-style names`() {
        assertTrue(SibionicsType.SIBIONICS2.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.SIBIONICS2.acceptsBleSetupDevice("LT260346HU"))
        assertFalse(SibionicsType.SIBIONICS2.acceptsBleSetupDevice(null))
    }

    @Test
    fun `first generation variants keep generic ff30 discovery`() {
        assertTrue(SibionicsType.EU.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.HEMATONIX.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.CHINESE.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.EU.acceptsBleSetupDevice("HEMATONIX42"))
        assertFalse(SibionicsType.EU.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.HEMATONIX.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.CHINESE.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.CHINESE.acceptsBleSetupDevice(null))
    }

    @Test
    fun `sibionics 2 accepts split labels whose AI 21 is the probe identifier`() {
        // Decoded from real Sibionics 2 split labels: AI (21) is the printed probe code
        // (探头识别码), not the P... serial printed alongside it.
        listOf(
            "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q",
            "\u001D0106972831641476112507151726071410LT46250651C\u001D21145TUMXYK4S46V",
        ).forEach { payload ->
            assertTrue(payload, SibionicsType.SIBIONICS2.acceptsSetupQr(payload))
        }
    }

    @Test
    fun `sibionics 2 still accepts P and XPT labels`() {
        assertTrue(
            SibionicsType.SIBIONICS2.acceptsSetupQr(
                "\u001D0106972831641476112507301726072910LT46250683C\u001D21P2250683013AQT98",
            ),
        )
        assertTrue(
            SibionicsType.SIBIONICS2.acceptsSetupQr(
                "\u001D0106972831641476112512181727061710LT46251212C\u001D21XPT1EEX2NRU16U",
            ),
        )
    }

    @Test
    fun `sibionics 2 still rejects codes that are not sibionics labels`() {
        listOf(
            null,
            "",
            "YAICOMVK1HE1F5EE",
            "https://example.invalid/0106972831641476112602081727080710LT46260201C21EU2VCZUQPSHD5Q",
            // GTIN check digit off by one.
            "\u001D0106972831641475112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q",
            // Empty AI (21).
            "\u001D0106972831641476112602081727080710LT46260201C\u001D21",
        ).forEach { payload ->
            assertFalse(payload.orEmpty(), SibionicsType.SIBIONICS2.acceptsSetupQr(payload))
        }
    }

    @Test
    fun `first generation types keep the generation check on scanned labels`() {
        val probeLabel = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val v120P = "\u001D0106972831641476112507301726072910LT46250683C\u001D21P2250683013AQT98"
        val v120Xpt = "\u001D0106972831641476112512181727061710LT46251212C\u001D21XPT1EEX2NRU16U"
        val eu = "\u001D0106972831641803112412191725121810LT4F241247J\u001D21241247YEZ1450HAJ02"

        listOf(SibionicsType.EU, SibionicsType.HEMATONIX, SibionicsType.CHINESE).forEach { type ->
            assertTrue(type.name, type.acceptsSetupQr(eu))
            assertTrue(type.name, type.acceptsSetupQr(probeLabel))
            assertFalse(type.name, type.acceptsSetupQr(v120P))
            assertFalse(type.name, type.acceptsSetupQr(v120Xpt))
            assertFalse(type.name, type.acceptsSetupQr("YAICOMVK1HE1F5EE"))
        }
        assertFalse(SibionicsType.GS3.acceptsSetupQr(eu))
        assertFalse(SibionicsType.GS3.acceptsSetupQr(probeLabel))
    }

    @Test
    fun `gs3 stays out of public setup choices`() {
        assertFalse(SibionicsType.GS3.setupVisible)
        assertTrue(SibionicsType.entries.filter { it.setupVisible }.none { it == SibionicsType.GS3 })
    }

    @Test
    fun `bluetooth-only setup creates a stable native sensor identity`() {
        val payload = requireNotNull(constructBleOnlySibionicsQr("P225043JMV"))
        val nativeLongName = payload.substring(payload.length - 17, payload.length - 1)

        assertEquals(70, payload.length)
        assertEquals("000003JMVP225043", nativeLongName)
        assertEquals("3JMVP225043", nativeLongName.takeLast(11))
    }

}
