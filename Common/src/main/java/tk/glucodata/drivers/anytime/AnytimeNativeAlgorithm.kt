// AnytimeNativeAlgorithm.kt — CT3 kernel ported from the SHIPPED vendor
// libalgorithm-jni.so (Common/src/main/jniLibs/arm64-v8a/, SHA 1303a448…).
//
// Vendor entry: algorithmMain case 3 -> yqidui_PX3_QysrKoeqyhs -> yqidui_PX3
// (file vaddr 0x171f0). The obfuscated callees are named by role; addresses and
// decompilation are in docs/anytime-native-algorithm-plan.md and ct3_ship_map.txt.
//
// State and the vendor's process-wide statics are kept as raw little-endian
// buffers so the offset map stays the single source of truth (the vendor overlaps
// its globals on purpose, e.g. DAT_0028d478[i] == the ring element at i-1).
//
// Validated against the vendor binary in the Unicorn oracle for a 200-sample
// fixture (worst error 5.8e-6; see AnytimeNativeCt3Tests). Still WIRED only in
// the test — CT3-P4 (wiring into AnytimeAlgorithm.compute) is a separate change.
// Known deviation: the vendor PULSE injects a rand()/time()-driven perturbation
// which is not reproducible; this port omits it. ponytail: O-1, revisit with a
// real CT3 capture.
package tk.glucodata.drivers.anytime

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Named input fields (vendor `input` struct; corrected map in the plan). */
class AnytimeNativeInput {
    var glucoseId = 0
    var iw = 0f
    var ib = 0f
    var temperatureC = 0f
    var flags = 0
    var newBgValue = 0f
    var k0 = 0f
    var r = 0f
    var width = 1f
    var height = 0f
    var weight = 0f
    var age = 0
    var userType = 0
    var gender = 0
}

/** Vendor `output` struct subset the app consumes. */
class AnytimeNativeOutput {
    var glucose = 0f
    var mgdl = 0
    var glucose2 = 0f
    var mgdl2 = 0
    var warn = 0
    var errorCode = 0
    var trend = 0
    var sdGlu = 0f
}

/** Per-sensor state (`internalState`, param_5) + the vendor's shared statics. */
class AnytimeNativeState {
    private val b = ByteArray(STATE_BYTES)
    val globals = ByteArray(GLOBALS_BYTES)

    /** The vendor's smoothing statics (base 0x285000) — per sensor here. */
    val smooth = ByteArray(SMOOTH_BYTES)

    fun sf(off: Int) = Float.fromBits(si(off))
    fun setSf(off: Int, v: Float) = setSi(off, v.toRawBits())
    fun si(off: Int) =
        (smooth[off].toInt() and 0xFF) or
            ((smooth[off + 1].toInt() and 0xFF) shl 8) or
            ((smooth[off + 2].toInt() and 0xFF) shl 16) or
            ((smooth[off + 3].toInt() and 0xFF) shl 24)
    fun setSi(off: Int, v: Int) {
        smooth[off] = v.toByte(); smooth[off + 1] = (v ushr 8).toByte()
        smooth[off + 2] = (v ushr 16).toByte(); smooth[off + 3] = (v ushr 24).toByte()
    }

    fun f(off: Int) = Float.fromBits(i(off))
    fun setF(off: Int, v: Float) = setI(off, v.toRawBits())
    fun i(off: Int) =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    fun setI(off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    fun gf(off: Int) = Float.fromBits(gi(off))
    fun setGf(off: Int, v: Float) = setGi(off, v.toRawBits())
    fun gi(off: Int) =
        (globals[off].toInt() and 0xFF) or
            ((globals[off + 1].toInt() and 0xFF) shl 8) or
            ((globals[off + 2].toInt() and 0xFF) shl 16) or
            ((globals[off + 3].toInt() and 0xFF) shl 24)
    fun setGi(off: Int, v: Int) {
        globals[off] = v.toByte(); globals[off + 1] = (v ushr 8).toByte()
        globals[off + 2] = (v ushr 16).toByte(); globals[off + 3] = (v ushr 24).toByte()
    }

    /** Hex encoding of the whole state, for persistence via AnytimeRegistry. */
    fun encode(): String {
        val out = ByteArray(TOTAL_BYTES)
        System.arraycopy(b, 0, out, 0, STATE_BYTES)
        System.arraycopy(globals, 0, out, STATE_BYTES, GLOBALS_BYTES)
        System.arraycopy(smooth, 0, out, STATE_BYTES + GLOBALS_BYTES, SMOOTH_BYTES)
        return out.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val STATE_BYTES = 0x1070
        const val GLOBALS_BYTES = 0x430
        const val SMOOTH_BYTES = 0x120
        const val TOTAL_BYTES = STATE_BYTES + GLOBALS_BYTES + SMOOTH_BYTES

        /** Inverse of [encode]; null when the payload is missing or the wrong length. */
        fun decode(hex: String): AnytimeNativeState? {
            if (hex.length != TOTAL_BYTES * 2) return null
            val raw = ByteArray(TOTAL_BYTES)
            for (i in raw.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                raw[i] = ((hi shl 4) or lo).toByte()
            }
            val state = AnytimeNativeState()
            System.arraycopy(raw, 0, state.b, 0, STATE_BYTES)
            System.arraycopy(raw, STATE_BYTES, state.globals, 0, GLOBALS_BYTES)
            System.arraycopy(raw, STATE_BYTES + GLOBALS_BYTES, state.smooth, 0, SMOOTH_BYTES)
            return state
        }
    }
}

/**
 * Faithful port of `yqidui_PX3` and its CT3 callees. Pure Kotlin, no Android.
 */
object AnytimeNativeAlgorithm {

