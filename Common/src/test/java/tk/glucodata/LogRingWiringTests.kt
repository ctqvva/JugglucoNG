package tk.glucodata

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The ring is only worth anything if the calls the drivers actually make reach it. */
class LogRingWiringTests {
    @Before
    fun clear() = SensorTraceRing.reset()

    @Test
    fun taggedCallsReachTheRingWithLoggingOff() {
        Log.doLog = false
        Log.i("Anytime", "CT5 raw id=8175")
        Log.d("Anytime", "onCharacteristicWrite status=0")
        Log.w("Ottai", "no data for 300s")
        Log.e("Ottai", "handshake failed")

        val lines = SensorTraceRing.snapshot().toList()
        assertTrue("expected four captured lines, got $lines", lines.size == 4)
        assertTrue(lines[0].contains("I/Anytime CT5 raw id=8175"))
        assertTrue(lines[3].contains("E/Ottai handshake failed"))
    }
}
