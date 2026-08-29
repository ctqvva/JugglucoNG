package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloneSensorKeyCodecTests {
    @Test
    fun normalizesSensorIdsForStableMatching() {
        assertEquals("ABC-123", CloneSensorKeyCodec.normalize("  abc-123 "))
        assertNull(CloneSensorKeyCodec.normalize("  "))
    }

    @Test
    fun encodingIsDistinctAndDeterministic() {
        assertEquals(
            "ABC|1\nXYZ|2",
            CloneSensorKeyCodec.encode(
                linkedMapOf(
                    " xyz " to CloneTransport.TURN,
                    "ABC" to CloneTransport.LOCAL_ICE,
                )
            )
        )
    }

    @Test
    fun decodingIgnoresBlankLinesAndAcceptsLegacyUnknownEntries() {
        assertEquals(
            mapOf(
                "ABC" to CloneTransport.UNKNOWN,
                "XYZ" to CloneTransport.TURN,
            ),
            CloneSensorKeyCodec.decode("abc\n\n XYZ|2 \n")
        )
    }

    @Test
    fun transportLookupNeverAttributesOnePhysicalSensorToAnother() {
        val encoded = CloneSensorKeyCodec.encode(
            mapOf("OLD-SENSOR" to CloneTransport.LOCAL_ICE)
        )
        assertEquals(CloneTransport.LOCAL_ICE, CloneSensorKeyCodec.transportFor(encoded, "old-sensor"))
        assertNull(CloneSensorKeyCodec.transportFor(encoded, "NEW-SENSOR"))
    }

    @Test
    fun transportLookupAcceptsExplicitFullAndShortAliases() {
        val encoded = CloneSensorKeyCodec.encode(
            mapOf("E07A-XX0TEST000A" to CloneTransport.LOCAL_ICE)
        )

        assertEquals(
            CloneTransport.LOCAL_ICE,
            CloneSensorKeyCodec.transportForAny(
                encoded,
                listOf("XX0TEST000A", "E07A-XX0TEST000A"),
            ),
        )
        assertNull(
            CloneSensorKeyCodec.transportForAny(
                encoded,
                listOf("XX0TEST000B", "E07A-XX0TEST000B"),
            )
        )
    }

    @Test
    fun handoverRetiresEveryCloneExceptTheSendersNewPrimary() {
        assertEquals(
            listOf("OLD-SENSOR", "HISTORY-SENSOR"),
            CloneSensorKeyCodec.nonPrimarySensorIds(
                listOf("OLD-SENSOR", "new-sensor", "HISTORY-SENSOR"),
                "NEW-SENSOR",
            )
        )
    }

    @Test
    fun handoverKeepsPrimaryAcrossFullAndShortAliases() {
        assertEquals(
            listOf("E07A-XX0TEST000B"),
            CloneSensorKeyCodec.nonPrimarySensorIds(
                listOf("E07A-XX0TEST000A", "E07A-XX0TEST000B"),
                listOf("XX0TEST000A", "E07A-XX0TEST000A"),
            )
        )
    }
}
