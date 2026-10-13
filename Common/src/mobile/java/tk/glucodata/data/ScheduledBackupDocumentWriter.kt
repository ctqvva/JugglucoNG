package tk.glucodata.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Prepare the complete archive before exposing a document in the user's folder. */
internal object ScheduledBackupDocumentWriter {
    data class WrittenBackup<T>(val summary: T, val byteSize: Long)

    suspend fun <T, D : Any> write(
        cacheDirectory: File,
        prepare: suspend (File) -> T,
        createDocument: () -> D,
        openDocument: (D) -> OutputStream?,
        deleteDocument: (D) -> Unit,
        onCleanupFailure: (Throwable) -> Unit
    ): WrittenBackup<T> = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("scheduled-backup-", ".tmp", cacheDirectory)
        var document: D? = null
        var complete = false
        try {
            val summary = prepare(staged)
            val byteSize = staged.length()
            if (byteSize <= 0L) throw IOException("Prepared backup is empty")
            currentCoroutineContext().ensureActive()
            val destination = createDocument().also { document = it }
            val output = openDocument(destination)
                ?: throw IOException("Could not open backup destination")
            output.use { target ->
                staged.inputStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            complete = true
            WrittenBackup(summary, byteSize)
        } finally {
            // WorkManager cancellation must not skip removal of a partial document.
            withContext(NonCancellable) {
                if (!complete) {
                    document?.let { partial ->
                        runCatching { deleteDocument(partial) }.onFailure(onCleanupFailure)
                    }
                }
                staged.delete()
            }
        }
    }
}
