package tk.glucodata.drivers.aidex

import java.io.File
import java.security.MessageDigest

internal object AiDexSourceJournalBackup {
    private const val ROOT = "aidex-managed"
    private const val NAME = "source-samples-v1.bin"
    private val pathPattern = Regex("^$ROOT/[0-9a-f]{24}/${Regex.escape(NAME)}$")

    fun file(filesDir: File, sensorId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(sensorId.trim().uppercase().toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return File(filesDir, "$ROOT/$digest/$NAME")
    }

    fun isJournalPath(path: String): Boolean = pathPattern.matches(path)

    fun collect(filesDir: File): Map<String, ByteArray> = File(filesDir, ROOT).listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{24}")) }
        .map { "$ROOT/${it.name}/$NAME" to File(it, NAME) }
        .filter { it.second.isFile }
        .associate { (path, file) -> path to AiDexSampleJournal(file).bytes() }

    fun restore(filesDir: File, path: String, bytes: ByteArray): Int {
        require(isJournalPath(path)) { "Unsupported AiDex journal path" }
        val target = File(filesDir, path)
        require(target.canonicalPath.startsWith(File(filesDir, ROOT).canonicalPath + File.separator))
        val imported = AiDexSampleJournal.decode(bytes)
        val journal = AiDexSampleJournal(target)
        val held = journal.snapshot().mapTo(HashSet()) { it.minute }
        return journal.appendAll(imported.filter { it.minute !in held })
    }
}
