package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import kotlin.math.round
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsEmissionPrecisionTest {

    private fun samples(minutes: Int): List<SibionicsSourceSample> =
        (1..minutes).map { i ->
            val raw = 5.6f + i * 0.01f
            SibionicsSourceSample(i, i * 60_000L, raw, 34f, 2_900f, 0)
        }

    private fun offGrid(value: Float): Boolean =
        abs(value * 10f - round(value * 10f)) > 1e-3f

    @Test
    fun stockKeepsTheVendorGridAndV2KeepsFullPrecision() {
        val rows = SibionicsReplayHarness.replay(
            samples = samples(TRACE_MINUTES),
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
        ).filter {
            it.index > SETTLE && it.stockMmol.isFinite() && it.adaptiveV2Mmol.isFinite()
        }

        val settled = TRACE_MINUTES - SETTLE
        assertTrue("settled rows missing: ${rows.size}/$settled", rows.size == settled)

        val stockOffGrid = rows.count { offGrid(it.stockMmol) }
        val v2OffGrid = rows.count { offGrid(it.adaptiveV2Mmol) }

        println("PRECISION stockOffGrid=$stockOffGrid/${rows.size} v2OffGrid=$v2OffGrid/${rows.size}")

        assertTrue("stock left the vendor 0.1 grid: $stockOffGrid/${rows.size}", stockOffGrid == 0)
        assertTrue("V2 output is still quantised: $v2OffGrid/${rows.size}", v2OffGrid > rows.size * 0.95)
    }

    private companion object {
        const val TRACE_MINUTES = 700
        const val SETTLE = 200
    }
}