    // ---- per-sensor offsets (vendor internalState) ----
    private const val S_DATE = 0x08
    private const val S_RESET = 0x0c
    private const val S_GLUCOSE_ID = 0x10
    private const val S_SAMPLES = 0x14
    private const val S_RAMP = 0x18
    private const val S_BG_FLAG = 0x1c
    private const val S_IW = 0x20
    private const val S_IB = 0x24
    private const val S_TEMP_MUL_IW = 0x28
    private const val S_IB_PREV = 0x2c
    private const val S_GAIN_PREV = 0x30
    private const val S_TEMP = 0x34
    private const val S_BG_NOISE = 0x38
    private const val S_COMPENSATED = 0x3c
    private const val S_SENS = 0x40
    private const val S_T = 0x44
    private const val S_REF_MMOL = 0x48
    private const val S_REF_MGDL = 0x4c
    private const val S_T_REF = 0x50
    private const val S_WU1 = 0x54
    private const val S_WU2 = 0x58
    private const val S_WU3 = 0x5c
    private const val S_GAIN_BASE = 0x60
    private const val S_R = 0x64
    private const val S_WU_GAIN = 0x68
    private const val S_GAIN_RAMPED = 0x6c
    private const val S_ABNORMAL_COUNT = 0x8c
    private const val S_ABNORMAL_A = 0x90
    private const val S_ABNORMAL_B = 0x94
    private const val S_BG_X01 = 0x98
    private const val S_IW48 = 0x9c
    private const val S_BG_DIFF = 0xa0
    private const val S_IW48_COUNT = 0xa4
    private const val S_BG = 0xa8
    private const val S_BG_COUNT = 0xac
    private const val S_IW48_BASE = 0xb0
    private const val S_IW48_BASE_COUNT = 0xb4
    private const val S_IW48_IIR = 0xb8
    private const val S_IW48_IIR_COUNT = 0xbc
    private const val S_BASELINE = 0xc0
    private const val S_BASELINE2 = 0xc4
    private const val S_SENS_COEF = 0xcc
    private const val S_SENS_COEF2 = 0xd0
    private const val S_GLUCOSE = 0xd4
    private const val S_MGDL = 0xdc
    private const val S_GLUCOSE2 = 0xe0
    private const val S_MGDL2 = 0xe4
    private const val S_WARN = 0x118
    private const val S_TREND_RING = 0x11c
    private const val S_TREND_LAST = 0x140
    private const val S_TREND_COUNT = 0x144
    private const val S_TREND = 0x148
    private const val S_ERROR = 0x14c
    private const val S_STATUS = 0x15c
    private const val S_COUNTDOWN = 0x164
    private const val S_SD_RING_COUNT = 0x168
    private const val S_SD_RING = 0x16c
    private const val S_SD_LAST = 0x1068
    private const val S_SD_GLU = 0x106c

    // ---- globals offsets (base 0x28d464) ----
    private const val G_COUNT = 0x00
    private const val G_PREV = 0x04
    private const val G_CUR = 0x08
    private const val G_IW3 = 0x0c
    private const val G_DELTA = 0x10
    private const val G_TOUCH_LATCH = 0x14
    private const val G_RING = 0x18          // float[240] @0x47c
    private const val G_BASE3 = 0x3d0        // @0x834
    private const val G_BASE3_2 = 0x3d4      // @0x838
    private const val G_SD_PAIR = 0x3d8      // @0x83c float[2]
    private const val G_SD10 = 0x3e0         // @0x844
    private const val G_MEAN40 = 0x3e4       // @0x848
    private const val G_NOISE_ACC = 0x3e8    // @0x84c
    private const val G_NOISE_STREAK = 0x3ec // @0x850
    private const val G_NOISE_COUNT = 0x3f0  // @0x854
    private const val G_BIG_IW_COUNT = 0x3f4 // @0x858
    private const val G_TOUCH_COUNT = 0x3f8  // @0x85c
    private const val G_AVG10 = 0x3fc        // @0x860 float[11]

    private const val PI_F = 3.1415927f

    private fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

    /** Entry point: one CT3 sample. `previousGlucoseId` is the vendor `dynamic[0]`. */
    fun process(
        input: AnytimeNativeInput,
        state: AnytimeNativeState,
        previousGlucoseId: Int,
    ): AnytimeNativeOutput {
        val g = state
        // ---- yqidui_PX3 head ----
        g.setI(S_GLUCOSE_ID, input.glucoseId)
        g.setF(S_IW, input.iw)
        g.setF(S_IB, input.ib)
        g.setI(S_TREND, 10)
        g.setI(S_WARN, 0)
        g.setF(S_T, input.temperatureC)
        g.setF(S_R, input.r)
        if (g.i(S_DATE) != input.glucoseId) {
            g.setI(S_DATE, input.glucoseId)
            g.setI(S_RESET, 0)
        }
        g.setI(0x160, 0); g.setI(0x164, 0)
        g.setF(S_SENS_COEF, 0f)

        // ZBWBL (PULSE) omitted. ponytail: O-1
        initIwStatistics(g, g.f(S_IW), input.k0, input.glucoseId)

        var errorCode = when {
            noise(g) -> 0xb
            waterproofBigIw(g) -> 0x10
            touch(g) -> 0xf
            takeOff(g) -> 0xd
            else -> 0
        }
        if (errorCode != 0) {
            clearChain(g)
            g.setI(S_ERROR, errorCode)
            val out = AnytimeNativeOutput()
            writeOutput(g, out)
            return out
        }
        g.setI(S_ERROR, 0)

        var samples = g.i(S_SAMPLES)
        var ramp = g.i(S_RAMP)
        var tRefBits = g.i(S_T_REF)
        val t = g.f(S_T)
        var tempPath = true

        if (t <= 0.01f || ramp > 0 || input.glucoseId < previousGlucoseId - 1) {
            if (input.flags == 0x80 && g.i(S_STATUS) != 0) {
                val ref = input.newBgValue
                samples += 1
                g.setF(S_BASELINE, 0f)
                g.setF(S_SENS_COEF, 0f)
                g.setF(S_REF_MGDL, ref)
                g.setF(S_REF_MMOL, ref / 18f)
                tRefBits = input.temperatureC.toRawBits()
                ramp = 1
            } else if (ramp > 0) {
                ramp += 1
            } else {
                tempPath = false
                ramp = 0
                samples = 0
            }
        } else {
            samples = 0
            g.setF(S_GAIN_BASE, input.k0 * 1.35f)
            ramp = 1
            tRefBits = 0x42000000 // 32.0f
        }

        if (tempPath) {
            val mul = temperatureMul(Float.fromBits(tRefBits), t)
            val tiw = mul * g.f(S_IW)
            g.setF(S_TEMP_MUL_IW, tiw); g.setF(S_IB_PREV, mul)
            // smoothMain(reset, value, factor, scale) -> state+0x30 / state+0x34
            val reset = input.glucoseId - previousGlucoseId + 1
            val (sv, sf) = smoothMain(g, reset, tiw, mul, g.f(S_GAIN_RAMPED))
            g.setF(S_GAIN_PREV, sv); g.setF(S_TEMP, sf)
        } else {
            val iw = g.f(S_IW); val ib = g.f(S_IB)
            g.setF(S_TEMP_MUL_IW, iw); g.setF(S_IB_PREV, ib)
            g.setF(S_GAIN_PREV, iw); g.setF(S_TEMP, ib)
        }
        // SUTC_SD3_NC3 (INIT_IW3): G_IW3 = state+0x30
        g.setGi(G_IW3, g.i(S_GAIN_PREV))

        g.setI(S_SAMPLES, samples)
        g.setI(S_RAMP, ramp)
        g.setI(S_T_REF, tRefBits)

        if (!errorIw(g, input.glucoseId)) algorithmBody(input, g)
        val out = AnytimeNativeOutput()
        writeOutput(g, out)
        return out
    }

