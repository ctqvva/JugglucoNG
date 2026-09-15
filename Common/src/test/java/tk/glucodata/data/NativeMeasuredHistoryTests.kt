package tk.glucodata.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the production C++ exporter with an in-memory sensor (requires a host C++ compiler). */
class NativeMeasuredHistoryTests {
    private fun repositoryFile(path: String): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val file = File(directory, path)
            if (file.isFile) return file
            directory = directory.parentFile
        }
        error("Cannot find $path")
    }

    @Test
    fun measuredHistoryExcludesDexcomForecastsButPreservesLibreHistoryAndPolls() {
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
            run(directory, listOf(System.getenv("CXX") ?: "c++", "-std=c++17", source.path, "-o", binary.path))
            run(directory, listOf(binary.path))
        } finally {
            directory.deleteRecursively()
        }
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
