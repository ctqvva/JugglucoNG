package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The watch's loss-alarm tick asked the phone to wake its native stream sender
 * (/wakestream) on every check. After the /data gate the phone streams nothing
 * natively at this watch, and the same tick already requests a WearSync2 sync,
 * which is the channel that delivers data. The watch's own sender
 * (Natives.wakestreamsender) stays.
 */
class WearAlarmLoopNoWakeStreamTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        // The test runs from the module directory; walk up if it does not.
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    @Test
    fun `alarm loop does not wake the phone's stream sender`() {
        val text = File(moduleRoot, "src/wear/java/tk/glucodata/WearGlucoseAlarms.java").readText()
        assertFalse(
            "the watch's alarm loop still asks the phone to wake its stream sender (/wakestream); " +
                "after the /data gate the phone has nothing to stream at the watch, and the same " +
                "tick already requests WearSync2",
            text.contains("sendwakestream"),
        )
    }
}