    // =====================================================================
    // SUTC_SD_GE3_PD5 — INIT_IW statistics
    // =====================================================================
    private fun initIwStatistics(g: AnytimeNativeState, iw: Float, k0: Float, glucoseId: Int) {
        if (glucoseId < glucoseId - 1) { // vendor w1==glucoseId at the call site -> never
            java.util.Arrays.fill(g.globals, 0.toByte())
            return
        }
        val count0 = g.gi(G_COUNT)
        g.setGf(G_CUR, iw)
        // fVar9 (Mean10 accumulator) starts as param_1 in the vendor
        var sum10 = iw
        var meanAll = 0f
        var mean15: Float
        var mean10: Float

        if (count0 > 0xef) {
            // ---- shift path ----
            g.setGi(G_NOISE_COUNT, 0)
            var sumE2 = 0f
            var sumC9 = 0f
            var sumAll = 0f
            var absAcc = 0f
            var noise = 0
            var i = 0
            while (i != 0xef) {
                val v = g.gf(G_RING + (i + 1) * 4)
                val idxN = i + 1
                if (idxN >= 0xe2) sumE2 += v
                if (idxN >= 0xc9) sumC9 += v
                if (idxN > 0xe1) {
                    val d = abs(v - g.gf(G_RING + i * 4))
                    absAcc += d
                    if (idxN > 0xe6 && d > 4f) noise += 1
                    sum10 += v
                }
                sumAll += v
                g.setGf(G_RING + i * 4, v)
                i = idxN
            }
            val prev834 = g.gf(G_BASE3)
            noise += if (abs(iw - prev834) > 4f) 1 else 0
            g.setGf(G_NOISE_ACC, absAcc + abs(iw - prev834))
            g.setGf(G_BASE3_2, iw)
            g.setGi(G_NOISE_COUNT, noise)
            mean15 = (sumE2 + iw) / 15f
            g.setGf(G_MEAN40, (sumC9 + iw) / 40f)
            meanAll = (sumAll + iw) / 240f
            mean10 = sum10 / 10f
        } else {
            g.setGf(G_PREV, if (count0 < 1) iw else g.gf(G_RING + (count0 - 1) * 4))
            g.setGf(G_RING + count0 * 4, iw)
            // ---- partial accumulation (uVar4 = count0+1) ----
            val uVar4 = count0 + 1
            var sum15 = 0f
            var sum40 = 0f
            var absAcc = 0f
            var noise = 0
            var i = 0
            while (i != uVar4) {
                if (uVar4 > 0xe && count0 - 0xe <= i) sum15 += g.gf(G_RING + i * 4)
                if (uVar4 > 0x27 && count0 - 0x27 <= i) sum40 += g.gf(G_RING + i * 4)
                if (uVar4 > 0xf && count0 - 0xe <= i) {
                    absAcc += abs(g.gf(G_RING + i * 4) - g.gf(G_TOUCH_LATCH + i * 4))
                }
                if (uVar4 > 10 && count0 - 9 <= i) {
                    val d = abs(g.gf(G_RING + i * 4) - g.gf(G_TOUCH_LATCH + i * 4))
                    if (d > 4f) noise += 1
                    sum10 += g.gf(G_RING + i * 4)
                }
                i += 1
            }
            g.setGi(G_NOISE_COUNT, noise)
            g.setGf(G_NOISE_ACC, absAcc)
            mean15 = sum15 / 15f
            g.setGf(G_MEAN40, sum40 / 40f)
            mean10 = sum10 / 10f
            meanAll = 0f
        }
        g.setGi(G_COUNT, count0 + 1)
        postStats(g, iw, k0, count0 + 1, meanAll, mean15, mean10)
    }

    /** The tail of SUTC_SD_GE3_PD5: variance loop + the 10-sample moving average ring. */
    private fun postStats(
        g: AnytimeNativeState, iw: Float, k0: Float, n: Int,
        meanAll: Float, mean15: Float, mean10: Float,
    ) {
        val fVar15 = g.gf(G_AVG10 + 0x28)
        val fVar10 = g.gf(G_AVG10 + 0x1c)
        val fVar8 = g.gf(G_AVG10 + 0x14)
        val fVar6 = g.gf(G_AVG10 + 0x0c)

        if (g.gf(G_NOISE_ACC) > 150f) g.setGi(G_NOISE_STREAK, g.gi(G_NOISE_STREAK) + 1)
        g.setGf(G_DELTA, iw - g.gf(G_PREV))

        var varAll = 0f
        var var15 = 0f
        var var10 = 0f
        if (n >= 1) {
            for (i in 0 until n) {
                val x = g.gf(G_RING + i * 4)
                if (meanAll > 0f) varAll += (x - meanAll) * (x - meanAll)
                if (mean15 > 0f && n - 0xf <= i) var15 += (x - mean15) * (x - mean15)
                if (mean10 > 0f && n - 10 <= i) var10 += (x - mean10) * (x - mean10)
            }
        }
        g.setGf(G_SD10, if (k0 <= 1f) sqrt(var10 / 10f) else sqrt(var10 / 10f) / k0)
        g.setGf(G_SD_PAIR, sqrt(varAll / 240f))
        g.setGf(G_SD_PAIR + 4, sqrt(var15 / 240f))

        if (n < 0xb) {
            g.setGf(G_AVG10 + n * 4, g.gf(G_CUR))
            return
        }
        g.setGf(G_AVG10 + 4, fVar6)
        g.setGf(G_AVG10 + 8, g.gf(G_AVG10 + 0x0c))
        var s = fVar6 + g.gf(G_AVG10 + 0x0c)
        g.setGf(G_AVG10 + 0x0c, g.gf(G_AVG10 + 0x10))
        g.setGf(G_AVG10 + 0x10, g.gf(G_AVG10 + 0x14))
        s += fVar8 + g.gf(G_AVG10 + 0x14)
        g.setGf(G_AVG10 + 0x14, g.gf(G_AVG10 + 0x18))
        g.setGf(G_AVG10 + 0x18, g.gf(G_AVG10 + 0x1c))
        s += fVar10 + g.gf(G_AVG10 + 0x1c) + g.gf(G_AVG10 + 0x20) + g.gf(G_AVG10 + 0x24) + g.gf(G_AVG10 + 0x28)
        g.setGf(G_AVG10 + 0x28, iw)
        g.setGf(G_AVG10 + 0x1c, g.gf(G_AVG10 + 0x20))
        g.setGf(G_AVG10 + 0x20, g.gf(G_AVG10 + 0x24))
        g.setGf(G_AVG10 + 0x24, fVar15)
        g.setGf(G_AVG10, (s + iw) / 10f)
    }

