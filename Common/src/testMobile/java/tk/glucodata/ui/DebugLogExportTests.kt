package tk.glucodata.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DebugLogExportTests {

    @Test
    fun `file backed log is copied after the report header`() {
        val sourceFile = File.createTempFile("juggluco-debug-export", ".log")
        try {
            val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
            sourceFile.outputStream().use { it.write(payload) }
            val output = ByteArrayOutputStream()

            writeDebugLogReport(
                output = output,
                header = "# test build\n\n",
                source = DebugLogExportSource.FileContent(sourceFile),
            )

            assertArrayEquals("# test build\n\n".toByteArray() + payload, output.toByteArray())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun `bounded text log is copied after the report header`() {
        val output = ByteArrayOutputStream()

        writeDebugLogReport(
            output = output,
            header = "# test build\n\n",
            source = DebugLogExportSource.TextContent("BLE error\n"),
        )

        assertArrayEquals("# test build\n\nBLE error\n".toByteArray(), output.toByteArray())
    }

    @Test
    fun `missing and empty file sources are rejected before export`() {
        val missing = File("/path/that/does/not/exist/juggluco-trace.log")
        val empty = File.createTempFile("juggluco-empty-export", ".log")
        try {
            assertTrue(DebugLogExportSource.FileContent(missing).isEmpty())
            assertTrue(DebugLogExportSource.FileContent(empty).isEmpty())
            assertFalse(DebugLogExportSource.TextContent("one line\n").isEmpty())
        } finally {
            empty.delete()
        }
    }
}
