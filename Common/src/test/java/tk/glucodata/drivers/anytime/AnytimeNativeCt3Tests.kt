package tk.glucodata.drivers.anytime

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a fixed synthetic CT3 fixture through the ported `yqidui_PX3` chain and
 * checks it against values produced by the vendor binary in the Unicorn oracle
 * (Common/.../emu_ct3.py in the RE work dir; see
 * docs/anytime-native-algorithm-plan.md). The fixture does not trigger PULSE,
 * which the port deliberately omits.
 */
class AnytimeNativeCt3Tests {

    private fun runFixture(): FloatArray {
        val state = AnytimeNativeState()
        var prevId = 0
        val out = FloatArray(N)
        for (k in 0 until N) {
            val gid = 20 + k
            val input = AnytimeNativeInput().apply {
                glucoseId = gid
                iw = 30.0f + 0.05f * k + 2.0f * sin(k / 20.0f).toFloat()
                ib = 1.0f
                temperatureC = 33.0f + 0.5f * sin(k / 50.0f).toFloat()
                flags = if (k == 1) 0x80 else 0
                newBgValue = if (k == 1) 120.0f else 0f
                k0 = 1.0f
                r = 1.0f
                width = 1.0f
                height = 170f
                weight = 70f
                age = 30
                userType = 0
            }
            out[k] = AnytimeNativeAlgorithm.process(input, state, prevId).glucose
            prevId = gid
        }
        return out
    }

    @Test
    fun replayMatchesVendorOracle() {
        val out = runFixture()
        val golden = mapOf(
            0 to 24.397055f,
            1 to 24.551136f,
            2 to 24.679115f,
            3 to 24.801176f,
            5 to 25.046093f,
            10 to 25.766684f,
            20 to 27.005564f,
            50 to 27.766657f,
            80 to 26.190838f,
            120 to 27.799999f,
            160 to 27.799999f,
            199 to 27.799999f,
        )
        for ((k, expected) in golden) {
            assertTrue(
                "sample $k: expected $expected, got ${out[k]}",
                abs(out[k] - expected) <= MAX_ERROR,
            )
        }
    }

    @Test
    fun firstSamplesAreNotFloored() {
        val out = runFixture()
        assertTrue("glucose stays at the 1.7 floor", out[0] > 20f)
    }

    @Test
    fun stateEncodeDecodeRoundTrips() {
        val state = AnytimeNativeState()
        var prevId = 0
        for (k in 0 until 10) {
            val gid = 20 + k
            val input = AnytimeNativeInput().apply {
                glucoseId = gid
                iw = 30.0f + 0.05f * k
                ib = 1.0f
                temperatureC = 33f
                k0 = 1.0f
                r = 1.0f
            }
            AnytimeNativeAlgorithm.process(input, state, prevId)
            prevId = gid
        }
        val restored = AnytimeNativeState.decode(state.encode())
        assertTrue("decode returned null", restored != null)
        for (off in 0 until AnytimeNativeState.STATE_BYTES step 4) {
            assertEquals("state $off", state.i(off), restored!!.i(off))
        }
        for (off in 0 until AnytimeNativeState.GLOBALS_BYTES step 4) {
            assertEquals("globals $off", state.gi(off), restored!!.gi(off))
        }
        for (off in 0 until AnytimeNativeState.SMOOTH_BYTES step 4) {
            assertEquals("smooth $off", state.si(off), restored!!.si(off))
        }
    }

    companion object {
        private const val N = 200
        private const val MAX_ERROR = 1e-3f
    }
}
