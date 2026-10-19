package tk.glucodata.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Downloads a release APK into the app's own cache directory.
 *
 * Deliberately not `Downloads/`: a half-finished APK sitting in a shared folder is exactly the
 * "find the file yourself" experience an in-app updater exists to avoid, and a world-readable
 * staging file is one more thing that could be swapped before install.
 */
object UpdateDownloader {

    private const val LOG_TAG = "AppUpdate"

    private const val DOWNLOAD_DIR = "updates"
    private const val MAX_APK_BYTES = 400L * 1024 * 1024
    /** Headroom on top of the APK itself, since the installer copies it again during commit. */
    private const val REQUIRED_FREE_MULTIPLIER = 3

    sealed interface Outcome {
        data class Ok(val file: File, val sha256: String) : Outcome
        data class Failed(val error: UpdateError) : Outcome
    }

    fun downloadDir(context: Context): File =
        File(context.applicationContext.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }

    /** Removes every staged APK. Called after a successful install and when a download fails. */
    fun clearStaged(context: Context) {
        runCatching { downloadDir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * Streams [update]'s artifact to disk, hashing as it goes.
     *
     * [onProgress] receives (bytesRead, totalBytes); totalBytes is 0 when the server does not
     * announce a length. Cancelling the calling coroutine aborts the transfer and deletes the
     * partial file.
     */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Long, Long) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        if (!UpdateSourceClient.isDownloadableUrl(update.artifact.downloadUrl)) {
            return@withContext Outcome.Failed(UpdateError.NO_ARTIFACT)
        }

        val dir = downloadDir(context)
        clearStaged(context)
        val target = File(dir, update.artifact.fileName)

        val expectedSize = update.artifact.sizeBytes
        if (expectedSize > MAX_APK_BYTES) return@withContext Outcome.Failed(UpdateError.STORAGE)
        if (expectedSize > 0 && dir.usableSpace < expectedSize * REQUIRED_FREE_MULTIPLIER) {
            return@withContext Outcome.Failed(UpdateError.STORAGE)
        }

        var connection: HttpURLConnection? = null
        try {
            connection = UpdateSourceClient.openConnection(update.artifact.downloadUrl)
            connection.setRequestProperty("Accept", "application/octet-stream")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(LOG_TAG, "update download failed, HTTP $code")
                return@withContext Outcome.Failed(UpdateError.NETWORK)
            }
            // Redirects are expected (GitHub bounces assets to its CDN), but a redirect must
            // not be allowed to quietly downgrade the transfer to plain HTTP.
            if (!UpdateSourceClient.isDownloadableUrl(connection.url.toString())) {
                Log.w(LOG_TAG, "update download redirected to a non-https URL")
                return@withContext Outcome.Failed(UpdateError.NETWORK)
            }

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            if (total > MAX_APK_BYTES) return@withContext Outcome.Failed(UpdateError.STORAGE)

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_APK_BYTES) {
                            return@withContext Outcome.Failed(UpdateError.STORAGE)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        // Repainting a progress bar per 64 KiB chunk on a 76 MB APK would be
                        // ~1200 recompositions; every 512 KiB is smooth and cheap.
                        if (written - lastReported >= 512 * 1024) {
                            lastReported = written
                            onProgress(written, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(written, total)

            if (expectedSize > 0 && written != expectedSize) {
                Log.w(LOG_TAG, "update download size mismatch: got $written, expected $expectedSize")
                target.delete()
                return@withContext Outcome.Failed(UpdateError.CHECKSUM)
            }

            val actualSha = digest.digest().toHex()
            val expectedSha = update.artifact.sha256
            if (expectedSha != null && !expectedSha.equals(actualSha, ignoreCase = true)) {
                Log.w(LOG_TAG, "update download checksum mismatch")
                target.delete()
                return@withContext Outcome.Failed(UpdateError.CHECKSUM)
            }
            Outcome.Ok(target, actualSha)
        } catch (e: kotlinx.coroutines.CancellationException) {
            target.delete()
            throw e
        } catch (_: Exception) {
            target.delete()
            Outcome.Failed(UpdateError.NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        forEachIndexed { i, byte ->
            val value = byte.toInt() and 0xff
            chars[i * 2] = digits[value ushr 4]
            chars[i * 2 + 1] = digits[value and 0x0f]
        }
        return String(chars)
    }
}
