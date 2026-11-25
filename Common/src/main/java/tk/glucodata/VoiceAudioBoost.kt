package tk.glucodata

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Voice loudness preset and boosted TTS playback helper.
 *
 * - [LEVEL_SOFTER] uses native [TextToSpeech.Engine.KEY_PARAM_VOLUME] attenuation.
 * - [LEVEL_NORMAL] leaves the direct [TextToSpeech.speak] path completely unchanged.
 * - [LEVEL_LOUDER] (+6 dB) and [LEVEL_MAX] (+10 dB) synthesize to an app-private cache WAV via
 *   [TextToSpeech.synthesizeToFile], apply peak-safe soft-limited PCM amplification (avoiding
 *   Google TTS ignoring `KEY_PARAM_SESSION_ID`), and play over [MediaPlayer] with the exact
 *   requested [AudioAttributes]. Any synthesis or decode failure falls back to direct TTS.
 */
object VoiceAudioBoost {
    private const val LOG_ID = "VoiceAudioBoost"
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_VOLUME_LEVEL = "voice_volume_level"
    private const val TEMP_FILE_PREFIX = "tts_boost_"
    private const val TEMP_FILE_SUFFIX = ".wav"
    private const val MAX_WAV_BYTES = 4 * 1024 * 1024
    private const val CLIP_TIMEOUT_SECONDS = 30L
    private const val SOFT_CLIP_KNEE = 0.70
    private const val SOFT_CLIP_HEADROOM = 0.28

    const val LEVEL_SOFTER: Int = 0
    const val LEVEL_NORMAL: Int = 1
    const val LEVEL_LOUDER: Int = 2
    const val LEVEL_MAX: Int = 3

    val LEVEL_PRESETS: List<Int> = listOf(LEVEL_SOFTER, LEVEL_NORMAL, LEVEL_LOUDER, LEVEL_MAX)

    private const val SOFTER_TTS_VOLUME = 0.55f
    private const val LOUDER_GAIN_DB = 6.0f
    private const val MAX_GAIN_DB = 10.0f

    private val tempFileSeq = AtomicLong(0L)
    private val activePlayerLock = Any()
    private var activePlayer: MediaPlayer? = null
    private var activeLatch: CountDownLatch? = null