    // =====================================================================
    // wntrulThgs_NG3 (smoothMain) + vbsjfVjtgy_NG3 (rangeLimit) + hulls
    // over the 0x285000 statics. Returns (state+0x30, state+0x34) pair.
    // =====================================================================
    private fun smoothMain(g: AnytimeNativeState, reset: Int, value: Float, factor: Float, scale: Float): Pair<Float, Float> {
        g.setSf(0xf8, 0f)
        g.setSf(0x100, 0f)
        if (reset == 0) for (o in 0 until 0x100 step 4) g.setSf(o, 0f)
        rangeLimit(g, value, factor, scale)
        hull1(g)
        hullStable(g)
        return g.sf(0x100) to g.sf(0x104)
    }

    private fun rangeLimit(g: AnytimeNativeState, value: Float, factor: Float, scale: Float) {
        val n = g.si(0)
        var last: Int
        if (n < 5) {
            g.setSf((n + 0xb) * 4, value)
            g.setSf((n + 0x10) * 4, factor)
            g.setSi(0, n + 1)
            if (n != 4) {
                g.setSf(0xf8, value)
                g.setSf(0xfc, factor)
                val add = g.sf(0x36 * 4)
                g.setSf(0xf8, value + add)
                g.setSf((n + 0xb) * 4, value + add)
                g.setSf((n + 0x10) * 4, g.sf(0xfc))
                return
            }
            last = 4
        } else {
            for (i in 1 until n) {
                g.setSf((0xa + i) * 4, g.sf((0xb + i) * 4))
                g.setSf((0xf + i) * 4, g.sf((0x10 + i) * 4))
            }
            g.setSf((n + 0xa) * 4, value)
            g.setSf((n + 0xf) * 4, factor)
            g.setSi(0, 5)
            last = 4
        }
        val fVar10 = g.sf(0x3a * 4)
        val fVar1 = g.sf(0x3b * 4)
        val fVar16 = g.sf(0x3c * 4)
        val fVar11 = g.sf(0x3d * 4)
        var meanV = 0f
        for (i in 0xb..0xf) meanV += g.sf(i * 4)
        meanV /= 5f
        var meanF = 0f
        for (i in 0x10..0x14) meanF += g.sf(i * 4)
        meanF /= 5f
        val d0v = g.sf(0xb * 4) - meanV
        val d0f = g.sf(0x10 * 4) - meanF
        val d4v = g.sf(0xf * 4) - meanV
        val d4f = g.sf(0x14 * 4) - meanF
        g.setSf(0x3b * 4, fVar16)
        g.setSf(0x3c * 4, fVar11)
        val dv = ((-2f * d0v) + (meanV - g.sf(0xc * 4)) + 0f + (g.sf(0xe * 4) - meanV) + 2f * d4v) / 10f
        val df = ((-2f * d0f) + (meanF - g.sf(0x11 * 4)) + 0f + (g.sf(0x13 * 4) - meanF) + 2f * d4f) / 10f
        g.setSf(0x3d * 4, dv)
        g.setSf(0x37 * 4, dv)
        g.setSf(0x38 * 4, df)
        g.setSf(0x39 * 4, fVar10)
        g.setSf(0x3a * 4, fVar1)
        g.setSf(0x35 * 4, dv + fVar10 + fVar1 + fVar16 + fVar11)
        val limV = clamp(abs(dv), 0.4f, 0.8f)
        val limF = clamp(abs(df), 0.2f, 0.3f)
        val lv = if (scale <= 0f) limV else limV * scale
        val lf = if (scale <= 0f) limF else limF * scale
        var pv = g.sf(0xf * 4)
        val pvBase = g.sf(0xe * 4)
        pv = if (pv - pvBase <= lv) { if (lv < pvBase - pv) pvBase - lv else pv } else pvBase + lv
        var pf = g.sf(0x13 * 4)
        val pfBase = g.sf(0x14 * 4)
        pf = if (pfBase - pf <= lf) { if (lf < pf - pfBase) pf - lf else pf } else pf + lf
        g.setSf(0xf8, pv)
        g.setSf(0xfc, pf)
        val add = g.sf(0x36 * 4)
        g.setSf(0xf8, pv + add)
        g.setSf((last + 0xb) * 4, pv + add)
        g.setSf((last + 0x10) * 4, g.sf(0xfc))
    }

