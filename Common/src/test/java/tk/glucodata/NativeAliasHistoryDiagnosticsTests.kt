package tk.glucodata

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeAliasHistoryDiagnosticsTests {
    private val canonical = "SIBI:0111801JGS9"
    private val old = "240600111801JGS9"
    private val base = 1_760_000_000L / 60 * 60

    private fun write(directory: File, name: String, vararg rows: Triple<Int, Int, Int>) {
        val record = File(directory, name).apply { mkdirs() }
        val polls = ByteBuffer.allocate(rows.size * 20).order(ByteOrder.LITTLE_ENDIAN)
        val raw = ByteBuffer.allocate(rows.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        rows.forEach { (minute, auto, rawValue) ->
            polls.putInt((base + minute * 60).toInt()).putInt(minute).putInt(auto).putInt(0).putFloat(0f)
            raw.putShort(rawValue.toShort())
        }
        File(record, "polls.dat").writeBytes(polls.array())
        File(record, "rawpolls.dat").writeBytes(raw.array())
    }

    private fun report(directory: File, names: List<String> = listOf(old, canonical)) =
        NativeAliasHistoryDiagnostics.inspect(directory, canonical, "0111801JGS9",
            listOf(canonical), names, base, base + 600)

    @Test fun detectsTwoPopulatedPhysicalRecordsWithoutModifyingFiles() {
        val root = Files.createTempDirectory("alias-report-").toFile()
        try {
            write(root, canonical, Triple(2, 123, 65), Triple(3, 125 or 0x10000, 66))
            write(root, old, Triple(1, 120, 64), Triple(2, 123, 65), Triple(3, 190, 80))
            val before = root.walk().filter { it.isFile }.associate { it.relativeTo(root).path to (it.readBytes() to it.lastModified()) }
            val text = report(root)
            assertTrue(text, text.contains("classification=SPLIT_POLL_HISTORY"))
            assertTrue(text, text.contains("alias_only_minutes=1"))
            assertTrue(text, text.contains("overlap_minutes=2"))
            assertTrue(text, text.contains("stored_auto_different=1"))
            assertTrue(text, text.contains("representation_different=1"))
            assertTrue(text, text.contains("equality_is_not_algorithm_proof=true"))
            assertEquals(before.keys, root.walk().filter { it.isFile }.map { it.relativeTo(root).path }.toSet())
            before.forEach { (path, state) ->
                assertArrayEquals(state.first, File(root, path).readBytes())
                assertEquals(state.second, File(root, path).lastModified())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun distinguishesSameRecordEmptyStoreAndMissingLookup() {
        val root = Files.createTempDirectory("alias-report-").toFile()
        try {
            write(root, canonical, Triple(2, 123, 65))
            assertTrue(report(root, listOf(canonical)).contains("classification=SAME_NATIVE_RECORD"))
            write(root, old)
            assertTrue(report(root).contains("classification=ONE_OR_BOTH_EMPTY"))
            assertTrue(NativeAliasHistoryDiagnostics.inspect(root, canonical, "old", null,
                listOf(old), base, base + 600).contains("classification=LOOKUP_UNAVAILABLE"))
            assertTrue(NativeAliasHistoryDiagnostics.inspect(root, canonical, "old", emptyList(),
                listOf(old), base, base + 600).contains("classification=CANONICAL_MISSING"))
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedReadRetriesWithoutCreatingOrRepairingStores() {
        val root = Files.createTempDirectory("alias-report-").toFile()
        try {
            write(root, canonical, Triple(2, 123, 65))
            assertTrue(report(root).contains("classification=INCOMPLETE_SNAPSHOT"))
            assertFalse(File(root, old).exists())
            write(root, old, Triple(1, 120, 64))
            File(root, "$old/polls.dat").appendBytes(byteArrayOf(1))
            assertTrue(report(root).contains("classification=INCOMPLETE_SNAPSHOT"))
            write(root, old, Triple(1, 120, 64))
            assertTrue(report(root).contains("classification=SPLIT_POLL_HISTORY"))
            assertEquals(report(root), report(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun handlesRawOnlyOldRecordsTruncationAndUnsafeNames() {
        val root = Files.createTempDirectory("alias-report-").toFile()
        try {
            write(root, old, Triple(1, 0, 70), Triple(2, 120, 65))
            val snapshot = NativeAliasHistoryDiagnostics.read(root, old, base, base + 600)
            assertEquals(2, snapshot.minutes.size)
            assertEquals(0, snapshot.minutes.getValue((base + 60) / 60).auto)
            File(root, "$old/rawpolls.dat").writeBytes(byteArrayOf(1))
            assertEquals("TRUNCATED", NativeAliasHistoryDiagnostics.read(root, old, base, base + 600).rawStatus)
            assertEquals("INVALID_NAME", NativeAliasHistoryDiagnostics.read(root, "../escape", base, base + 600).status)
        } finally { root.deleteRecursively() }
    }

    @Test fun productionNativeLookupIncludesRetiredAliasesAndPreservesDiskLayout() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-native-alias-diagnostics.py").isFile }
        val output = File.createTempFile("alias-native-", ".log")
        try {
            val process = ProcessBuilder("python3", "tools/test-native-alias-diagnostics.py")
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            assertTrue("native diagnostic test timed out", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
        } finally { output.delete() }
    }
}
