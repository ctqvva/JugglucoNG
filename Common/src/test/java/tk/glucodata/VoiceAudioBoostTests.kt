package tk.glucodata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceAudioBoostTests {

    private fun buildPcm16Wav(
        samples: ShortArray,
        audioFormat: Short = 1,
        bitsPerSample: Short = 16,
        includeJunkChunk: Boolean = false,
    ): ByteArray {
        val dataBytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { dataBytes.putShort(it) }
        val pcm = dataBytes.array()

        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        if (includeJunkChunk) {
            body.write("JUNK".toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array())
            body.write(byteArrayOf(1, 2, 3, 4))
        }
        body.write("fmt ".toByteArray(Charsets.US_ASCII))
        val fmt = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(16) // fmt chunk size
            putShort(audioFormat)
            putShort(1) // mono
            putInt(22050)
            putInt(22050 * 2)
            putShort(2) // block align
            putShort(bitsPerSample)
        }
        body.write(fmt.array())
        body.write("data".toByteArray(Charsets.US_ASCII))
        body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcm.size).array())
        body.write(pcm)

        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bodyBytes.size).array())
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun readDataSamples(wav: ByteArray): ShortArray {
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val start = pos + 8
            if (id == "data") {
                val buf = ByteBuffer.wrap(wav, start, size).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(size / 2) { buf.short }
            }
            pos = start + size + (size and 1)
        }
        error("data chunk not found")
    }

    @Test
    fun softClipGain_amplifiesQuietSpeechLinearlyAndSaturatesPeaksWithoutOverflow() {
        val gain6Db = 1.9952623149688795
        val gain10Db = 3.1622776601683795

        // Quiet speech (~12% full scale) stays strictly below knee -> clean linear amplification.
        val quiet = 4000.toShort()
        val quietBoosted6 = VoiceAudioBoost.applySoftClipGain(quiet, gain6Db)
        assertTrue("Expected ~8000 for +6 dB, got $quietBoosted6", abs(quietBoosted6 - 7981) <= 10)
        val quietBoosted10 = VoiceAudioBoost.applySoftClipGain(quiet, gain10Db)
        assertTrue("Expected ~12649 for +10 dB, got $quietBoosted10", abs(quietBoosted10 - 12649) <= 10)

        // Silence remains exact silence.
        assertEquals(0.toShort(), VoiceAudioBoost.applySoftClipGain(0, gain10Db))

        // Extreme peaks must saturate smoothly below full scale without wrapping sign.
        val maxPos = VoiceAudioBoost.applySoftClipGain(Short.MAX_VALUE, gain10Db)
        val maxNeg = VoiceAudioBoost.applySoftClipGain(Short.MIN_VALUE, gain10Db)
        assertTrue("Positive peak must stay positive and bounded below 32767: $maxPos", maxPos in 30000..32500)
        assertTrue("Negative peak must stay negative and bounded above -32768: $maxNeg", maxNeg in -32500..-30000)
    }

    @Test
    fun boostPcm16Wav_amplifiesSamplesAcrossExtraChunksInPlace() {
        val tmp = File.createTempFile("voice_boost_test_", ".wav")
        try {
            val input = shortArrayOf(0, 3000, -3000, 28000, -28000)
            tmp.writeBytes(buildPcm16Wav(input, includeJunkChunk = true))

            assertTrue(VoiceAudioBoost.boostPcm16Wav(tmp, 6.0f))

            val output = readDataSamples(tmp.readBytes())
            assertEquals(input.size, output.size)
            assertEquals(0.toShort(), output[0])
            assertTrue("Expected +6 dB on +3000 -> ~6000, got ${output[1]}", output[1] in 5950..6050)
            assertTrue("Expected +6 dB on -3000 -> ~-6000, got ${output[2]}", output[2] in -6050..-5950)
            assertTrue("Loud peak must stay positive and soft-limited: ${output[3]}", output[3] in 28500..32500)
            assertTrue("Loud negative peak must stay negative and soft-limited: ${output[4]}", output[4] in -32500..-28500)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun boostPcm16Wav_rejectsNonPcm16AndCorruptFiles() {
        val nonPcm = File.createTempFile("voice_boost_nonpcm_", ".wav")
        val corrupt = File.createTempFile("voice_boost_corrupt_", ".wav")
        try {
            nonPcm.writeBytes(buildPcm16Wav(shortArrayOf(1000, 2000), audioFormat = 3, bitsPerSample = 32))
            assertFalse(VoiceAudioBoost.boostPcm16Wav(nonPcm, 6.0f))

            corrupt.writeBytes("not a wav file".toByteArray())
            assertFalse(VoiceAudioBoost.boostPcm16Wav(corrupt, 6.0f))
        } finally {
            nonPcm.delete()
            corrupt.delete()
        }
    }

    @Test
    fun volumeLevels_mapToExpectedBoostFlagsAndGains() {
        assertEquals(VoiceAudioBoost.LEVEL_SOFTER, VoiceAudioBoost.normalizeLevel(-5))
        assertEquals(VoiceAudioBoost.LEVEL_MAX, VoiceAudioBoost.normalizeLevel(99))
        assertFalse(VoiceAudioBoost.needsBoostedPlayback(VoiceAudioBoost.LEVEL_SOFTER))
        assertFalse(VoiceAudioBoost.needsBoostedPlayback(VoiceAudioBoost.LEVEL_NORMAL))
        assertTrue(VoiceAudioBoost.needsBoostedPlayback(VoiceAudioBoost.LEVEL_LOUDER))
        assertTrue(VoiceAudioBoost.needsBoostedPlayback(VoiceAudioBoost.LEVEL_MAX))
        assertEquals(6.0f, VoiceAudioBoost.gainDbForLevel(VoiceAudioBoost.LEVEL_LOUDER), 0.01f)
        assertEquals(10.0f, VoiceAudioBoost.gainDbForLevel(VoiceAudioBoost.LEVEL_MAX), 0.01f)
    }
}
