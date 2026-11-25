package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthNativeRebuildTests {
    @Test fun temperaturesSurviveTheSavedForm() {
        val temperatures = mapOf(28_333_334L to 31.5f, 28_333_337L to 33.2f)
        assertEquals(temperatures, ICanHealthNativeRebuild.decode(ICanHealthNativeRebuild.encode(temperatures)))
    }

    @Test fun nothingIsInventedForReadingsWithoutATemperature() {
        val encoded = ICanHealthNativeRebuild.encode(mapOf(1L to Float.NaN, 2L to 0f, 3L to -1f))
        assertTrue(ICanHealthNativeRebuild.decode(encoded).isEmpty())
        assertTrue(ICanHealthNativeRebuild.decode("").isEmpty())
        assertTrue(ICanHealthNativeRebuild.decode("garbage;7,;,7;8,x;").isEmpty())
    }

    @Test fun nativeEntriesCarryTheSecondOfTheirReading() {
        val second = 1_700_000_040L
        val unpacked = ICanHealthNativeRebuild.unpackStream(longArrayOf((second shl 16) or 315L, (second + 60) shl 16))
        assertEquals(mapOf(second / 60 to 31.5f), unpacked)
    }

    @Test fun retriesBackOffToAQuarterOfAnHour() {
        assertEquals(
            listOf(30L, 60L, 120L, 240L, 480L, 900L, 900L),
            (1..7).map { ICanHealthHistoryPolicy.nativeRebuildRetryDelayMs(it) / 1000L },
        )
    }
}
