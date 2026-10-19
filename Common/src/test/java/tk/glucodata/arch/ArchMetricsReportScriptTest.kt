package tk.glucodata.arch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scripts/arch-metrics.sh` is report-only now (plan §2.3): nothing in CI depends on its exit
 * code or its numbers, so this is a smoke test, not a correctness gate. It exists only to catch
 * the script crashing or silently producing no output -- a wrong count in a report line is a
 * misleading log, not a build that should have failed and did not.
 */
class ArchMetricsReportScriptTest {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    private fun repoRoot(): File =
        generateSequence(moduleRoot) { it.parentFile }
            .firstOrNull { File(it, "scripts/arch-metrics.sh").isFile }
            ?: error("repository root with scripts/arch-metrics.sh not found from $moduleRoot")

    @Test
    fun theScriptRunsAndPrintsAReport() {
        val script = File(repoRoot(), "scripts/arch-metrics.sh")
        assertTrue("scripts/arch-metrics.sh not found", script.isFile)

        val process = ProcessBuilder("bash", script.absolutePath)
            .directory(repoRoot())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals("arch-metrics.sh exited non-zero:\n$output", 0, exitCode)
        assertTrue("missing volatile-fields line:\n$output", output.contains("@Volatile / volatile fields:"))
        assertTrue("missing per-package section:\n$output", output.contains("lines of code by top-level package"))
        assertTrue("missing oversized-files section:\n$output", output.contains("files over 2000 lines"))
    }
}
