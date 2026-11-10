package tk.glucodata.drivers.sibionics

import java.io.File

/**
 * The raw source-sample journals behind every Sibionics sensor, as files an
 * export package can carry. Without them a restored app has the exact algorithm
 * checkpoint but no input for a local rebuild, and the only way to get that
 * input back is to download the sensor's whole life over BLE again.
 *
 * Paths are relative to the app's files directory and keyed by the sensor-id
 * digest the driver already uses, so a restore lands where the driver looks.
 */
internal object SibionicsSourceJournalBackup {
    private const val ROOT = "sibionics-managed"
    private const val FILE_NAME = "source-samples-v1.bin"
    private val RELATIVE_PATH = Regex("^$ROOT/[0-9a-f]{24}/${Regex.escape(FILE_NAME)}$")

    fun collect(filesDir: File): Map<String, File> {
        val root = File(filesDir, ROOT)
        val dirs = root.listFiles { file -> file.isDirectory }.orEmpty()
        return dirs.sortedBy { it.name }
            .map { it to File(it, FILE_NAME) }
            .filter { (_, file) -> file.isFile && file.length() > 0L }
            .associate { (dir, file) -> "$ROOT/${dir.name}/$FILE_NAME" to file }
    }

    fun isJournalPath(relativePath: String): Boolean = RELATIVE_PATH.matches(relativePath)

    /**
     * Merge [bytes] (a journal file as exported) into the journal at
     * [relativePath]. Existing samples win on conflict, so restoring an older
     * backup over a live journal never loses what the sensor delivered since.
     * Returns the number of samples the local journal did not have.
     */
    fun restore(filesDir: File, relativePath: String, bytes: ByteArray): Int {
        require(isJournalPath(relativePath)) { "Unsupported source journal path: $relativePath" }
        val target = File(filesDir, relativePath)
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "$FILE_NAME.import")
        try {
            staging.writeBytes(bytes)
            val imported = SibionicsSampleJournal(staging).snapshot()
            if (imported.isEmpty()) return 0
            val local = SibionicsSampleJournal(target)
            val held = local.snapshot().associateBy { it.index }
            return local.appendAll(imported.filter { it.index !in held })
        } finally {
            staging.delete()
        }
    }
}