    private fun hull1(g: AnytimeNativeState) {
        val c = g.si(4)
        var pairLo: Float
        var pairHi: Float
        if (c == 0) {
            g.setSi(0x14, 10)
            g.setSi(0x1c, 3)
            g.setSi(0x24, 3)
            pairLo = g.sf(0xf8); pairHi = g.sf(0xfc)
            g.setSf(c * 4 + 0x54, pairLo)
            g.setSf(c * 4 + 0xa4, pairHi)
            g.setSi(4, c + 1)
        } else if (c > 3) {
            val fVar7 = g.sf(0x58); val fVar8 = g.sf(0x5c)
            val fVar12 = g.sf(0xa8); val fVar14 = g.sf(0xac); val fVar18 = g.sf(0xb0)
            g.setSf(0x54, fVar7); g.setSf(0x58, fVar8)
            val fVar11 = g.sf(0x60)
            g.setSf(0xa4, fVar12); g.setSf(0xa8, fVar14); g.setSf(0xac, fVar18); g.setSf(0x5c, fVar11)
            val fVar9 = g.si(0x14).toFloat()
            val out0 = g.sf(0xf8)
            g.setSf(0x60, out0)
            val fVar13 = g.si(0x1c).toFloat()
            val out1 = g.sf(0xfc)
            g.setSf(0xb0, out1)
            val vl = fVar11 / fVar13 + 2f * out0 / fVar13
            val vh = fVar18 / fVar13 + 2f * out1 / fVar13
            pairLo = 2f * vl - (fVar7 / fVar9 + 2f * fVar8 / fVar9 + 3f * fVar11 / fVar9 + 4f * out0 / fVar9)
            pairHi = 2f * vh - (fVar12 / fVar9 + 2f * fVar14 / fVar9 + 3f * fVar18 / fVar9 + 4f * out1 / fVar9)
        } else {
            pairLo = g.sf(0xf8); pairHi = g.sf(0xfc)
            g.setSf(c * 4 + 0x54, pairLo)
            g.setSf(c * 4 + 0xa4, pairHi)
            g.setSi(4, c + 1)
        }
        // LAB_00107adc
        val uVar3 = g.si(0xc)
        if (uVar3 > 1) {
            val fVar11 = g.si(0x24).toFloat()
            var o1lo = 0f
            var o1hi = 0f
            var j = 0
            while (j < uVar3 - 1) {
                val base = 0xd0 + 4 * j
                val r0 = base - 0x18 // 0xb8
                val r1 = base - 0x04 // 0xcc
                g.setSf(r0 - 4, g.sf(r0))
                g.setSf(r1, g.sf(base))
                val w = (j + 1).toFloat()
                o1lo += g.sf(r0) * w / fVar11
                o1hi += g.sf(r1) * w / fVar11
                j += 1
            }
            g.setSf(0xb8, pairLo)
            g.setSf(0xd0, pairHi)
            g.setSf(0x100, o1lo + 2f * pairLo / fVar11)
            g.setSf(0x104, o1hi + 2f * pairHi / fVar11)
            return
        }
        g.setSf(uVar3 * 4 + 0xb4, pairLo)
        g.setSf(uVar3 * 4 + 0xcc, pairHi)
        g.setSf(0x100, pairLo)
        g.setSf(0x104, pairHi)
        g.setSi(0xc, uVar3 + 1)
    }

    private fun hullStable(g: AnytimeNativeState) {
        val out0 = g.sf(0xf8)
        var iVar8 = g.si(8)
        var fVar7: Float
        if (iVar8 == 0) {
            g.setSi(0x18, 0x88)
            g.setSi(0x20, 0x24)
            g.setSi(0x28, 10)
            fVar7 = out0
            g.setSf(iVar8 * 4 + 100, fVar7)
            g.setSi(8, iVar8 + 1)
        } else if (iVar8 > 0xf) {
            val win = g.si(0x18)
            var accW = 0f
            var accM = 0f
            var j = 0
            while (j != 15) {
                val fVar10 = g.sf(0x68 + 4 * j)
                val uVar1 = j + 1
                g.setSf(0x64 + 4 * j, fVar10)
                if (uVar1 > 8) accW += fVar10 * (j - 7) / g.si(0x20)
                accM += fVar10 * uVar1 / win
                j = uVar1
            }
            g.setSf(0xa0, out0)
            val fVar7a = accW + (out0 * 8f) / g.si(0x20)
            fVar7 = 2f * fVar7a - (accM + (out0 * 16f) / win)
        } else {
            fVar7 = out0
            g.setSf(iVar8 * 4 + 100, fVar7)
            g.setSi(8, iVar8 + 1)
        }
        // LAB_00107c80
        val uVar3 = g.si(0x10)
        if (uVar3 < 4) {
            g.setSf(uVar3 * 4 + 0xbc, fVar7)
            g.setSi(0x10, uVar3 + 1)
        } else {
            iVar8 = g.si(0x28)
            var lVar5 = 0
            var fVar9 = 0f
            while (lVar5.toLong() != uVar3.toLong() - 1) {
                val fVar10 = g.sf(lVar5 * 4 + 0xc0)
                val writeOff = lVar5 * 4 + 0xbc
                lVar5 += 1
                g.setSf(writeOff, fVar10)
                fVar9 += fVar10 * lVar5 / iVar8
            }
            g.setSf(200, fVar7)
            fVar7 = fVar9 + (fVar7 * 4f) / iVar8
        }
        val fVar9 = if (abs(g.sf(0xd4)) <= 1.5f) 0.5f else clamp(abs(g.sf(0xdc)) + 0.2f, 0.5f, 1f)
        g.setSf(0x100, fVar9 * g.sf(0x100) + fVar7 * (1f - fVar9))
    }

    // =====================================================================
    // emlrsmuokMrif_ND3 — algorithmBody_CT3
    // =====================================================================
    private fun algorithmBody(input: AnytimeNativeInput, g: AnytimeNativeState) {
        if (g.f(S_BG_DIFF) <= 0.2f) {
            val c = g.i(S_IW48_COUNT)
            if (c < 0x1e1) {
                if (c < 1) {
                    if (g.i(S_GLUCOSE_ID) < 0x13) {
                        g.setI(S_IW48_COUNT, 0); g.setF(S_IW48, 0f)
                    } else {
                        g.setI(S_IW48_COUNT, c + 1); g.setI(S_IW48, g.i(S_TEMP))
                    }
                } else {
                    g.setF(S_IW48, (g.f(S_IW48) * c + g.f(S_TEMP)) / (c + 1f))
                    g.setI(S_IW48_COUNT, c + 1)
                }
            } else {
                g.setI(S_IW48_COUNT, c + 1)
                g.setF(S_IW48, g.f(S_IW48) * 0.9986111f + g.f(S_TEMP) * 0.0013889f)
            }
        }
        calcRAndIwBackground(g)
        iw48BaseStage(g)
        iw48Base(g)
        sensitivity(g, input)
        if (g.i(S_ERROR) < 0x11 && ((1 shl g.i(S_ERROR)) and 0x15000) != 0) {
            clearBody(g)
            return
        }
        warmUpRamp(g)
        glucoseCalculate(g)
        trend(g)
        if (g.i(S_RAMP) < 1) {
            g.setI(S_ERROR, 0)
            g.setI(S_STATUS, 0)
        } else {
            g.setI(S_ERROR, 0)
            g.setI(S_STATUS, if (g.i(S_TREND) + 1 < 3) 1 else 0)
        }
    }

