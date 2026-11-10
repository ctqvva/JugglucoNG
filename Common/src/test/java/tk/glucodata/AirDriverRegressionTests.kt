package tk.glucodata

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes production Air methods; does not substitute for vendor/device verification. */
class AirDriverRegressionTests {
    private fun regression(mode: String) {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-air-driver.py").isFile }
        val output = File.createTempFile("air-regression-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-air-driver.py").path, mode)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Air $mode timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }

    @Test fun missingLibraryAndSymbolAreRetriedWithoutAdvancingCursor() = regression("library")

    @Test fun retiredConnectionsCannotDisturbCurrentTransport() = regression("gatt")
}
