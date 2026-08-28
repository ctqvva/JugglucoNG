package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto

class AiDexPreSessionRecoveryTests {

    private val serial = "22222FZXKT"
    private val snSecret = SerialCrypto.deriveSecret(serial)
    private val snIv = SerialCrypto.deriveIv(serial)

    private fun rejection() = byteArrayOf(0x00)

    @Test
    fun attemptsOnExplicitSingleZeroFromFGenerationSensor() {
        val recovery = AiDexPreSessionRecovery()
        assertTrue(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = false,
                response = rejection(),
            )
        )
    }

    @Test
    fun skipsNonFGenerationHardware() {
        val recovery = AiDexPreSessionRecovery()
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = false,
                challengeWritten = true,
                hasPairKey = false,
                response = rejection(),
            )
        )
    }

    @Test
    fun skipsShortResponsesThatAreNotZero() {
        val recovery = AiDexPreSessionRecovery()
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = false,
                response = byteArrayOf(0x01),
            )
        )
    }

    @Test
    fun skipsMultiByteShortResponses() {
        val recovery = AiDexPreSessionRecovery()
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = false,
                response = byteArrayOf(0x00, 0x00),
            )
        )
    }

    @Test
    fun skipsWhenChallengeWasNeverWritten() {
        val recovery = AiDexPreSessionRecovery()
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = false,
                hasPairKey = false,
                response = rejection(),
            )
        )
    }

    @Test
    fun skipsWhenAPairKeyAlreadyExists() {
        val recovery = AiDexPreSessionRecovery()
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = true,
                response = rejection(),
            )
        )
    }

    @Test
    fun firesOnlyOncePerConnection() {
        val recovery = AiDexPreSessionRecovery()
        assertTrue(recovery.begin())
        assertFalse(recovery.begin())
        assertFalse(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = false,
                response = rejection(),
            )
        )
    }

    @Test
    fun resetRearmsForTheNextConnection() {
        val recovery = AiDexPreSessionRecovery()
        recovery.begin()
        recovery.nextFrame(snSecret, snIv)
        recovery.reset()

        assertFalse(recovery.hasStarted)
        assertTrue(
            recovery.shouldAttempt(
                isFGeneration = true,
                challengeWritten = true,
                hasPairKey = false,
                response = rejection(),
            )
        )
        assertEquals(
            AiDexPreSessionRecovery.Candidate.PLAINTEXT,
            recovery.nextFrame(snSecret, snIv)?.candidate
        )
    }

    @Test
    fun plaintextCandidateIsABareClearStorageFrame() {
        val recovery = AiDexPreSessionRecovery()
        val frame = recovery.frames(snSecret, snIv).first()

        assertEquals(AiDexPreSessionRecovery.Candidate.PLAINTEXT, frame.candidate)
        assertArrayEquals(Crc16CcittFalse.makeCommand(AiDexOpcodes.CLEAR_STORAGE), frame.bytes)
        assertEquals(0xF3, frame.bytes[0].toInt() and 0xFF)
        assertTrue(Crc16CcittFalse.validateResponse(frame.bytes))
    }

    @Test
    fun snSecretCandidateIsDistinctCiphertextOfTheSameLength() {
        val recovery = AiDexPreSessionRecovery()
        val frames = recovery.frames(snSecret, snIv)

        assertEquals(2, frames.size)
        val plaintext = frames[0].bytes
        val encrypted = frames[1].bytes
        assertEquals(AiDexPreSessionRecovery.Candidate.SN_SECRET, frames[1].candidate)
        assertEquals(plaintext.size, encrypted.size)
        assertFalse(plaintext.contentEquals(encrypted))
    }

    @Test
    fun framesAreHandedOutInOrderThenExhausted() {
        val recovery = AiDexPreSessionRecovery()
        recovery.begin()

        assertEquals(
            AiDexPreSessionRecovery.Candidate.PLAINTEXT,
            recovery.nextFrame(snSecret, snIv)?.candidate
        )
        assertEquals(
            AiDexPreSessionRecovery.Candidate.SN_SECRET,
            recovery.nextFrame(snSecret, snIv)?.candidate
        )
        assertNull(recovery.nextFrame(snSecret, snIv))
    }

    @Test
    fun neverEmitsResetOrDeleteBondOpcodes() {
        val recovery = AiDexPreSessionRecovery()
        val plaintext = recovery.frames(snSecret, snIv).first().bytes

        assertNotNull(plaintext)
        assertEquals(3, plaintext.size)
        val opcode = plaintext[0].toInt() and 0xFF
        assertFalse("must never send RESET", opcode == AiDexOpcodes.RESET)
        assertFalse("must never send DELETE_BOND", opcode == AiDexOpcodes.DELETE_BOND)
        assertFalse("must never send SHELF_MODE", opcode == AiDexOpcodes.SHELF_MODE)
    }
}
