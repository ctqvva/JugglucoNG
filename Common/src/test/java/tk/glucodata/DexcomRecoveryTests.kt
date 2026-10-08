package tk.glucodata

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomRecoveryTests {
    @Test fun recoveryRetainsIdentityAndWakesForTheNextAdvert() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-dexcom-connect.py").isFile }
        val output = File.createTempFile("dexcom-recovery-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-dexcom-connect.py").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Dexcom recovery timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }
}
