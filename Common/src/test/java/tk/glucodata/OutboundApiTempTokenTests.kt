package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the `{temp}` destination token (skin temperature in °C).
 *
 * The token renders whatever the Statistics temperature card would show for
 * the reading's sensor, via [SensorTemperature]. Unknown temperature renders
 * empty — never invented.
 */
class OutboundApiTempTokenTests {

    private fun reading(sensorId: String = "") = OutboundApi.Reading(
        eventId = "event",
        recipient = "",
        sensorId = sensorId,
        primaryText = "",
        displayValue = 5.5,
        mgdl = 99,
        autoValue = Float.NaN,
        autoMgdl = 0,
        rawValue = Float.NaN,
        rateMgdlPerMinute = Float.NaN,
        trendIndex = 0,
        trendNameFromInput = "Flat",
        trendRateMgdlPerMinute = Float.NaN,
        timeMillis = 1789376444000L,
        sensorGen = 0,
        alarm = 0,
        test = false
    )

    @Test
    fun tempIsAStaticToken() {
        // Temperature needs no journal snapshot — resolving it must stay cheap.
        assertFalse(OutboundApi.needsJournalSnapshot("{value} {unit} {temp} {time}"))
    }

    @Test
    fun formatTemperatureC() {
        assertEquals("33.2", OutboundApi.formatTemperatureC(33.24f))
        assertEquals("28.1", OutboundApi.formatTemperatureC(28.1f))
        assertEquals("", OutboundApi.formatTemperatureC(null))
        assertEquals("", OutboundApi.formatTemperatureC(Float.NaN))
    }

    @Test
    fun unknownTemperatureRendersEmpty() {
        // No app/registry backing in unit tests, so no sensor has a temperature.
        assertNull(SensorTemperature.latestTemperatureC(""))
        assertNull(SensorTemperature.latestTemperatureC("  "))
        assertNull(SensorTemperature.latestTemperatureC("X-222227KT3T"))

        val rendered = OutboundApi.renderMessage(
            template = "T:{temp}C",
            reading = reading(),
            status = OutboundApiSettings.TUNNEL_STATUS_IN_RANGE
        )
        assertEquals("T:C", rendered)
        assertFalse("{temp}" in rendered)
    }

    @Test
    fun tempTokenSurvivesAlongsideOtherTokens() {
        val rendered = OutboundApi.renderMessage(
            template = "{value} {unit} {temp} {sensor}",
            reading = reading(sensorId = "X-222227KT3T"),
            status = OutboundApiSettings.TUNNEL_STATUS_IN_RANGE
        )
        assertTrue(rendered.startsWith("99 mg/dL "))
        assertTrue(rendered.endsWith(" X-222227KT3T"))
        assertFalse("{temp}" in rendered)
    }
}
