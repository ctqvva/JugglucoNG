package tk.glucodata.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Executes the production C++ exporter with an in-memory sensor (requires a host C++ compiler). */
class NativeMeasuredHistoryTests {
    private fun repositoryFile(path: String): File {
        var directory: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val file = File(directory, path)
            if (file.isFile) return file
            directory = directory.parentFile
        }
        error("Cannot find $path")
    }

    @Test
    fun measuredHistoryExcludesDexcomForecastsButPreservesLibreHistoryAndPolls() {
        val compiler = compilerOrSkip()
        val nativeSource = repositoryFile("Common/src/main/cpp/g.cpp").readText()
        val start = nativeSource.indexOf("static void appendStoredHistory(")
        val end = nativeSource.indexOf("static void appendScanHistory(", start)
        assertTrue("Locate the production stored-history exporter", start >= 0 && end > start)
        val exporter = nativeSource.substring(start, end)
        val fixture = repositoryFile("Common/src/test/resources/native/measured_history.cpp").readText()
        val directory = Files.createTempDirectory("measured-history-test").toFile()
        try {
            val source = File(directory, "test.cpp")
            source.writeText(fixture.replace("// PRODUCTION_EXPORTER", exporter))
            val binary = File(directory, "test")
            run(directory, listOf(compiler, "-std=c++17", source.path, "-o", binary.path))
            run(directory, listOf(binary.path))
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * This test compiles the production exporter for real, so without a host compiler it can
     * prove nothing. Skip it rather than report a failure that says nothing about the code.
     */
    private fun compilerOrSkip(): String {
        val cxx = System.getenv("CXX") ?: "c++"
        val present = try {
            val probe = ProcessBuilder(cxx, "--version").redirectErrorStream(true).start()
            probe.inputStream.use { it.readBytes() }
            probe.waitFor(30, TimeUnit.SECONDS)
        } catch (_: java.io.IOException) {
            false
        }
        assumeTrue("No host C++ compiler on PATH; set CXX to point at one", present)
        return cxx
    }

    private fun run(directory: File, command: List<String>) {
        val log = File(directory, "output.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("Timed out: $command", completed)
        assertEquals(log.readText(), 0, process.exitValue())
    }
}
