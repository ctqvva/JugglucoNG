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
}