    /** Second IW48-style EMA inside `emlrsmuokMrif_ND3` (state+0xb0/+0xb4). */
    private fun iw48BaseStage(g: AnytimeNativeState) {
        val c = g.f(S_IW48_BASE_COUNT)
        when {
            c <= 480f && c > 0f -> {
                g.setF(S_IW48_BASE, (c * g.f(S_IW48_BASE) + g.f(S_COMPENSATED)) / (c + 1f))
                g.setF(S_IW48_BASE_COUNT, c + 1f)
            }
            c <= 0f -> {
                if (g.i(S_RAMP) >= 1) {
                    g.setF(S_IW48_BASE, g.f(S_COMPENSATED))
                    g.setF(S_IW48_BASE_COUNT, c + 1f)
                } else {
                    g.setF(S_IW48_BASE, 0f)
                    g.setF(S_IW48_BASE_COUNT, 0f)
                }
            }
            else -> {
                g.setF(S_IW48_BASE_COUNT, c + 1f)
                val sd = g.f(S_SD_GLU)
                if (sd <= 1.8f) {
                    if (sd <= 1.2f) {
                        g.setF(S_IW48_BASE, g.f(S_IW48_BASE) * 0.99722224f + g.f(S_COMPENSATED) * 0.00277778f)
                    } else {
                        g.setF(S_IW48_BASE, g.f(S_IW48_BASE) * 0.9979167f + g.f(S_COMPENSATED) * 0.00208333f)
                    }
                } else {
                    g.setF(S_IW48_BASE, g.f(S_IW48_BASE) * 0.9986111f + g.f(S_COMPENSATED) * 0.00138889f)
                }
            }
        }
    }

    /** gbqfvpbagtqCNriShLbhnhvpbli_NG3 — calculationRAndIwBackground_CT3. */
    private fun calcRAndIwBackground(g: AnytimeNativeState) {
        var v = g.f(S_BG_FLAG)
        val gid = g.i(S_GLUCOSE_ID)
        if (v <= 0.1f && gid < 0x50 && g.i(S_RAMP) == 1) {
            val target = g.f(S_GAIN_BASE) * 5f
            val decay = exp(gid * -0.008f)
            var r = 5f
            if (g.f(S_GAIN_PREV) - decay * 5f < target) {
                while (g.f(S_GAIN_PREV) - decay * r < target) r -= 0.5f
            }
            g.setF(S_BG_FLAG, r)
            v = r
        }
        val decay = exp(gid * -0.008f)
        val bgCount = g.f(S_BG_COUNT)
        val bg = g.f(S_GAIN_PREV) - decay * v
        g.setF(S_BG_NOISE, bg)
        val newBg = when {
            bgCount <= 480f && bgCount > 0f -> (bg + bgCount * g.f(S_BG)) / (bgCount + 1f)
            bgCount <= 0f && g.i(S_RAMP) > 0 -> bg
            bgCount > 480f -> bg * 0.00208333f + g.f(S_BG) * 0.9979167f
            else -> g.f(S_GAIN_PREV)
        }
        g.setF(S_BG, newBg)
        g.setF(S_BG_COUNT, bgCount + 1f)
        val bgx = newBg * 0.1f
        g.setF(S_BG_X01, bgx)
        var bd = 0f
        if (gid >= 0x2d0 && g.f(S_IW48) > 0f) {
            bd = g.f(S_TEMP) - g.f(S_IW48)
            if (bd <= 0.2) bd = 0f
        }
        g.setF(S_BG_DIFF, bd)
        g.setF(S_COMPENSATED, if (g.f(S_BG_NOISE) <= bgx) 1f else g.f(S_BG_NOISE) - bgx)
    }

    /** kfyRx48_GE3 — getIw48_CT3. */
    private fun iw48Base(g: AnytimeNativeState) {
        var c = g.i(S_IW48_IIR_COUNT)
        var iir = g.f(S_IW48_IIR)
        if (c < 0x1e1) {
            if (c < 1) {
                if (g.i(S_RAMP) < 1) {
                    c = 0; iir = 0f; g.setI(S_IW48_IIR, 0)
                } else {
                    iir = g.f(S_COMPENSATED); c += 1
                    g.setF(S_IW48_IIR, iir); g.setI(S_IW48_IIR_COUNT, c)
                }
            } else {
                iir = (iir * c + g.f(S_COMPENSATED)) / (c + 1f)
                c += 1
                g.setF(S_IW48_IIR, iir); g.setI(S_IW48_IIR_COUNT, c)
            }
        } else {
            if (g.i(S_GLUCOSE_ID) < 0x5b4) {
                val ratio = (g.f(S_COMPENSATED) - iir) / iir
                if (ratio > -0.2f && ratio < 0.5f) {
                    iir = g.f(S_COMPENSATED) * 0.00104167f + iir * 0.99895835f
                }
            } else {
                iir = iir * 0.99895835f + g.f(S_COMPENSATED) * 0.00104167f
            }
            c += 1
            g.setF(S_IW48_IIR, iir); g.setI(S_IW48_IIR_COUNT, c)
        }
        if (g.i(S_RAMP) > 0x1df && c > 0x3c0) {
            val gid = g.i(S_GLUCOSE_ID)
            if (gid - 0x975 < 0x14) {
                val f = (gid - 0x974) / 20f
                g.setF(S_BASELINE, if (g.f(S_BASELINE) <= 0f) iir else (1f - f) * g.f(S_BASELINE) + f * iir)
                if (g.f(S_BASELINE2) > 0f) {
                    g.setF(S_BASELINE2, (1f - f) * g.f(S_BASELINE2) + f * iir)
                } else {
                    g.setF(S_BASELINE2, iir)
                }
            } else if (abs(g.f(S_IW48_BASE) - iir) / iir < 0.05f) {
                if (g.f(S_BASELINE) <= 0f) g.setF(S_BASELINE, iir)
                if (g.f(S_BASELINE2) <= 0f) g.setF(S_BASELINE2, iir)
            }
        }
    }

