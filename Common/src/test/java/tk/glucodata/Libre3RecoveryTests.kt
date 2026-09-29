package tk.glucodata

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Libre3RecoveryTests {
    private val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "tools/test-libre3-connect.py").isFile }

    private val jdkBin = File(System.getProperty("java.home"), "bin")
    private val jdkHasJavac = File(jdkBin, "javac").isFile || File(jdkBin, "javac.exe").isFile

    /** The test JVM's own JDK first, so the scripts' javac and java match (PATH may pair a new javac with an old java). */
    private val path: String = run {
        val inherited = System.getenv("PATH").orEmpty()
        if (jdkHasJavac) jdkBin.path + File.pathSeparator + inherited else inherited
    }

    private fun process(command: List<String>) = ProcessBuilder(command).apply { environment()["PATH"] = path }

    private fun runs(vararg command: String): Boolean {
        val sink = File.createTempFile("libre3-probe-", ".log")
        return try {
            val started = process(command.toList()).redirectErrorStream(true).redirectOutput(sink).start()
            started.waitFor(20, TimeUnit.SECONDS) && started.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            sink.delete()
        }
    }

    /** A working Python 3: on Windows `python3` can be the Store alias, which only prints a hint. */
    private val python: List<String>? by lazy {
        listOf(listOf("python3"), listOf("python"), listOf("py", "-3"))
            .firstOrNull { runs(*(it + listOf("-c", "import sys; sys.exit(sys.version_info[0] != 3)")).toTypedArray()) }
    }

    /** A g++ that can do what the native scripts ask of it: build and run C++20 under ASan and UBSan. */
    private val sanitizingCxx: Boolean by lazy {
        val source = File.createTempFile("libre3-probe-", ".cpp")
        val binary = File(source.parentFile, source.nameWithoutExtension)
        try {
            source.writeText("int main() { return 0; }\n")
            runs("g++", "-std=c++20", "-fsanitize=address,undefined", source.path, "-o", binary.path) && runs(binary.path)
        } finally {
            source.delete()
            binary.delete()
            File(binary.path + ".exe").delete()
        }
    }

    private fun regression(script: String, needsJavac: Boolean = false, needsCxx: Boolean = false) {
        val interpreter = python
        assumeTrue("no working Python 3 here, $script not run", interpreter != null)
        // The script finds javac on the child's PATH, which starts with the JDK's bin when it has one;
        // a probe by name would search the parent's PATH instead.
        if (needsJavac) assumeTrue("no javac here, $script not run", jdkHasJavac || runs("javac", "-version"))
        if (needsCxx) assumeTrue("no g++ with ASan/UBSan here, $script not run", sanitizingCxx)
        val output = File.createTempFile("libre3-recovery-", ".log")
        try {
            val started = process(interpreter!! + File(root, "tools/$script").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = started.waitFor(60, TimeUnit.SECONDS)
            if (!finished) started.destroyForcibly()
            assertTrue("$script timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, started.exitValue())
        } finally {
            output.delete()
        }
    }

    @Test fun silentConnectionRecoversWithoutResurrectingStoppedSensors() =
        regression("test-libre3-connect.py", needsJavac = true)

    @Test fun nfcResponseParsingIsDeterministicAndBounded() =
        regression("test-libre3-nfc-response.py", needsCxx = true)

    @Test fun nfcMetadataRepairProtectsHistoryAndCredentials() =
        regression("test-libre3-nfc.py", needsCxx = true)
}
