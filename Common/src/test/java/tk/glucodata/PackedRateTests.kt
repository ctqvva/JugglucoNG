package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rate native glucoselong packs into bits 32..47 of a glucose result. */
class PackedRateTests {
    private fun packed(rateTimes1000: Int, mgL: Int = 1000): Long =
        ((rateTimes1000.toLong() and 0xFFFF) shl 32) or mgL.toLong()

    @Test fun finiteRatesRoundTrip() {
        assertEquals(1.5f, SuperGattCallback.packedRate(packed(1500)), 0f)
        assertEquals(-2.3f, SuperGattCallback.packedRate(packed(-2300)), 0f)
        assertEquals(0f, SuperGattCallback.packedRate(packed(0)), 0f)
        assertEquals(32.767f, SuperGattCallback.packedRate(packed(0x7FFF)), 0f)
        assertEquals(-32.767f, SuperGattCallback.packedRate(packed(-0x7FFF)), 0f)
    }

    @Test fun missingRateReadsAsNaN() {
        // A Dexcom trend of 0x7F ("no trend") reaches glucoselong as NAN.
        assertTrue(SuperGattCallback.packedRate(packed(0x8000)).isNaN())
    }

    @Test fun nativeMarksMissingRateWithTheSameValue() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "Common/src/main/cpp/g.cpp").isFile }
        val source = File(root, "Common/src/main/cpp/g.cpp").readText()
        val packing = source.substringAfter("static jlong glucoselong(").substringBefore("calibrateNow(")
        assertTrue("glucoselong must pack a NaN rate as 0x8000", Regex("""isnan\(drate\)\s*\?\s*jlong\{0x8000\}""").containsMatchIn(packing))
        assertTrue("finite rates must stay clear of 0x8000", packing.contains("-0x7FFF"))
    }
}