    /** wfyBfrtprnynacNspjgnfjioa_NC3 — setSensitivityCoefficient_CT3. */
    private fun sensitivity(g: AnytimeNativeState, input: AnytimeNativeInput) {
        val ramp = g.i(S_RAMP)
        if (ramp < 1) {
            g.setF(S_SENS_COEF, 0f)
        } else if (ramp == 1) {
            g.setF(S_ABNORMAL_COUNT, 0f)
            g.setF(S_SENS_COEF, 0f)
        } else if (ramp < 0x1e0 || g.f(S_BASELINE) <= 0f) {
            g.setF(S_SENS_COEF, 0f)
        } else {
            val coef = (g.f(S_IW48_BASE) - g.f(S_BASELINE)) / g.f(S_BASELINE)
            g.setF(S_SENS_COEF, coef)
            if (coef != 0f) {
                val n = if (g.i(S_ABNORMAL_COUNT) > 0) g.i(S_ABNORMAL_COUNT) + 1 else 1
                g.setI(S_ABNORMAL_COUNT, n)
                if (n.toFloat() < abs(coef) * 100f) {
                    val s = sin(((n shl 1).toFloat() / (abs(coef) * 100f + 1f) - 1f) * PI_F * 0.5f)
                    g.setF(S_SENS_COEF, coef * (s + 1f) * 0.5f)
                }
            }
        }
        val b2 = g.f(S_BASELINE2)
        var coef2 = 0f
        g.setF(S_SENS_COEF2, 0f)
        if (b2 > 0f) {
            coef2 = (g.f(S_IW48_BASE) - b2) / b2
            g.setF(S_SENS_COEF2, coef2)
        }
        when {
            attenuation(g, coef2) -> g.setI(S_ERROR, 0xc)
            waterproof(g, coef2) -> g.setI(S_ERROR, 0x10)
            breakage(coef2) -> g.setI(S_ERROR, 0xe)
            abs(g.f(S_SENS_COEF)) > 0.2f && g.i(S_ABNORMAL_A) == g.i(S_ABNORMAL_B) -> {
                var v = input.age
                if (input.height > 4f) v += 1
                g.setI(S_ABNORMAL_COUNT, v)
                g.setI(S_ABNORMAL_A, g.i(S_ABNORMAL_A) + 1)
            }
        }
        var sens = g.f(S_COMPENSATED)
        if (input.userType != 1) sens /= (g.f(S_SENS_COEF) + 1f)
        g.setF(S_SENS, sens)
    }

    /** kmzfpwfPyqfzseyi_MA3 — glucoseCalculate_CT3. */
    private fun glucoseCalculate(g: AnytimeNativeState) {
        if (g.i(S_RAMP) < 1) return
        var v = g.f(S_SENS) / g.f(S_GAIN_RAMPED)
        if (g.i(S_RAMP) < 4) {
            g.setF(S_GLUCOSE, v)
        } else {
            val count = g.i(S_SD_RING_COUNT)
            var gate = false
            if (count >= 1) {
                var mean = 0f
                for (i in 0 until count) mean += g.f(S_SD_RING + i * 4)
                mean /= count.toFloat()
                if (mean > v) gate = (v / mean) <= 0.9f
            }
            v = compensation(g.f(S_GLUCOSE), v, g.f(S_SD_GLU))
            g.setF(S_GLUCOSE, v)
        }
        val mgdl = (v * 18f + 0.5f).toInt()
        g.setI(S_MGDL, mgdl)
        g.setI(S_MGDL2, mgdl)
        g.setF(S_GLUCOSE2, v)
        if (v in 0.0001f..3.05f) g.setI(S_WARN, 2)
        if (v > 22f) g.setI(S_WARN, 3)
        if (v > 0f) pushSdRing(g, v)
    }

    private fun pushSdRing(g: AnytimeNativeState, v: Float) {
        var count = g.i(S_SD_RING_COUNT)
        if (count < 0x3c0) {
            g.setF(S_SD_RING + count * 4, v)
            count += 1
            g.setI(S_SD_RING_COUNT, count)
        } else {
            var sum = 0f
            var i = 0
            while (i < 0x3bf) {
                val x = g.f(S_SD_RING + (i + 1) * 4)
                sum += x
                g.setF(S_SD_RING + i * 4, x)
                i += 1
            }
            g.setF(S_SD_LAST, v)
            g.setF(S_SD_RING + 0x3bf * 4, v)
            g.setI(S_SD_RING_COUNT, 0x3c0)
            sum += v
            count = 0x3c0
        }
        var sum = 0f
        for (i in 0 until count) sum += g.f(S_SD_RING + i * 4)
        var varSum = 0f
        for (i in 0 until count) {
            val d = g.f(S_SD_RING + i * 4) - sum / count
            varSum += d * d
        }
        g.setF(S_SD_GLU, sqrt(varSum / count))
    }

    /** QSFLVCL_GZVARXDKKSVY_JD3 — glucose step limiter. */
    private fun compensation(prev: Float, next: Float, sd: Float): Float {
        val down: Float
        val up: Float
        when {
            sd <= 1.2f -> { down = 0.4f; up = 0.5f }
            sd <= 1.8f -> { down = 0.6f; up = 0.7f }
            sd > 2.5f -> { down = 1.0f; up = 1.0f }
            else -> { down = 0.8f; up = 0.9f }
        }
        var v = next
        if (prev - v <= down) {
            if (up < v - prev) v = prev + up
        } else {
            v = prev - down
        }
        if (v > 15f) v = prev * 0.2f + v * 0.8f
        return v
    }

    /** wfyCsiok_NC3 — setTrend_CT3. */
    private fun trend(g: AnytimeNativeState) {
        if (g.i(S_RAMP) < 1) { g.setI(S_TREND, 10); return }
        if (g.i(S_RAMP) == 1) {
            g.setI(S_TREND_COUNT, 0)
            g.setI(S_TREND, 10)
            for (i in 0 until 10) g.setF(S_TREND_RING + i * 4, 0f)
        }
        var count = g.i(S_TREND_COUNT)
        val newVal = g.f(S_GLUCOSE2)
        var oldVal: Float
        if (count < 10) {
            g.setF(S_TREND_RING + count * 4, newVal)
            count += 1
            g.setI(S_TREND_COUNT, count)
            if (count != 10) return
            oldVal = g.f(S_TREND_LAST)
        } else {
            for (i in 0 until 9) g.setF(S_TREND_RING + i * 4, g.f(S_TREND_RING + (i + 1) * 4))
            oldVal = g.f(S_TREND_LAST)
            g.setF(S_TREND_LAST, newVal)
            g.setI(S_TREND_COUNT, 10)
        }
        val d = ((newVal - oldVal) / 9f) / 3f
        g.setI(S_TREND, when {
            d >= 0.11f -> 2
            d <= -0.11f -> if (newVal > 5f) -2 else -3
            d >= 0.06f -> 1
            d > -0.06f -> 0
            else -> -1
        })
    }

    /** xfrsfvbaswhXhms_TD3 — temperatureMain_CT3; returns the multiplier. */
    private fun temperatureMul(tRef: Float, tCur: Float): Float {
        val a1 = clamp(0.12f * tRef + 31.4f, 32.5f, 35.7f)
        val a2 = clamp(0.12f * tCur + 31.4f, 32.5f, 35.7f)
        val b1 = clamp(0.055f * tRef + 34.585f, 35.6f, 36.5f)
        val b2 = clamp(0.055f * tCur + 34.585f, 35.6f, 36.5f)
        return ((a2 - a1) + (b2 - b1)) * -0.5f * 0.035f + 1f
    }

