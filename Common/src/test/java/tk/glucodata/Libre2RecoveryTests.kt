package tk.glucodata

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Libre2RecoveryTests {
    @Test fun freshGattRecoveryHandlesMissingAndRetiredCallbacks() = run("test-libre2-connect.py")

    @Test fun historyMinutesAndSensorErrorFrames() = run("test-libre2-processing.py")

    private fun run(script: String) {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/$script").isFile }
        val output = File.createTempFile("libre2-recovery-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/$script").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("$script timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }
}
