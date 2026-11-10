package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT2 profile table: per-prefix warmupMinutes (initNumber × 3 min) and
 * ratedLifetimeDays (endNumber × 3 min). SN08 confirmed live 2026-09-07:
 * warmup 20×3 = 60 min, lifetime 6720×3 = 20160 min = 14.0 days.
 */
class AnytimeCt2ProfileTests {

    private val ct2Prefixes = listOf(
        "SN04", "SN06", "SN08", "SN12", "SN18", "SN20", "SN22", "SN48", "SN50", "SN52",
    )

    @Test
    fun everyCt2PrefixResolvesToCt2Family() {
        for (prefix in ct2Prefixes) {
            val profile = AnytimeProfileResolver.resolve("$prefix-device")
            assertEquals("$prefix must be CT2", AnytimeConstants.Family.CT2, profile.family)
        }
    }

    @Test
    fun sn06AndSn12Have180MinuteWarmup() {
        assertEquals(180, AnytimeProfileResolver.resolve("SN06-x").warmupMinutes)
        assertEquals(180, AnytimeProfileResolver.resolve("SN12-x").warmupMinutes)
    }

    @Test
    fun otherCt2PrefixesHave60MinuteWarmup() {
        for (prefix in ct2Prefixes.filter { it !in setOf("SN06", "SN12") }) {
            assertEquals("$prefix warmup", 60, AnytimeProfileResolver.resolve("$prefix-x").warmupMinutes)
        }
    }

    @Test
    fun sn08Has60MinuteWarmupAnd14DayLife() {
        val profile = AnytimeProfileResolver.resolve("SN08-device")
        assertEquals(60, profile.warmupMinutes)
        assertEquals(14, profile.ratedLifetimeDays)
        assertEquals(6720, profile.endNumber)
    }

    @Test
    fun ct2CadenceIsThreeMinutes() {
        for (prefix in ct2Prefixes) {
            assertEquals(3, AnytimeProfileResolver.resolve("$prefix-x").readingIntervalMinutes)
        }
    }

    @Test
    fun onlyTheCt2GenerationAdvertisesTheCurrentSelfTest() {
        assertTrue(AnytimeConstants.supportsSelfTest(AnytimeConstants.Family.CT2))
        for (family in AnytimeConstants.Family.entries.filter { it != AnytimeConstants.Family.CT2 }) {
            assertFalse("$family must not advertise a self-test", AnytimeConstants.supportsSelfTest(family))
        }
    }

    @Test
    fun theReferenceSensorIsTheSelfTestFamily() {
        val entry = AnytimeConstants.resolveFamily("SN08402458")
        assertEquals(AnytimeConstants.Family.CT2, entry.family)
        assertTrue(AnytimeConstants.supportsSelfTest(entry.family))
    }

    @Test
    fun genericAdvertisedNameFallsBackToTheSerial() {
        // Stored-address reconnect on the main build: registry keeps the generic
        // "CGM Sensor" advert and gatt.device.name repeats it, so the SN## serial
        // must win or the CT-14 is driven with the CT3/CT2.5 check handshake.
        assertEquals(
            "SN08402178",
            AnytimeConstants.resolveHandshakeName("CGM Sensor", "CGM Sensor", null, "SN08402178"),
        )
        assertEquals(AnytimeConstants.Family.CT2, AnytimeProfileResolver.resolve(
            AnytimeConstants.resolveHandshakeName("CGM Sensor", "", null, "SN08402178"),
        ).family)
    }

    @Test
    fun aKnownAdvertisedNameBeatsTheSerial() {
        assertEquals("SN08-device", AnytimeConstants.resolveHandshakeName("", "SN08-device", null, "SN08402178"))
    }

    @Test
    fun warmupGateIsSampleBased() {
        val anchor = 1_000_000L
        val window = 60 * 60_000L
        assertTrue(AnytimeConstants.isWithinWarmup(anchor, anchor + window - 1, window))
        assertFalse(AnytimeConstants.isWithinWarmup(anchor, anchor + window, window))
        // No authoritative anchor, no sample, or no window disables the gate rather
        // than blanking readings on a guess.
        assertFalse(AnytimeConstants.isWithinWarmup(0L, anchor + 1, window))
        assertFalse(AnytimeConstants.isWithinWarmup(anchor, 0L, window))
        assertFalse(AnytimeConstants.isWithinWarmup(anchor, anchor + 1, 0L))
    }
}
