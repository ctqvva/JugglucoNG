package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering of the outbound message body, which is what SMS contacts actually read
 * ([OutboundApi.renderMessage] is called by `SmsMessageComposer`).
 */
class OutboundApiRenderMessageTests {

    private fun reading(
        mgdl: Int = 126,
        primaryText: String = mgdl.toString(),
        timeMillis: Long = 1_700_000_000_000L,
    ) = OutboundApi.Reading(
        eventId = "evt-1",
        recipient = "Mia",
        sensorId = "SN123",
        primaryText = primaryText,
        displayValue = mgdl.toDouble(),
        mgdl = mgdl,
        autoValue = Float.NaN,
        autoMgdl = 0,
        rawValue = Float.NaN,
        rateMgdlPerMinute = 0f,
        trendIndex = 0,
        trendNameFromInput = "Flat",
        trendRateMgdlPerMinute = 0f,
        timeMillis = timeMillis,
        sensorGen = 1,
        alarm = 0,
        test = false,
    )

    @Test
    fun staticTokensAreSubstituted() {
        val rendered = OutboundApi.renderMessage(
            template = "{value}|{unit}|{mgdl}|{mmol}|{trend}|{sensor}|{sensor_gen}|{alarm}|{rate_mgdl}|{recipient}|{event_id}",
            reading = reading(),
            status = OutboundApiSettings.TUNNEL_STATUS_IN_RANGE,
        )
        assertEquals("126|mg/dL|126|6.99|Flat|SN123|1|0|0.0|Mia|evt-1", rendered)
    }

    @Test
    fun unavailableAutoAndRawTokensRenderEmpty() {
        val rendered = OutboundApi.renderMessage(
            template = "[{auto}][{auto_mgdl}][{raw}][{raw_mgdl}]",
            reading = reading(),
        )
        assertEquals("[][][][]", rendered)
    }

    @Test
    fun statusTokensUseProvidedStatus() {
        val rendered = OutboundApi.renderMessage(
            template = "{status}|{status_emoji}",
            reading = reading(),
            status = OutboundApiSettings.TUNNEL_STATUS_LOW,
        )
        assertEquals("low|\uD83D\uDD34", rendered)
    }

    @Test
    fun iobFallsBackToZeroWhenUnavailable() {
        assertEquals("0", OutboundApi.renderMessage("{iob}", reading()))
    }

    @Test
    fun templateWithoutPlaceholdersIsUnchanged() {
        assertEquals("no tokens here", OutboundApi.renderMessage("no tokens here", reading()))
    }

    @Test
    fun needsJournalSnapshotOnlyForNonStaticTokens() {
        assertFalse(OutboundApi.needsJournalSnapshot("{value} {trend} {status} {time}"))
        assertTrue(OutboundApi.needsJournalSnapshot("{iob}"))
        assertTrue(OutboundApi.needsJournalSnapshot("{cob}"))
        assertTrue(OutboundApi.needsJournalSnapshot("{journal}"))
        assertTrue(OutboundApi.needsJournalSnapshot("{journal_events}"))
    }

    @Test
    fun statusEmojiMapping() {
        assertEquals("\uD83D\uDFE1", OutboundApi.statusEmoji(OutboundApiSettings.TUNNEL_STATUS_HIGH))
        assertEquals("\uD83D\uDD34", OutboundApi.statusEmoji(OutboundApiSettings.TUNNEL_STATUS_LOW))
        assertEquals("\u26A0\uFE0F", OutboundApi.statusEmoji(OutboundApiSettings.TUNNEL_STATUS_STALE))
        assertEquals("\u26AA", OutboundApi.statusEmoji(OutboundApiSettings.TUNNEL_STATUS_MISSED))
        assertEquals("\uD83D\uDFE2", OutboundApi.statusEmoji(OutboundApiSettings.TUNNEL_STATUS_IN_RANGE))
    }

    @Test
    fun formatNumberUsesFixedUsLocale() {
        assertEquals("6.99", OutboundApi.formatNumber(6.99295f, 2))
        assertEquals("", OutboundApi.formatNumber(Float.NaN, 2))
    }
}
