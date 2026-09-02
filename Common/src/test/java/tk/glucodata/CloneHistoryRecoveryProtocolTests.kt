package tk.glucodata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CloneHistoryRecoveryProtocolTests {
    @Test
    fun categorySelectionAlwaysIncludesGlucose() {
        assertEquals(
            CloneRecoveryCategories.GLUCOSE,
            CloneRecoveryCategories.selected(
                includeJournal = false,
                includeHypoClassifications = false,
            ),
        )
        assertEquals(
            CloneRecoveryCategories.ALL,
            CloneRecoveryCategories.selected(
                includeJournal = true,
                includeHypoClassifications = true,
            ),
        )
    }

    @Test
    fun capabilitiesRoundTripAndNegotiateCurrentVersion() {
        val capabilities = CloneHistoryRecoveryProtocol.localCapabilities(
            CloneRecoveryCategories.ALL,
        )

        val decoded = CloneHistoryRecoveryProtocol.decodeCapabilities(
            CloneHistoryRecoveryProtocol.encodeCapabilities(capabilities),
        )

        assertEquals(capabilities, decoded)
        assertEquals(1, CloneHistoryRecoveryProtocol.negotiatedProtocolVersion(decoded))
        assertEquals(
            CloneHistoryRecoveryProtocol.DEFAULT_CHUNK_BYTES,
            CloneHistoryRecoveryProtocol.negotiatedChunkBytes(decoded),
        )
    }

    @Test
    fun peerWhoseMinimumVersionIsNewerDoesNotNegotiate() {
        val remote = CloneRecoveryCapabilities(
            minimumProtocolVersion = 2,
            maximumProtocolVersion = 3,
            categories = CloneRecoveryCategories.GLUCOSE,
            maximumChunkBytes = 32 * 1024,
            maximumCompressedBytes = 1024 * 1024,
        )

        assertNull(CloneHistoryRecoveryProtocol.negotiatedProtocolVersion(remote))
    }

    @Test
    fun manifestRoundTripRetainsModeCategoriesAndCounts() {
        val manifest = validManifest()

        val decoded = CloneHistoryRecoveryProtocol.decodeManifest(
            CloneHistoryRecoveryProtocol.encodeManifest(manifest),
        )

        assertEquals(manifest, decoded)
    }

    @Test
    fun malformedManifestCannotOmitGlucose() {
        val raw = JSONObject(CloneHistoryRecoveryProtocol.encodeManifest(validManifest()))
            .put("categories", CloneRecoveryCategories.JOURNAL)
            .toString()

        val error = assertThrows(IllegalArgumentException::class.java) {
            CloneHistoryRecoveryProtocol.decodeManifest(raw)
        }
        assertTrue(error.message.orEmpty().contains("Glucose history"))
    }

    @Test
    fun malformedManifestCannotUseUnknownCategories() {
        val raw = JSONObject(CloneHistoryRecoveryProtocol.encodeManifest(validManifest()))
            .put("categories", CloneRecoveryCategories.ALL or (1 shl 12))
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            CloneHistoryRecoveryProtocol.decodeManifest(raw)
        }
    }

    @Test
    fun malformedManifestCannotEscapeThroughJobIdentifier() {
        val raw = JSONObject(CloneHistoryRecoveryProtocol.encodeManifest(validManifest()))
            .put("jobId", "../../databases/glucose_history")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            CloneHistoryRecoveryProtocol.decodeManifest(raw)
        }
    }

    @Test
    fun malformedManifestCannotCoerceNumericIdentityToString() {
        val raw = JSONObject(CloneHistoryRecoveryProtocol.encodeManifest(validManifest()))
            .put("jobId", 1234)
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            CloneHistoryRecoveryProtocol.decodeManifest(raw)
        }
    }

    @Test
    fun malformedManifestCannotExceedPackageLimit() {
        val raw = JSONObject(CloneHistoryRecoveryProtocol.encodeManifest(validManifest()))
            .put("compressedBytes", CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES + 1L)
            .put("uncompressedBytes", CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES + 1L)
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            CloneHistoryRecoveryProtocol.decodeManifest(raw)
        }
    }

    @Test
    fun smallPackageMayHaveMoreGzipBytesThanPayloadBytes() {
        val manifest = validManifest().copy(
            compressedBytes = 48,
            uncompressedBytes = 12,
        )

        assertEquals(
            manifest,
            CloneHistoryRecoveryProtocol.decodeManifest(
                CloneHistoryRecoveryProtocol.encodeManifest(manifest),
            ),
        )
    }

    @Test
    fun digestUsesStreamingSha256() {
        val digest = CloneHistoryRecoveryProtocol.sha256(
            ByteArrayInputStream("clone history".toByteArray()),
        )

        assertEquals(
            "ad1e06d05c7e7fa56886c974f5ae86eff93cac056a9c269e8e2f5a5ec3f11e69",
            digest,
        )
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(digest.any(Char::isUpperCase))
    }

    private fun validManifest(): CloneRecoveryManifest = CloneRecoveryManifest(
        protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
        jobId = "0123456789abcdef0123456789abcdef",
        direction = CloneRecoveryDirection.RECOVER_FROM_RECEIVER,
        mode = CloneRecoveryMode.ONLY_MISSING,
        categories = CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
        compressedBytes = 1024,
        uncompressedBytes = 4096,
        recordCounts = mapOf(
            "glucose" to 20,
            "journal" to 2,
        ),
        sha256 = "a".repeat(64),
    )
}
