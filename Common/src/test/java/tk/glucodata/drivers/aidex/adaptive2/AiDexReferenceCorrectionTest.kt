package tk.glucodata.drivers.aidex.adaptive2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class AiDexReferenceCorrectionTest {
    private val time = 1_790_000_000_000L
    private val unit = AiDexAdaptiveV2.MGDL_PER_MMOL.toDouble()
    private fun mapping(scale: Double = 1.0, offset: Double = 0.0) = AiDexSignalNormalizer.Mapping(scale, offset)
    private fun AiDexReferenceCorrection.reference(raw: Float, output: Float, meter: Float,
        at: Long = time, map: AiDexSignalNormalizer.Mapping = mapping()) =
        accept(listOf(AiDexReference(at, meter)), output / unit, raw, map)
    private fun AiDexReferenceCorrection.shift(map: AiDexSignalNormalizer.Mapping = mapping(), at: Long = time) =
        offsetMmol(at, map) * unit

    @Test fun `a fresh reference corrects eighty percent of the filtered output error`() {
        val correction = AiDexReferenceCorrection()
        // Filtered output differs from mapped raw: using raw would get the sign wrong.
        correction.reference(raw = 150f, output = 130f, meter = 140f)
        assertEquals(8.0, correction.shift(), 0.001)
    }

    @Test fun `only affine mapping changes reduce an anchored error`() {
        val correction = AiDexReferenceCorrection()
        correction.reference(raw = 100f, output = 95f, meter = 130f)
        assertEquals(28.0, correction.shift(), 0.001)
        // Mapping at reference raw moved from 100 to 122; its 22 mg/dL share
        // is removed. The reference's 5 mg/dL filter residual is retained.
        assertEquals(10.4, correction.shift(mapping(1.2, 2.0)), 0.001)
        assertEquals(45.6.coerceAtMost(2.5 * unit), correction.shift(mapping(0.8, -2.0)), 0.001)
    }

    @Test fun `mapping catchup is evaluated separately at each references raw level`() {
        val correction = AiDexReferenceCorrection()
        correction.reference(raw = 50f, output = 50f, meter = 60f)
        correction.reference(raw = 200f, output = 200f, meter = 240f, at = time + 60_000L)
        // Both references describe a scale of 1.2, despite different raw levels.
        assertEquals(0.0, correction.shift(mapping(1.2), time + 60_000L), 0.001)
        // Today's raw is deliberately absent from the correction API: a genuine
        // glucose rise/fall cannot change either reference's drift subtraction.
    }

    @Test fun `a newer reference does not recursively include an earlier correction`() {
        val correction = AiDexReferenceCorrection()
        correction.reference(raw = 100f, output = 100f, meter = 130f)
        correction.reference(raw = 100f, output = 100f, meter = 130f, at = time + 60_000L)
        val oldWeight = exp(-60_000.0 / (1.5 * 24 * 60 * 60_000.0))
        assertEquals(30 * (oldWeight + 1) / (0.25 + oldWeight + 1),
            correction.shift(at = time + 60_000L), 0.001)
    }

    @Test fun `duplicate references cannot reset their anchor to a newer mapping`() {
        val correction = AiDexReferenceCorrection()
        correction.reference(raw = 100f, output = 100f, meter = 130f)
        correction.reference(raw = 200f, output = 220f, meter = 130f, map = mapping(1.1))
        assertEquals(16.0, correction.shift(mapping(1.1)), 0.001)
    }

    @Test fun `both correction directions retain the existing cap`() {
        for ((raw, meter, sign) in listOf(Triple(30f, 600f, 1), Triple(500f, 40f, -1))) {
            val correction = AiDexReferenceCorrection()
            correction.reference(raw, raw, meter)
            assertEquals(sign * 2.5 * unit, correction.shift(), 0.001)
        }
    }

    @Test fun `age decay and variance survive mapping catchup`() {
        val correction = AiDexReferenceCorrection()
        correction.reference(raw = 100f, output = 100f, meter = 130f)
        val later = time + 36 * 60 * 60_000L
        val weight = exp(-1.0)
        assertEquals(30 * weight / (0.25 + weight), correction.shift(at = later), 0.001)
        assertEquals(0.0, correction.shift(mapping(1.3), later), 0.001)
        assertTrue(correction.varianceMmol2(later) > 0f)
        assertTrue(correction.varianceMmol2(later) < correction.varianceMmol2(time))
    }

    @Test fun `anchors at different raw levels round trip exactly`() {
        val a = AiDexReferenceCorrection()
        a.reference(raw = 80f, output = 79f, meter = 90f)
        a.reference(raw = 140f, output = 138f, meter = 150f, at = time + 60_000L)
        val bytes = ByteArrayOutputStream().also { a.writeTo(DataOutputStream(it)) }.toByteArray()
        val b = AiDexReferenceCorrection().also { it.readFrom(DataInputStream(ByteArrayInputStream(bytes))) }
        for (map in listOf(mapping(), mapping(1.1, 3.0), mapping(0.9, -2.0))) {
            assertEquals(a.offsetMmol(time + 120_000L, map), b.offsetMmol(time + 120_000L, map), 0f)
        }
    }
}
