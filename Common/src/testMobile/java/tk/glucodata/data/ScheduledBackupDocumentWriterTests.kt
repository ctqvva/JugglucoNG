package tk.glucodata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ScheduledBackupDocumentWriterTests {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun preparesAndClosesArchiveBeforeCreatingDestination() = runBlocking {
        var prepared = false
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        val result = ScheduledBackupDocumentWriter.write(
            temporary.root,
            prepare = { file ->
                file.outputStream().use { ExportPackageCodec.writeUtf8(it, payload, ExportCompression.GZIP) }
                prepared = true
                "summary"
            },
            createDocument = { assertTrue(prepared); "document" },
            openDocument = { output },
            deleteDocument = { fail("Successful backup must be kept") },
            onCleanupFailure = { throw AssertionError(it) }
        )
        assertTrue(closed)
        assertEquals("summary", result.summary)
        assertEquals(output.size().toLong(), result.byteSize)
        assertEquals(payload, ExportPackageCodec.readUtf8(output.toByteArray().inputStream()))
        assertNoStagedFiles()
    }

    @Test
    fun preparationFailureDoesNotCreateDestination() = assertPreparationFails(IOException("Export failed"))

    @Test
    fun preparationCancellationDoesNotCreateDestination() = assertPreparationFails(CancellationException("Stopped"))

    @Test
    fun emptyPreparedArchiveDoesNotCreateDestination() = runBlocking {
        val failure = runCatching {
            ScheduledBackupDocumentWriter.write(
                temporary.root, prepare = { Unit },
                createDocument = { fail("Empty archive must not create a document"); "document" },
                openDocument = { ByteArrayOutputStream() }, deleteDocument = {}, onCleanupFailure = {}
            )
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertNoStagedFiles()
    }

    @Test
    fun nullDestinationStreamRemovesDocument() = assertCopyFails(null, IOException::class.java)

    @Test
    fun writeFailureRemovesDocument() = assertCopyFails(object : OutputStream() {
        override fun write(value: Int) { throw IOException("Provider write failed") }
    }, IOException::class.java)

    @Test
    fun closeFailureRemovesDocument() = assertCopyFails(object : ByteArrayOutputStream() {
        override fun close() { throw IOException("Provider close failed") }
    }, IOException::class.java)

    @Test
    fun cancellationWhileCopyingRemovesDocument() = assertCopyFails(object : OutputStream() {
        override fun write(value: Int) { throw CancellationException("Worker stopped") }
    }, CancellationException::class.java)

    @Test
    fun cancelledWorkerClosesStreamAndRemovesPartialDocument() = runBlocking {
        lateinit var copyJob: Job
        var deleted = false
        var closed = false
        val worker = launch {
            ScheduledBackupDocumentWriter.write(
                temporary.root,
                prepare = { file ->
                    copyJob = currentCoroutineContext().job
                    file.writeText(payload)
                },
                createDocument = { "document" },
                openDocument = {
                    object : ByteArrayOutputStream() {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            super.write(bytes, offset, length)
                            copyJob.cancel()
                        }
                        override fun close() { closed = true; super.close() }
                    }
                },
                deleteDocument = { deleted = true }, onCleanupFailure = { throw AssertionError(it) }
            )
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(closed)
        assertTrue(deleted)
        assertNoStagedFiles()
    }

    @Test
    fun cleanupFailurePreservesOriginalFailure() = runBlocking {
        val original = IOException("Provider open failed")
        var cleanupFailure: Throwable? = null
        val actual = runCatching {
            ScheduledBackupDocumentWriter.write(
                temporary.root, prepare = { it.writeText(payload) }, createDocument = { "document" },
                openDocument = { throw original },
                deleteDocument = { throw IOException("Provider delete failed") },
                onCleanupFailure = { cleanupFailure = it }
            )
        }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception across dispatchers.
        assertEquals(original.javaClass, actual?.javaClass)
        assertEquals(original.message, actual?.message)
        assertEquals("Provider delete failed", cleanupFailure?.message)
        assertNoStagedFiles()
    }

    private fun assertPreparationFails(original: Exception) = runBlocking {
        val actual = runCatching {
            ScheduledBackupDocumentWriter.write(
                temporary.root, prepare = { throw original },
                createDocument = { fail("Failed export must not create a document"); "document" },
                openDocument = { ByteArrayOutputStream() }, deleteDocument = {}, onCleanupFailure = {}
            )
        }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception across dispatchers.
        assertEquals(original.javaClass, actual?.javaClass)
        assertEquals(original.message, actual?.message)
        assertNoStagedFiles()
    }

    private fun assertCopyFails(output: OutputStream?, failureType: Class<out Exception>) = runBlocking {
        var deleted = false
        val actual = runCatching {
            ScheduledBackupDocumentWriter.write(
                temporary.root, prepare = { it.writeText(payload) }, createDocument = { "document" },
                openDocument = { output }, deleteDocument = { deleted = true }, onCleanupFailure = {}
            )
        }.exceptionOrNull()
        assertTrue("Unexpected failure: $actual", failureType.isInstance(actual))
        assertTrue(deleted)
        assertNoStagedFiles()
    }

    private fun assertNoStagedFiles() = assertEquals(0, temporary.root.listFiles()!!.size)
    private val payload = """{"schema":"tk.glucodata.export-package","history":[]}"""
}
