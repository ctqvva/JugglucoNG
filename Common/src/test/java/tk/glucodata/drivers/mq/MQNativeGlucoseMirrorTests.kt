package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQNativeGlucoseMirrorTests {
    private val result = MQAlgorithm.calculateResult(1, 1.0, 24.0, 175.0, 0.0,
        29.3, 0.0, 2.0, 720.0, 1.0)

    // The native poll value is plain mg/dL and addGlucoseStream() multiplies its
    // float by ten on the way in (g.cpp storeGlucoseStreamSample), so the boundary
    // takes mg/dL over ten -- the same contract iCan, Anytime and the wear sync
    // follow. The previous version of this test pinned mg/dL at the boundary and
    // so pinned a tenfold value in native, where valid() rejected it as >= 552 and
    // a Clone of the sensor had nothing to show.
    @Test fun nativeBoundaryReceivesMgdlOverTenSoNativeStoresTrueMgdl() {
        assertEquals(5.9, result.glucoseMmol, 0.0)
        var called = false
        assertTrue(MQNativeGlucoseMirror.write(1_789_000_000_123L, result, "CFD8EBDDF969") { sec, value, id ->
            called = true
            assertEquals(1_789_000_000L, sec)
            assertEquals("CFD8EBDDF969", id)
            val trueMgdl = (5.9 * MQConstants.MMOL_TO_MGDL).toFloat()
            assertEquals(trueMgdl / 10f, value, 0.0001f)
            // What native will hold after its own x10: real mg/dL, inside valid()'s range.
            val nativeG = (value * 10f).toInt()
            assertEquals(trueMgdl.toInt(), nativeG)
            assertTrue("native g=$nativeG must satisfy valid(): g < 552", nativeG in 1 until 552)
            true
        })
        assertTrue(called)
    }

    @Test fun nativeRejectionIsPropagatedAndInvalidSamplesNeverReachStorage() {
        assertFalse(MQNativeGlucoseMirror.write(1_789_000_000_000L, result, "MQ") { _, _, _ -> false })
        val forbidden: (Long, Float, String) -> Boolean = { _, _, _ -> error("Invalid sample written") }
        assertFalse(MQNativeGlucoseMirror.write(0, result, "MQ", forbidden))
        assertFalse(MQNativeGlucoseMirror.write(1_789_000_000_000L, result, "", forbidden))
        assertFalse(MQNativeGlucoseMirror.write(1_789_000_000_000L,
            result.copy(glucoseMmol = Double.NaN), "MQ", forbidden))
    }
}
