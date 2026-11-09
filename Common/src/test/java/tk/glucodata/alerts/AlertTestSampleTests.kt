package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTestSampleTests {

    @Test
    fun lowsFallAndHighsRise() {
        listOf(AlertType.LOW, AlertType.VERY_LOW, AlertType.PRE_LOW, AlertType.FALLING_FAST).forEach {
            assertTrue("$it", AlertTestSample.rate(it)!! < -0.5f)
        }
        listOf(AlertType.HIGH, AlertType.VERY_HIGH, AlertType.PRE_HIGH, AlertType.RISING_FAST).forEach {
            assertTrue("$it", AlertTestSample.rate(it)!! > 0.5f)
        }
        assertTrue(AlertTestSample.rate(AlertType.FALLING_FAST)!! < AlertTestSample.rate(AlertType.LOW)!!)
        assertTrue(AlertTestSample.customRate(isHigh = true) > 0f)
        assertTrue(AlertTestSample.customRate(isHigh = false) < 0f)
    }

    @Test
    fun alarmsNotAboutDirectionKeepTheCurrentRate() {
        listOf(AlertType.LOSS, AlertType.MISSED_READING, AlertType.SENSOR_EXPIRY, AlertType.SIGNAL_RESTORED).forEach {
            assertNull("$it", AlertTestSample.rate(it))
        }
    }

    @Test
    fun theHourEndsAtTheTestValueArrivingAtTheTestRate() {
        val end = 10_000_000L
        val rate = -1.4f
        val trace = AlertTestSample.trace(2.7f, rate, isMmol = true, endMs = end)
        assertEquals(181, trace.size)
        assertEquals(end, trace.last().first)
        assertEquals(end - 180 * 60_000L, trace.first().first)
        assertEquals(2.7f, trace.last().second, 0.001f)
        // Came down into the low: three hours ago it was higher, and the last minutes fall at the rate.
        assertTrue(trace.first().second > 4.5f)
        // Level before the last hour, so a long window does not start somewhere absurd.
        assertTrue(trace.first().second < 5.6f)
        assertEquals(trace.first().second, trace[trace.size - 61].second, 0.6f)
        val lastSlopeMgdl = (trace.last().second - trace[trace.size - 6].second) * 18.0182f / 5f
        assertEquals(rate, lastSlopeMgdl, 0.3f)
    }

    @Test
    fun mgDlTracesStayInsideTheSensorRange() {
        val trace = AlertTestSample.trace(45f, -2.6f, isMmol = false, endMs = 0L)
        trace.forEach { assertTrue("${it.second}", it.second in 40f..400f) }
    }
}
