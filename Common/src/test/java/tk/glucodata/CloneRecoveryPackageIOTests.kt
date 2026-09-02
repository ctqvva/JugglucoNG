package tk.glucodata

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class CloneRecoveryPackageIOTests {
    @Test
    fun packageRoundTripPreservesRecordsAndUnicode() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(
                file = file,
                declaredRecordTypes = setOf("glucose", "journal"),
            ) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 1000L).put("value", 123.0))
                sink.write("journal", JSONObject().put("note", "Kaffee und Käse"))
            }
            val manifest = manifest(stats)
            val records = mutableListOf<CloneRecoveryRecord>()

            CloneRecoveryPackageIO.visitValidated(file, manifest, visitor = records::add)

            assertEquals(listOf("glucose", "journal"), records.map(CloneRecoveryRecord::type))
            assertEquals("Kaffee und Käse", records[1].payload.getString("note"))
            assertEquals(mapOf("glucose" to 1L, "journal" to 1L), stats.recordCounts)
        }
    }

    @Test
    fun declaredCategoryCanContainNoRecords() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(
                file = file,
                declaredRecordTypes = setOf("glucose", "journal"),
            ) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 1000L))
            }

            assertEquals(0L, stats.recordCounts.getValue("journal"))
            CloneRecoveryPackageIO.validate(file, manifest(stats))
        }
    }

    @Test
    fun emptyHistoryPackageRemainsValidForFullReplacement() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(
                file = file,
                declaredRecordTypes = setOf("glucose"),
            ) {}

            assertEquals(0L, stats.uncompressedBytes)
            assertEquals(mapOf("glucose" to 0L), stats.recordCounts)
            CloneRecoveryPackageIO.validate(
                file,
                manifest(stats).copy(
                    mode = CloneRecoveryMode.FULL_HISTORY,
                    categories = CloneRecoveryCategories.GLUCOSE,
                ),
            )
        }
    }

    @Test
    fun corruptedPackageIsRejectedBeforeVisitorRuns() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 1000L))
            }
            val manifest = manifest(stats)
            val bytes = file.readBytes()
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            var visits = 0

            assertIllegalArgument {
                CloneRecoveryPackageIO.visitValidated(
                    file = file,
                    manifest = manifest,
                    visitor = { visits++ },
                )
            }
            assertEquals(0, visits)
        }
    }

    @Test
    fun wrongRecordCountIsRejectedBeforeVisitorRuns() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 1000L))
            }
            val manifest = manifest(stats).copy(recordCounts = mapOf("glucose" to 2L))
            var visits = 0

            assertIllegalArgument {
                CloneRecoveryPackageIO.visitValidated(
                    file = file,
                    manifest = manifest,
                    visitor = { visits++ },
                )
            }
            assertEquals(0, visits)
        }
    }

    @Test
    fun semanticValidationFinishesBeforeVisitorRuns() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 0L))
                sink.write("glucose", JSONObject().put("timestamp", 1000L))
            }
            var visits = 0

            assertIllegalArgument {
                CloneRecoveryPackageIO.visitValidated(
                    file = file,
                    manifest = manifest(stats).copy(
                        categories = CloneRecoveryCategories.GLUCOSE,
                    ),
                    recordValidator = { record ->
                        require(record.payload.optLong("timestamp", 0L) > 0L) {
                            "Invalid glucose timestamp"
                        }
                    },
                    visitor = { visits++ },
                )
            }
            assertEquals(0, visits)
        }
    }

    @Test
    fun malformedUtf8IsRejectedBeforeVisitorRuns() = runBlocking {
        withTempPackage { file ->
            val raw = byteArrayOf(0xc3.toByte(), '\n'.code.toByte())
            GZIPOutputStream(file.outputStream()).use { it.write(raw) }
            val stats = CloneRecoveryPackageStats(
                compressedBytes = file.length(),
                uncompressedBytes = raw.size.toLong(),
                recordCounts = mapOf("glucose" to 1L),
                sha256 = CloneHistoryRecoveryProtocol.sha256(file),
            )
            var visits = 0

            val error = assertIllegalArgument {
                CloneRecoveryPackageIO.visitValidated(
                    file = file,
                    manifest = manifest(stats).copy(
                        categories = CloneRecoveryCategories.GLUCOSE,
                    ),
                    visitor = { visits++ },
                )
            }
            assertTrue(error.message.orEmpty().contains("UTF-8"))
            assertEquals(0, visits)
        }
    }

    @Test
    fun undeclaredRecordTypeCannotBeWritten() = runBlocking {
        withTempPackage { file ->
            val error = assertIllegalArgument {
                CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                    sink.write("journal", JSONObject().put("note", "unexpected"))
                }
            }

            assertTrue(error.message.orEmpty().contains("Undeclared"))
            assertTrue(!file.exists())
        }
    }

    @Test
    fun oversizedSingleRecordCannotBeWritten() = runBlocking {
        withTempPackage { file ->
            val oversized = "x".repeat(CloneHistoryRecoveryProtocol.MAXIMUM_RECORD_BYTES)

            assertIllegalArgument {
                CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                    sink.write("glucose", JSONObject().put("value", oversized))
                }
            }
        }
    }

    @Test
    fun replacementStartsOnlyAfterValidationAndInsideTransaction() = runBlocking {
        withTempPackage { file ->
            val stats = CloneRecoveryPackageIO.write(file, setOf("glucose")) { sink ->
                sink.write("glucose", JSONObject().put("timestamp", 1000L))
            }
            val events = mutableListOf<String>()

            CloneRecoveryPackageIO.visitValidated(
                file = file,
                manifest = manifest(stats).copy(mode = CloneRecoveryMode.FULL_HISTORY),
                recordValidator = { events += "validate" },
                transaction = { operation ->
                    events += "transaction-start"
                    operation()
                    events += "transaction-end"
                },
                beforeVisit = { events += "replace" },
                afterVisit = { events += "finish-import" },
                visitor = { events += "import" },
            )

            assertEquals(
                listOf(
                    "validate",
                    "transaction-start",
                    "replace",
                    "import",
                    "finish-import",
                    "transaction-end",
                ),
                events,
            )
        }
    }

    private fun manifest(stats: CloneRecoveryPackageStats): CloneRecoveryManifest =
        CloneRecoveryManifest(
            protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
            jobId = "0123456789abcdef0123456789abcdef",
            direction = CloneRecoveryDirection.SEND_TO_RECEIVER,
            mode = CloneRecoveryMode.ONLY_MISSING,
            categories = CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
            compressedBytes = stats.compressedBytes,
            uncompressedBytes = stats.uncompressedBytes,
            recordCounts = stats.recordCounts,
            sha256 = stats.sha256,
        )

    private suspend fun withTempPackage(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("clone-recovery-package-test").toFile()
        try {
            block(File(directory, "package.jsonl.gz"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun assertIllegalArgument(
        block: suspend () -> Unit,
    ): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        throw AssertionError("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
