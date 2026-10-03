package tk.glucodata.glucosecomplication

import org.junit.Assert.*
import org.junit.Test

class MultiSensorComplicationTests {
    private val now = 1_000_000L
    private fun reading(sensor: String, time: Long = now, value: Float = 101f, rate: Float = 0f) =
        GlucoseComplicationData.Reading(value, value.toString(), false, time, rate, 0, sensor)

    @Test fun resolvesEachSensorInSelectionOrder() {
        val calls = mutableListOf<String>()
        val values = multiSensorValues(listOf("second", "first"), mapOf("second" to 42), now, 60_000L) {
            calls.add(it); reading(it)
        }
        assertEquals(listOf("second", "first"), calls)
        assertEquals(calls, values.map { it.sensorId })
        assertEquals(42, values.first().colorArgb)
        assertTrue(values.all { it.reading != null })
    }

    @Test fun stalePrimaryRetainsItsSlotBesideFreshPeer() {
        val values = multiSensorValues(listOf("primary", "peer"), emptyMap(), now, 60_000L) {
            reading(it, if (it == "primary") now - 60_000L else now)
        }
        assertNull(values.first().reading)
        assertNotNull(values.last().reading)
        assertEquals("— · 101.0", multiSensorText(values, false))
    }

    @Test fun rejectsFutureInvalidAndWrongSensorReadings() {
        val supplied = mapOf(
            "future" to reading("future", now + 1),
            "invalid" to reading("invalid", value = Float.NaN),
            "wrong" to reading("another"),
            "zero" to reading("zero", value = 0f),
            "unknown" to reading("unknown").copy(sensorId = null),
        )
        val values = multiSensorValues(supplied.keys.toList(), emptyMap(), now, 60_000L) { supplied[it] }
        assertTrue(values.all { it.reading == null })
    }

    @Test fun keepsMissingPeerAndDoesNotDuplicatePrimary() {
        val values = multiSensorValues(listOf("primary", "missing"), emptyMap(), now, 60_000L) {
            if (it == "primary") reading(it) else null
        }
        assertEquals("101.0 · —", multiSensorText(values, false))
    }

    @Test fun arrowAlternativeUsesEachSensorsOwnRateAndOmitsUnknownRate() {
        val values = listOf(
            MultiSensorValue("first", reading("first", rate = 3f), 0),
            MultiSensorValue("second", reading("second", rate = -1.5f), 0),
            MultiSensorValue("unknown", reading("unknown", rate = Float.NaN), 0),
        )
        assertEquals("101.0↑↑ · 101.0↓ · 101.0", multiSensorText(values, true))
        assertEquals("101.0 · 101.0 · 101.0", multiSensorText(values, false))
    }

    @Test fun slowDriftIsFlatLikeTheSharedArrowRenderer() {
        val values = listOf(MultiSensorValue("first", reading("first", rate = 0.4f), 0))
        assertEquals("101.0→", multiSensorText(values, true))
    }

    @Test fun mmolTextIsNotConvertedAgain() {
        val mmol = GlucoseComplicationData.Reading(5.6f, "5,6", true, now, 0f, 0, "first")
        assertEquals("5,6", multiSensorText(listOf(MultiSensorValue("first", mmol, 0)), false))
    }
}
