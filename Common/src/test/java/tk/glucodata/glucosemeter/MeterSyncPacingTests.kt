package tk.glucodata.glucosemeter

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterSyncPacingTests {
    private val everyOutcome = MeterLinkOutcome.values().toList()

    @Test
    fun aMeterThatSleepsIsNeverHeldBack() {
        // Contour and the rest: whatever the link brought, however long it kept answering.
        val pacer = MeterLinkPacer()
        repeat(20) {
            for (outcome in everyOutcome) assertEquals(outcome.name, 0L, pacer.linkEnded(outcome, false, 5))
        }
    }

    @Test
    fun aMeterThatStaysOnWaitsAfterNothingNew() {
        // Satellite in #798: read once per interval instead of every 33 s.
        val pacer = MeterLinkPacer()
        repeat(5) { assertEquals(300_000L, pacer.linkEnded(MeterLinkOutcome.NOTHING_NEW, true, 5)) }
        assertEquals(900_000L, pacer.linkEnded(MeterLinkOutcome.NOTHING_NEW, true, 15))
        // New readings, or a link that dropped before the meter answered: straight back.
        assertEquals(0L, pacer.linkEnded(MeterLinkOutcome.NEW_READINGS, true, 5))
        assertEquals(0L, pacer.linkEnded(MeterLinkOutcome.UNANSWERED, true, 5))
    }

    @Test
    fun aFailureAloneIsRetriedAtOnceButARepeatingOneWaits() {
        val pacer = MeterLinkPacer()
        // A failure right after a run of empty syncs is still isolated.
        repeat(5) { pacer.linkEnded(MeterLinkOutcome.NOTHING_NEW, true, 5) }
        assertEquals(0L, pacer.linkEnded(MeterLinkOutcome.FAILED, true, 5))
        // A wrong Satellite code fails every link: not redialled and toasted twice a minute.
        assertEquals(300_000L, pacer.linkEnded(MeterLinkOutcome.FAILED, true, 5))
        assertEquals(300_000L, pacer.linkEnded(MeterLinkOutcome.FAILED, true, 5))
        // Anything else in between makes the next failure isolated again.
        for (breaker in listOf(MeterLinkOutcome.NOTHING_NEW, MeterLinkOutcome.NEW_READINGS, MeterLinkOutcome.UNANSWERED)) {
            pacer.linkEnded(breaker, true, 5)
            assertEquals(breaker.name, 0L, pacer.linkEnded(MeterLinkOutcome.FAILED, true, 5))
            pacer.linkEnded(MeterLinkOutcome.FAILED, true, 5)
        }
        // Failures on a sleeping meter never wait.
        val sleeper = MeterLinkPacer()
        repeat(5) { assertEquals(0L, sleeper.linkEnded(MeterLinkOutcome.FAILED, false, 5)) }
    }

    @Test
    fun intervalZeroReconnectsAtOnceAsBefore() {
        val pacer = MeterLinkPacer()
        repeat(3) { for (outcome in everyOutcome) assertEquals(0L, pacer.linkEnded(outcome, true, 0)) }
        assertTrue(MeterSyncPacing.DEFAULT_MINUTES in MeterSyncPacing.CHOICES_MINUTES)
    }

    @Test
    fun onlyASuccessfulReportStoredRecordsAnswerCountsAsASync() {
        fun racp(vararg bytes: Int) = MeterSyncPacing.racpReportSucceeded(ByteArray(bytes.size) { bytes[it].toByte() })
        assertTrue("records sent", racp(0x06, 0x00, 0x01, 0x01))
        assertTrue("no records stored", racp(0x06, 0x00, 0x01, 0x06))
        assertFalse("op code not supported", racp(0x06, 0x00, 0x01, 0x02))
        assertFalse("invalid operator", racp(0x06, 0x00, 0x01, 0x03))
        assertFalse("procedure not completed", racp(0x06, 0x00, 0x01, 0x08))
        assertFalse("answer to another request", racp(0x06, 0x00, 0x04, 0x01))
        assertFalse("number of records response", racp(0x05, 0x00, 0x03, 0x00))
        assertFalse("truncated", racp(0x06, 0x00, 0x01))
        assertFalse(MeterSyncPacing.racpReportSucceeded(null))
    }

    @Test
    fun productionPacingMethodsSurviveStaleTasksAndNewerLinks() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-meter-pacing.py").isFile }
        val output = File.createTempFile("meter-pacing-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-meter-pacing.py").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Meter pacing timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }
}
