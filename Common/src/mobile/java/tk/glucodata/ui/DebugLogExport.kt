package tk.glucodata.ui

import java.io.File
import java.io.OutputStream

/**
 * Export payloads stay split by storage type: trace and logcat are streamed from disk, while the
 * deliberately bounded BLE history can remain an in-memory string.
 */
internal sealed class DebugLogExportSource {
    abstract fun isEmpty(): Boolean
    abstract fun copyTo(output: OutputStream)

    class FileContent(private val file: File) : DebugLogExportSource() {
        override fun isEmpty(): Boolean = !file.exists() || file.length() == 0L

        override fun copyTo(output: OutputStream) {
            file.inputStream().buffered().use { input -> input.copyTo(output) }
        }
    }

    class TextContent(private val text: String) : DebugLogExportSource() {
        override fun isEmpty(): Boolean = text.isEmpty()

        override fun copyTo(output: OutputStream) {
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}

internal fun writeDebugLogReport(
    output: OutputStream,
    header: String,
    source: DebugLogExportSource,
) {
    output.write(header.toByteArray(Charsets.UTF_8))
    source.copyTo(output)
}
