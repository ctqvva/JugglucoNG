package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CT-14/CT2 empirical model. Numbers come from the Blueberry app's own CT-14
 * series (3085 records): `rawGlucose = Iw + 0.4·elapsedDays` fit with a 0.0002
 * residual, and `glucose = (raw − 3.33) / 1.667` while no fingerstick was set.
 * Session start 1788803940000 matches the app's own `anytime_started_at`.
 */
class AnytimeCt14ModelTests {

    private val sessionStart = 1788803940000L

    private fun record(iw: Float, ib: Float = 1.1f, temp: Float = 30f, id: Int = 0) =
        AnytimeRawRecord(
            indexInPacket = 0,
            glucoseId = id,
            ibNa = ib,
            iwNa = iw,
            temperatureC = temp,
            recordBytes = ByteArray(0),
        )

    @Test
    fun rawIsTheCurrentAtSessionStart() {
        assertEquals(13.2f, AnytimeAlgorithm.ct14RawNa(13.2f, sessionStart, sessionStart), 0.0001f)
    }

    @Test
    fun rawAddsHalfANaPerDayOfAgingDrift() {
        // Blueberry recorded rawGlucose = 13.655 for Iw = 11.0 at this timestamp.
        val raw = AnytimeAlgorithm.ct14RawNa(11.0f, 1789377360000L, sessionStart)
        assertEquals(13.6547f, raw, 0.002f)
    }

    @Test
    fun defaultCalibrationAtSessionStart() {
        val result = AnytimeAlgorithm.computeCt14(
            record = record(iw = 13.2f, ib = 2.1f, temp = 29f),
            sampleTimeMs = sessionStart,
            sensorStartTimeMs = sessionStart,
        )
        assertEquals(5.9208f, result.mmol, 0.001f)
    }

    @Test
    fun defaultCalibrationWithAging() {
        val result = AnytimeAlgorithm.computeCt14(
            record = record(iw = 11.0f),
            sampleTimeMs = 1789377360000L,
            sensorStartTimeMs = sessionStart,
        )
        assertEquals(6.1936f, result.mmol, 0.002f)
    }

    @Test
    fun ibDoesNotChangeTheReading() {
        val a = AnytimeAlgorithm.computeCt14(record(iw = 11.0f, ib = 1.0f), sessionStart, sessionStart)
        val b = AnytimeAlgorithm.computeCt14(record(iw = 11.0f, ib = 1.1f), sessionStart, sessionStart)
        assertEquals(a.mmol, b.mmol, 0.0001f)
    }

    @Test
    fun temperatureDoesNotChangeTheReading() {
        // Unlike the MK4 chain, the CT2 reference model is temperature-independent.
        val cold = AnytimeAlgorithm.computeCt14(record(iw = 12f, temp = 20f), sessionStart, sessionStart)
        val warm = AnytimeAlgorithm.computeCt14(record(iw = 12f, temp = 38f), sessionStart, sessionStart)
        assertEquals(cold.mmol, warm.mmol, 0.0001f)
    }

}