    private val playbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceBoostPlayback").apply { isDaemon = true }
    }

    @JvmStatic
    fun normalizeLevel(level: Int): Int = level.coerceIn(LEVEL_SOFTER, LEVEL_MAX)

    @JvmStatic
    fun getLevel(context: Context?): Int {
        if (context == null) return LEVEL_NORMAL
        return normalizeLevel(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VOLUME_LEVEL, LEVEL_NORMAL)
        )
    }

    @JvmStatic
    fun setLevel(context: Context, level: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOLUME_LEVEL, normalizeLevel(level))
            .apply()
    }

    @JvmStatic
    fun gainDbForLevel(level: Int): Float = when (normalizeLevel(level)) {
        LEVEL_LOUDER -> LOUDER_GAIN_DB
        LEVEL_MAX -> MAX_GAIN_DB
        else -> 0f
    }

    @JvmStatic
    fun needsBoostedPlayback(level: Int): Boolean = gainDbForLevel(level) > 0f

    @JvmStatic
    fun createTtsParams(level: Int): Bundle? = when (normalizeLevel(level)) {
        LEVEL_SOFTER -> Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, SOFTER_TTS_VOLUME)
        }
        else -> null
    }

    @JvmStatic
    fun createTempWavFile(context: Context): File? = try {
        val dir = context.applicationContext.cacheDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        File(dir, "$TEMP_FILE_PREFIX${System.currentTimeMillis()}_${tempFileSeq.incrementAndGet()}$TEMP_FILE_SUFFIX")
    } catch (t: Throwable) {
        Log.stack(LOG_ID, "createTempWavFile", t)
        null
    }

    @JvmStatic
    fun cleanupStaleCacheFiles(context: Context?) {
        val app = context?.applicationContext ?: return
        try {
            playbackExecutor.execute {
                try {
                    val dir = app.cacheDir ?: return@execute
                    dir.listFiles { _, name ->
                        name.startsWith(TEMP_FILE_PREFIX) && name.endsWith(TEMP_FILE_SUFFIX)
                    }?.forEach { file ->
                        try {
                            file.delete()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun stopActivePlayback() {
        val player: MediaPlayer?
        val latch: CountDownLatch?
        synchronized(activePlayerLock) {
            player = activePlayer
            latch = activeLatch
            activePlayer = null
            activeLatch = null
        }
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Throwable) {
            }
        }
        latch?.countDown()
    }

    @JvmStatic
    fun playAsync(
        context: Context,
        speechFile: File,
        attr: AudioAttributes?,
        chime: Boolean,
        repeatTwice: Boolean,
        gainDb: Float,
        isCancelled: () -> Boolean,
        onFinished: Runnable,
        onFallbackSpeak: Runnable,
    ) {
        val app = context.applicationContext
        try {
            playbackExecutor.execute {
                var needsFallback = false
                try {
                    if (isCancelled()) return@execute
                    if (!speechFile.exists() || speechFile.length() <= 44L) {
                        Log.w(LOG_ID, "synthesized WAV missing or empty; falling back to direct TTS")
                        needsFallback = true
                        return@execute
                    }
                    val pcmBoosted = boostPcm16Wav(speechFile, gainDb)
                    val fallbackEnhancerMb = if (pcmBoosted) 0 else (gainDb * 100f).roundToInt()

                    // Best-effort lead-in & optional chime matching LeadIn.queue().
                    if (!isCancelled()) {
                        playRawClip(app, R.raw.voice_lead_in, attr, isCancelled)
                    }
                    if (chime && !isCancelled()) {
                        playRawClip(app, R.raw.voice_chime, attr, isCancelled)
                    }
                    if (isCancelled()) return@execute

                    val firstPlayed = playFileClip(speechFile, attr, fallbackEnhancerMb, isCancelled)
                    if (!firstPlayed) {
                        if (!isCancelled()) {
                            needsFallback = true
                        }
                        return@execute
                    }
                    if (repeatTwice && !isCancelled()) {
                        playRawClip(app, R.raw.voice_lead_in, attr, isCancelled)
                        if (!isCancelled()) {
                            playFileClip(speechFile, attr, fallbackEnhancerMb, isCancelled)
                        }
                    }
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "playAsync", t)
                    if (!isCancelled()) {
                        needsFallback = true
                    }
                } finally {
                    try {
                        speechFile.delete()
                    } catch (_: Throwable) {
                    }
                    onFinished.run()
                    if (needsFallback && !isCancelled()) {
                        onFallbackSpeak.run()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "playAsync submit", t)
            try {
                speechFile.delete()
            } catch (_: Throwable) {
            }
            onFinished.run()
            if (!isCancelled()) {
                onFallbackSpeak.run()
            }
        }
    }

    /**
     * Soft-knee limiter + gain for a single 16-bit PCM sample.
     * Linear below [SOFT_CLIP_KNEE] (0.70 full-scale) for clean speech clarity, and C1-smooth
     * asymptotic saturation approaching 0.98 full-scale above the knee so peaks never clip or wrap.
     */
    internal fun applySoftClipGain(sample: Short, linearGain: Double): Short {
        val x = sample.toDouble() / 32768.0
        val y = x * linearGain
        val ay = abs(y)
        val out = if (ay <= SOFT_CLIP_KNEE) {
            y
        } else {
            sign(y) * (SOFT_CLIP_KNEE + SOFT_CLIP_HEADROOM * tanh((ay - SOFT_CLIP_KNEE) / SOFT_CLIP_HEADROOM))
        }
        return (out * 32767.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Amplifies an uncompressed 16-bit PCM WAV file in place using [applySoftClipGain].
     * Returns `true` if the WAV header was valid PCM16 and samples were amplified, `false` otherwise.
     */
    internal fun boostPcm16Wav(file: File, gainDb: Float): Boolean {
        if (gainDb <= 0f) return true
        if (!file.exists()) return false
        val len = file.length()
        if (len <= 44L || len > MAX_WAV_BYTES) return false

        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "boostPcm16Wav read", t)
            return false
        }
        if (bytes.size < 44) return false
        if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WAVE")) {
            return false
        }

        var audioFormat = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkSize = readUInt32LE(bytes, pos + 4)
            val chunkDataStart = pos + 8
            if (chunkSize < 0L || chunkDataStart > bytes.size) break
            val available = (bytes.size - chunkDataStart).toLong().coerceAtMost(chunkSize).toInt()
            if (matchesAscii(bytes, pos, "fmt ") && available >= 16) {
                audioFormat = readUInt16LE(bytes, chunkDataStart)
                bitsPerSample = readUInt16LE(bytes, chunkDataStart + 14)
            } else if (matchesAscii(bytes, pos, "data")) {
                dataOffset = chunkDataStart
                dataSize = available - (available and 1)
                break
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            val next = chunkDataStart.toLong() + paddedSize
            if (next <= pos.toLong() || next > bytes.size.toLong()) break
            pos = next.toInt()
        }

        if (audioFormat != 1 || bitsPerSample != 16 || dataOffset < 0 || dataSize < 2) {
            return false
        }

        val linearGain = 10.0.pow(gainDb.toDouble() / 20.0)
        val end = dataOffset + dataSize
        var i = dataOffset
        while (i + 1 < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            val boosted = applySoftClipGain(sample, linearGain).toInt()
            bytes[i] = (boosted and 0xFF).toByte()
            bytes[i + 1] = ((boosted ushr 8) and 0xFF).toByte()
            i += 2
        }

        return try {
            file.writeBytes(bytes)
            true
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "boostPcm16Wav write", t)
            false
        }
    }

    private fun playRawClip(
        context: Context,
        rawResId: Int,
        attr: AudioAttributes?,
        isCancelled: () -> Boolean,
    ): Boolean {
        val uri = Uri.parse("android.resource://${context.packageName}/$rawResId")
        return playMediaPlayer(attr, fallbackEnhancerMb = 0, isCancelled = isCancelled) { mp ->
            mp.setDataSource(context, uri)
        }
    }

    private fun playFileClip(
        file: File,
        attr: AudioAttributes?,
        fallbackEnhancerMb: Int,
        isCancelled: () -> Boolean,
    ): Boolean = playMediaPlayer(attr, fallbackEnhancerMb = fallbackEnhancerMb, isCancelled = isCancelled) { mp ->
        mp.setDataSource(file.absolutePath)
    }

    private fun playMediaPlayer(
        attr: AudioAttributes?,
        fallbackEnhancerMb: Int,
        isCancelled: () -> Boolean,
        configureSource: (MediaPlayer) -> Unit,
    ): Boolean {
        if (isCancelled()) return false
        var player: MediaPlayer? = null
        var enhancer: LoudnessEnhancer? = null
        val latch = CountDownLatch(1)
        var completed = false
        try {
            val mp = MediaPlayer()
            player = mp
            if (attr != null) {
                mp.setAudioAttributes(attr)
            }
            configureSource(mp)
            mp.setOnCompletionListener {
                completed = true
                latch.countDown()
            }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepare()
            if (fallbackEnhancerMb > 0) {
                try {
                    enhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain(fallbackEnhancerMb)
                        enabled = true
                    }
                } catch (_: Throwable) {
                }
            }
            synchronized(activePlayerLock) {
                if (isCancelled()) return false
                activePlayer = mp
                activeLatch = latch
            }
            mp.start()
            latch.await(CLIP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return completed
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "playMediaPlayer", t)
            return false
        } finally {
            synchronized(activePlayerLock) {
                if (activePlayer === player) {
                    activePlayer = null
                    activeLatch = null
                }
            }
            try {
                enhancer?.release()
            } catch (_: Throwable) {
            }
            try {
                player?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i].toInt().toChar() != tag[i]) return false
        }
        return true
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}