    /** Warm-up gain ramp (`emlrsmuokMrif_ND3`), shipped thresholds 120/320/600. */
    private fun warmUpRamp(g: AnytimeNativeState) {
        val ramp = g.i(S_RAMP)
        val x = g.f(S_GAIN_BASE)
        if (g.i(S_SAMPLES) < 1) {
            val v: Float = when {
                ramp < 0x78 -> x * 0.7f + (x * 0.1f * ramp) / 120f
                ramp < 0x140 -> x * 0.8f + (x * 0.1f * (ramp - 0x78)) / 200f
                ramp < 600 -> x * 0.9f + (x * 0.1f * (ramp - 0x140)) / 280f
                else -> x
            }
            if (ramp < 1) { g.setF(S_WU1, 0f); g.setF(S_WU2, 0f) }
            g.setF(S_GAIN_RAMPED, v)
        } else if (ramp == 3) {
            val cur = g.f(S_SENS) / g.f(S_REF_MMOL)
            val mean = (cur + g.f(S_WU1) + g.f(S_WU2)) / 3f
            g.setF(S_WU3, cur); g.setF(S_GAIN_BASE, mean); g.setF(S_GAIN_RAMPED, mean)
        } else if (ramp == 2) {
            val cur = g.f(S_SENS) / g.f(S_REF_MMOL)
            g.setF(S_WU2, cur)
            val m = (cur + g.f(S_WU1)) * 0.5f
            g.setF(S_GAIN_BASE, m); g.setF(S_GAIN_RAMPED, m)
        } else if (ramp == 1) {
            g.setF(S_WU2, 0f)
            val cur = g.f(S_SENS) / g.f(S_REF_MMOL)
            g.setI(S_RESET, g.i(S_RESET) + 1)
            g.setF(S_WU1, cur)
            g.setF(S_WU_GAIN, if (g.f(S_GAIN_BASE) <= 0f) cur else g.f(S_GAIN_BASE))
            g.setF(S_GAIN_BASE, cur); g.setF(S_GAIN_RAMPED, cur)
        }
    }

    // ---- predicates ----
    private fun noise(g: AnytimeNativeState) = g.gi(G_NOISE_COUNT) > 6 && g.gf(G_SD10) > 5.6f
    private fun attenuation(g: AnytimeNativeState, v: Float) = v < -0.65f && g.gf(G_SD_PAIR) < 0.2f
    private fun waterproof(g: AnytimeNativeState, v: Float) = v > 1.5f && g.gi(G_NOISE_STREAK) > 0
    private fun breakage(v: Float) = v > 1.0f
    private fun takeOff(g: AnytimeNativeState) = g.gi(G_COUNT) > 0x28 && g.gf(G_MEAN40) < 1f && g.gf(G_SD_PAIR + 4) < 1f
    private fun errorIw(g: AnytimeNativeState, glucoseId: Int) = glucoseId > 4 && g.gi(G_IW3) <= 1

    private fun waterproofBigIw(g: AnytimeNativeState): Boolean {
        var d = -1
        if (g.gf(G_CUR) > 70f) d = 1
        val v = d + g.gi(G_BIG_IW_COUNT)
        if (v >= 3) { g.setGi(G_BIG_IW_COUNT, 3); return true }
        if (v > 0) { g.setGi(G_BIG_IW_COUNT, v); return false }
        g.setGi(G_BIG_IW_COUNT, 0)
        return false
    }

    private fun touch(g: AnytimeNativeState): Boolean {
        if (g.gf(G_CUR) < 0.5f) {
            if (g.gi(G_TOUCH_LATCH) > 0) {
                g.setGi(G_TOUCH_COUNT, g.gi(G_TOUCH_COUNT) + 1)
            } else if (g.gf(G_PREV) > 3f && g.gf(G_DELTA) / g.gf(G_PREV) < -0.95f) {
                g.setGi(G_TOUCH_LATCH, 1)
                g.setGi(G_TOUCH_COUNT, g.gi(G_TOUCH_COUNT) + 1)
            }
        } else {
            g.setGi(G_TOUCH_COUNT, 0); g.setGi(G_TOUCH_LATCH, 0)
        }
        return g.gi(G_TOUCH_COUNT) > 2
    }

    // ---- helpers ----
    private fun clearChain(g: AnytimeNativeState) {
        clearBody(g)
        g.setI(S_ERROR, 0)
        g.setI(S_STATUS, 0)
    }

    private fun clearBody(g: AnytimeNativeState) {
        for (o in intArrayOf(S_TREND, S_TREND_COUNT, S_STATUS, S_ERROR, 0x114)) g.setI(o, 0)
        g.setI(S_TREND, 10)
        for (o in intArrayOf(S_TEMP_MUL_IW, S_IB_PREV, S_GAIN_PREV, S_SENS, S_COMPENSATED,
                S_BG_NOISE, S_IW48, S_BG_DIFF, S_BG, S_IW48_BASE, S_IW48_IIR, S_BASELINE,
                S_BASELINE2, S_SENS_COEF, S_SENS_COEF2, S_GLUCOSE, S_GLUCOSE2, S_GAIN_RAMPED,
                S_SD_GLU, S_WU1, S_WU2, S_WU3)) g.setF(o, 0f)
        g.setI(S_SD_RING_COUNT, 0); g.setI(S_IW48_COUNT, 0)
        g.setI(S_IW48_BASE_COUNT, 0); g.setI(S_IW48_IIR_COUNT, 0)
    }

    private fun writeOutput(g: AnytimeNativeState, out: AnytimeNativeOutput) {
        if (g.i(S_RAMP) < 1 || g.i(S_ERROR) != 0) {
            out.glucose = 0f; out.mgdl = 0; out.glucose2 = 0f; out.mgdl2 = 0
        } else {
            val v = g.f(S_GLUCOSE)
            if (v <= 27.8f) {
                if (v >= 1.7f) { out.glucose = v; out.mgdl = g.i(S_MGDL) }
                else { out.glucose = 1.7f; out.mgdl = 0x1f }
            } else { out.glucose = 27.8f; out.mgdl = 500 }
            val v2 = g.f(S_GLUCOSE2)
            if (v2 <= 27.8f) {
                if (v2 >= 1.7f) { out.glucose2 = v2; out.mgdl2 = g.i(S_MGDL2) }
                else { out.glucose2 = 1.7f; out.mgdl2 = 0x1f }
            } else { out.glucose2 = 27.8f; out.mgdl2 = 500 }
        }
        out.warn = g.i(S_WARN)
        out.errorCode = g.i(S_ERROR)
        out.trend = g.i(S_TREND)
        out.sdGlu = g.f(S_SD_GLU)
    }
}
