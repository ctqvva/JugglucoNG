package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class CloneIceReportTests {
    private val connected = """<h1>ICE Connection 0: Hybrid Clone</h1><p><b>ICElabel</b>: "x" <b>Side</b>=0<br><b>Send to</b>: Amounts Scans Stream  <b>Running</b>=true <b>Locked</b>=true  <br><b>Receive from</b>: false    3046 wait for commands: true    <b>interpret</b>: false </p><p><b>Phase</b>: Same Connection<br><b>Local:</b> 192.168.1.162:58273 Host<br><b>Remote:</b> 192.168.1.117:48277 Host<br><b>ICE State:</b> completed<br>Connected<br>Wed Nov  8 03:54:36 2026
"""

    @Test
    fun readsTheFactsTheCardShows() {
        val report = CloneIceReport.parse(connected)
        assertEquals("Same Connection", report.phase)
        assertEquals("192.168.1.162", report.local)
        assertEquals("192.168.1.117", report.remote)
        assertEquals("completed", report.state)
        val at = Calendar.getInstance().apply { timeInMillis = assertNotNullLong(report.connectedAtMs) }
        assertEquals(2026, at.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, at.get(Calendar.MONTH))
        assertEquals(8, at.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, at.get(Calendar.HOUR_OF_DAY))
        assertEquals(54, at.get(Calendar.MINUTE))
    }

    @Test
    fun ipv6AddressesKeepTheirColons() {
        val report = CloneIceReport.parse("<b>Remote:</b> [2a02:6b8::1]:5000 Server Reflexive<br>")
        assertEquals("2a02:6b8::1", report.remote)
    }

    @Test
    fun aReportWithoutAPairHasNoAddresses() {
        val report = CloneIceReport.parse("<p><b>Phase</b>: Put description<br><b>Local:</b> <br><b>ICE State:</b> disconnected\u0000")
        assertEquals("Put description", report.phase)
        assertNull(report.local)
        assertNull(report.remote)
        assertNull(report.connectedAtMs)
    }

    @Test
    fun emptyIsEmpty() {
        assertEquals(CloneIceReport(), CloneIceReport.parse(""))
        assertEquals(CloneIceReport(), CloneIceReport.parse(null))
    }

    private fun assertNotNullLong(value: Long?): Long {
        assertNotNull(value)
        return value!!
    }
}
