package tk.glucodata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneOutgoingRecoveryProtocolTests {
    @Test
    fun durableStateRoundTripsEveryPhase() {
        CloneOutgoingPhase.entries.forEach { phase ->
            val state = state(
                phase = phase,
                jobId = if (phase == CloneOutgoingPhase.PROBING ||
                    phase == CloneOutgoingPhase.PROBE_READY
                ) null else JOB_ID,
                remoteTerminal = phase == CloneOutgoingPhase.COMPLETED ||
                    phase == CloneOutgoingPhase.CANCELLED,
            )

            assertEquals(
                state,
                CloneOutgoingRecoveryProtocol.decodeState(
                    CloneOutgoingRecoveryProtocol.encodeState(state)
                ),
            )
        }
    }

    @Test
    fun binaryActionsRoundTripExactPathsAndReadLengthMetadata() {
        val readLength = CloneOutgoingRecoveryProtocol.readRequestBytes()
        val actions = listOf(
            CloneOutgoingAction(
                CloneOutgoingActionKind.PROBE_CAPABILITIES,
                0L,
                CloneHistoryRecoveryProtocol.CAPABILITY_PATH,
                readLength,
            ),
            CloneOutgoingAction(
                CloneOutgoingActionKind.PUT_MANIFEST,
                0L,
                CloneHistoryRecoveryProtocol.jobManifestPath(JOB_ID),
                byteArrayOf(1),
            ),
            CloneOutgoingAction(
                CloneOutgoingActionKind.PUT_PACKAGE_CHUNK,
                123L,
                CloneHistoryRecoveryProtocol.jobPackagePath(JOB_ID),
                byteArrayOf(2, 3),
            ),
            CloneOutgoingAction(
                CloneOutgoingActionKind.PUT_COMMIT,
                0L,
                CloneHistoryRecoveryProtocol.jobCommitPath(JOB_ID),
                byteArrayOf(4),
            ),
            CloneOutgoingAction(
                CloneOutgoingActionKind.GET_STATUS,
                64L,
                CloneHistoryRecoveryProtocol.jobStatusPath(JOB_ID),
                readLength,
            ),
            CloneOutgoingAction(
                CloneOutgoingActionKind.PUT_CANCEL,
                0L,
                CloneHistoryRecoveryProtocol.jobCancelPath(JOB_ID),
                byteArrayOf(5),
            ),
        )

        actions.forEach { action ->
            val encoded = CloneOutgoingRecoveryProtocol.encodeAction(action)
            assertEquals(CloneOutgoingRecoveryProtocol.ACTION_VERSION, encoded[0].toInt())
            assertEquals(action, CloneOutgoingRecoveryProtocol.decodeAction(encoded))
        }
        assertEquals(
            CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES,
            CloneOutgoingRecoveryProtocol.requestedReadBytes(readLength),
        )
    }

    @Test
    fun resultFrameRetainsGenerationAndBoundsOneReadPage() {
        val result = CloneOutgoingResult(
            CloneOutgoingResultOutcome.OK,
            connectionGeneration = 42L,
            payload = "status".toByteArray(),
        )

        assertEquals(
            result,
            CloneOutgoingRecoveryProtocol.decodeResult(
                CloneOutgoingRecoveryProtocol.encodeResult(result)
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryProtocol.encodeResult(
                result.copy(
                    payload = ByteArray(CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES + 1)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryProtocol.encodeResult(
                result.copy(
                    outcome = CloneOutgoingResultOutcome.TRANSPORT_ERROR,
                    payload = byteArrayOf(1),
                )
            )
        }
    }

    @Test
    fun frameDecoderRejectsLengthOverflowAndPathSubstitution() {
        val action = CloneOutgoingAction(
            CloneOutgoingActionKind.GET_STATUS,
            0L,
            CloneHistoryRecoveryProtocol.jobStatusPath(JOB_ID),
            CloneOutgoingRecoveryProtocol.readRequestBytes(),
        )
        val malformedLength = CloneOutgoingRecoveryProtocol.encodeAction(action).also { raw ->
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryProtocol.decodeAction(malformedLength)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryProtocol.encodeAction(
                action.copy(path = "other/${action.path}")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryProtocol.validateIceLabel("label\nwith-control")
        }
        assertTrue(
            CloneOutgoingRecoveryProtocol.encodeState(state()).toByteArray().size <=
                CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES
        )
    }

    @Test
    fun capabilityTransitionEnforcesPeerSizeAndPreservesReconciliationRetries() {
        val capabilities = CloneRecoveryCapabilities(
            minimumProtocolVersion = 1,
            maximumProtocolVersion = 1,
            categories = CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
            maximumChunkBytes = 16 * 1024,
            maximumCompressedBytes = 1_024L,
        )
        val reconciling = state().copy(
            phase = CloneOutgoingPhase.PROBING,
            capabilityGeneration = null,
            reconcileAfterProbe = true,
            retryCount = 7,
        )

        val accepted = CloneOutgoingRecoveryTransitions.acceptCapabilities(
            reconciling,
            capabilities,
            CloneRecoveryCategories.GLUCOSE,
            compressedBytes = 1_024L,
        )

        assertEquals(CloneOutgoingPhase.POLLING_STATUS, accepted.phase)
        assertEquals(7, accepted.retryCount)
        assertEquals(1_024L, accepted.remoteMaximumCompressedBytes)
        assertThrows(IllegalArgumentException::class.java) {
            CloneOutgoingRecoveryTransitions.acceptCapabilities(
                reconciling,
                capabilities,
                CloneRecoveryCategories.GLUCOSE,
                compressedBytes = 1_025L,
            )
        }
    }

    @Test
    fun cancellationAndCleanupDistinguishLocalFromEstablishedRemoteJobs() {
        val preparing = state(phase = CloneOutgoingPhase.PREPARING)
            .copy(capabilityGeneration = null)
        val localCancel = CloneOutgoingRecoveryTransitions.requestCancel(preparing)
        assertEquals(CloneOutgoingPhase.CANCELLED, localCancel.phase)
        assertTrue(CloneOutgoingRecoveryTransitions.mayClear(localCancel))
        assertTrue(
            CloneOutgoingRecoveryTransitions.mayClear(
                preparing.copy(phase = CloneOutgoingPhase.FAILED, error = "failed")
            )
        )

        val reconnecting = preparing.copy(
            phase = CloneOutgoingPhase.PROBING,
            reconcileAfterProbe = true,
            remoteJobEstablished = true,
        )
        val remoteCancel = CloneOutgoingRecoveryTransitions.requestCancel(reconnecting)
        assertEquals(CloneOutgoingPhase.PROBING, remoteCancel.phase)
        assertFalse(CloneOutgoingRecoveryTransitions.mayClear(remoteCancel))
    }

    @Test
    fun durableRemoteCommitPhasesReplayCommitAfterReceiverRestart() {
        val active = state().copy(
            phase = CloneOutgoingPhase.POLLING_STATUS,
            remoteJobEstablished = true,
        )

        val verifying = CloneOutgoingRecoveryTransitions.continueRemoteCommit(
            active,
            CloneRecoveryPhase.VERIFYING,
            acceptedBytes = 512L,
        )
        assertEquals(CloneOutgoingPhase.PUTTING_COMMIT, verifying.phase)
        assertEquals(512L, verifying.nextOffset)

        val cancelBeforeImport = CloneOutgoingRecoveryTransitions.continueRemoteCommit(
            active.copy(cancelRequested = true),
            CloneRecoveryPhase.VERIFYING,
            acceptedBytes = 512L,
        )
        assertEquals(CloneOutgoingPhase.PUTTING_CANCEL, cancelBeforeImport.phase)
        assertTrue(cancelBeforeImport.cancelRequested)

        val tooLateToCancel = CloneOutgoingRecoveryTransitions.continueRemoteCommit(
            active.copy(cancelRequested = true),
            CloneRecoveryPhase.IMPORTING,
            acceptedBytes = 512L,
        )
        assertEquals(CloneOutgoingPhase.PUTTING_COMMIT, tooLateToCancel.phase)
        assertFalse(tooLateToCancel.cancelRequested)
    }

    private fun state(
        phase: CloneOutgoingPhase = CloneOutgoingPhase.PUTTING_PACKAGE,
        jobId: String? = JOB_ID,
        remoteTerminal: Boolean = false,
    ) = CloneOutgoingState(
        iceLabel = "stable-ice-label",
        connectionGeneration = 7L,
        jobId = jobId,
        phase = phase,
        nextOffset = 123L,
        maximumChunkBytes = 32 * 1024,
        negotiatedProtocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
        remoteCategories = CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
        remoteMaximumCompressedBytes = CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES,
        capabilityGeneration = 7L,
        reconcileAfterProbe = false,
        remoteJobEstablished = remoteTerminal,
        cancelRequested = phase == CloneOutgoingPhase.CANCELLED,
        remoteTerminal = remoteTerminal,
    )

    companion object {
        private const val JOB_ID = "0123456789abcdef0123456789abcdef"
    }
}
