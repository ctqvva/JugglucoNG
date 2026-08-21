package tk.glucodata.NovoPen.opennov.mt

import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.Log
import tk.glucodata.NovoPen.opennov.OpContext

/**
 * The anchor turns the pen's counter into clock time for every dose of a scan, so which
 * report it is taken from decides whether the whole read lands inside the time window or
 * entirely outside it.
 */
class EventReportAnchorTests {

    private var loggingWasOn = false

    /** The segment parser dumps raw bytes through a native call when logging is on. */
    @Before
    fun quietLogging() {
        loggingWasOn = Log.doLog
        Log.doLog = false
    }

    @After
    fun restoreLogging() {
        Log.doLog = loggingWasOn
    }

    /** Event report header: handle, relative time, event type, length of what follows. */
    private fun header(relativeTime: Long, eventType: Int, bodyLength: Int): ByteBuffer =
        ByteBuffer.allocate(10 + bodyLength).apply {
            putShort(256)
            putInt(relativeTime.toInt())
            putShort(eventType.toShort())
            putShort(bodyLength.toShort())
        }

    /** A configuration report carrying no objects: id, count 0, length 0. */
    private fun configReport(relativeTime: Long): ByteBuffer =
        header(relativeTime, EventReport.MDC_NOTI_CONFIG, 6).apply {
            putShort(0x4000)
            putShort(0)
            putShort(0)
            flip()
        }

    /** A segment-data report holding one well-formed dose record. */
    private fun segmentReport(relativeTime: Long, doseRelativeSeconds: Long): ByteBuffer =
        header(relativeTime, EventReport.MDC_NOTI_SEGMENT_DATA, 14 + 12).apply {
            putShort(16) // instance
            putInt(0) // index
            putInt(1) // count
            putShort(0) // status
            putShort(12) // bcount
            putInt(doseRelativeSeconds.toInt())
            put(0xFF.toByte()); put(0x00)
            putShort(40) // 4.0 U
            put(0x08); put(0x00); put(0x00); put(0x00)
            flip()
        }

    @Test
    fun anchorIsTakenFromTheSegmentReportNotTheConfigurationReport() {
        // What a real scan looks like: the configuration report opens it with a time
        // field of 0, the log data follows with the pen's live counter.
        val context = OpContext()
        val penClock = 11_019_281L
        val before = System.currentTimeMillis() / 1000L

        EventReport.parse(configReport(relativeTime = 0L), context)
        EventReport.parse(segmentReport(relativeTime = penClock, doseRelativeSeconds = penClock - 60L), context)

        val after = System.currentTimeMillis() / 1000L
        assertEquals(1, context.doses.size)
        val reference = context.doses[0].referencetime
        assertTrue("anchor $reference should be clock minus pen counter", reference in (before - penClock)..(after - penClock))
    }

    @Test
    fun configurationReportAloneDoesNotPinTheAnchor() {
        val context = OpContext()

        EventReport.parse(configReport(relativeTime = 0L), context)
        val penClock = 11_019_281L
        val before = System.currentTimeMillis() / 1000L
        EventReport.parse(segmentReport(relativeTime = penClock, doseRelativeSeconds = penClock - 60L), context)

        // Had the configuration report pinned it, the reference would be "now - 0" and the
        // dose would sit the pen's whole uptime in the future.
        assertTrue(context.doses[0].referencetime < before)
    }

    @Test
    fun laterSegmentReportsReuseTheFirstAnchor() {
        val context = OpContext()

        EventReport.parse(segmentReport(relativeTime = 1_000L, doseRelativeSeconds = 900L), context)
        EventReport.parse(segmentReport(relativeTime = 1_002L, doseRelativeSeconds = 800L), context)

        assertEquals(context.doses[0].referencetime, context.doses[1].referencetime)
    }
}
