package tk.glucodata.glucosemeter

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteMeterProtocolTests {
    @Test
    fun resolvesBarePinAndOfficialPrintedCodeDerivation() {
        assertEquals("007", SatelliteMeterProtocol.resolvePin(" 007 "))
        assertEquals("122", SatelliteMeterProtocol.resolvePin("D2502005885"))
        assertEquals("122", SatelliteMeterProtocol.resolvePin("e2502005885"))
        assertNull(SatelliteMeterProtocol.resolvePin("1234"))
        assertNull(SatelliteMeterProtocol.resolvePin("A2502005885"))
    }

    @Test
    fun buildsCommandsWithUtcMeterClock() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, 15, 12, 34, 56)
        }
        assertArrayEquals(bytes("pin.007"), SatelliteMeterProtocol.pinCommand("007"))
        assertArrayEquals(bytes("settime.260715123456"), SatelliteMeterProtocol.setTimeCommand(calendar.timeInMillis))
        assertArrayEquals(bytes("rd.009"), SatelliteMeterProtocol.recordCommand(9))
    }

    @Test
    fun parsesStrictUtcRecordAndConvertsCapillaryToPlasmaMgdl() {
        val reading = SatelliteMeterProtocol.parseRecord("rd260715123456000055")!!
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, 15, 12, 34, 56)
        }
        assertEquals(calendar.timeInMillis, reading.timestampMillis)
        assertEquals(1_110, reading.mgdlTenths)
        assertNull(SatelliteMeterProtocol.parseRecord("rd260231123456000055"))
        assertNull(SatelliteMeterProtocol.parseRecord("rd260715123456000001"))
        assertNull(SatelliteMeterProtocol.parseRecord("not-a-record"))
    }

    @Test
    fun sessionAuthenticatesThenReturnsOldestFirstBoundedHistory() {
        val session = SatelliteMeterSession("007", 1_800_000_000_000L)
        assertEquals("pin.007", text(session.notificationsEnabled().command))
        assertTrue(session.onNotification(bytes("pin.ok")).command?.let(::text)?.startsWith("settime.") == true)
        assertEquals("rd.000", text(session.onNotification(bytes("settime.ok")).command))
        assertEquals("rd.001", text(session.onNotification(bytes("rd260715123456000055")).command))
        assertEquals("rd.002", text(session.onNotification(bytes("rd260714123456000060")).command))

        val done = session.onNotification(bytes("rd000000000000000000"))
        assertTrue(done.complete)
        assertNull(done.error)
        assertEquals(2, done.readings.size)
        assertTrue(done.readings[0].timestampMillis < done.readings[1].timestampMillis)
    }

    @Test
    fun sessionFailsClosedOnRejectedPinOrMalformedRecord() {
        val rejected = SatelliteMeterSession("007", 1L).apply { notificationsEnabled() }
            .onNotification(bytes("pin.bad"))
        assertTrue(rejected.complete)
        assertEquals("Satellite PIN rejected", rejected.error)

        val malformed = SatelliteMeterSession("007", 1L)
        malformed.notificationsEnabled()
        malformed.onNotification(bytes("pin.ok"))
        malformed.onNotification(bytes("time.ok"))
        val failed = malformed.onNotification(bytes("rd-bad"))
        assertTrue(failed.complete)
        assertFalse(failed.error.isNullOrBlank())
    }

    @Test
    fun sessionStopsAtTenRecordsAndRejectsFutureClockPoisoning() {
        val bounded = SatelliteMeterSession("007", 1_800_000_000_000L)
        bounded.notificationsEnabled()
        bounded.onNotification(bytes("pin.ok"))
        bounded.onNotification(bytes("time.ok"))
        var update: SatelliteSessionUpdate? = null
        repeat(10) { second ->
            update = bounded.onNotification(
                bytes(String.format(Locale.US, "rd2607151234%02d000055", second))
            )
        }
        assertTrue(update!!.complete)
        assertEquals(10, update!!.readings.size)

        val future = SatelliteMeterSession("007", 1_600_000_000_000L)
        future.notificationsEnabled()
        future.onNotification(bytes("pin.ok"))
        future.onNotification(bytes("time.ok"))
        val rejected = future.onNotification(bytes("rd260715123456000055"))
        assertTrue(rejected.complete)
        assertEquals("Satellite record timestamp is in the future", rejected.error)
    }

    private fun bytes(value: String) = value.toByteArray(StandardCharsets.US_ASCII)
    private fun text(value: ByteArray?) = value?.toString(StandardCharsets.US_ASCII)
}
