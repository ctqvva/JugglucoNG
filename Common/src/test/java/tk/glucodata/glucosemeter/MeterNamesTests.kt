package tk.glucodata.glucosemeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterNamesTests {
    @Test
    fun `a custom name replaces the advertised one`() {
        assertEquals("Accu-Chek Instant", MeterNames.resolve("meter+08003459", " Accu-Chek  Instant \n"))
    }

    @Test
    fun `no custom name keeps the advertised one`() {
        assertEquals("meter+08003459", MeterNames.resolve("meter+08003459", null))
        assertEquals("meter+08003459", MeterNames.resolve("meter+08003459", "   "))
    }

    @Test
    fun `a name is cut to the limit without a trailing space`() {
        val cleaned = MeterNames.clean("a".repeat(MeterNames.MAX_LENGTH - 1) + " bcd")
        assertEquals("a".repeat(MeterNames.MAX_LENGTH - 1), cleaned)
    }

    @Test
    fun `meters are recognised by service or by name`() {
        assertTrue(MeterAdvertisement.looksLikeMeter("Anything", listOf(MeterAdvertisement.GLUCOSE_SERVICE)))
        assertTrue(MeterAdvertisement.looksLikeMeter("Anything", listOf(MeterAdvertisement.VERIO_SERVICE)))
        assertTrue(MeterAdvertisement.looksLikeMeter("meter+08003459", emptyList()))
        assertTrue(MeterAdvertisement.looksLikeMeter("SatelliteOnline2738", emptyList()))
        assertTrue(MeterAdvertisement.isSatellite("SatelliteOnline2738", emptyList()))
    }

    @Test
    fun `other devices are not taken for meters`() {
        assertFalse(MeterAdvertisement.looksLikeMeter("KD-55XE9305", emptyList()))
        assertFalse(MeterAdvertisement.looksLikeMeter("Xiaomi Scale S400 2E9B", emptyList()))
        assertFalse(MeterAdvertisement.isSatellite("meter+08003459", listOf(MeterAdvertisement.GLUCOSE_SERVICE)))
    }
}
