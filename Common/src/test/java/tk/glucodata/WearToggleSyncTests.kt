package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire the watch's on/off switches travel on. The phone is the authority,
 * so what matters is that a request survives the trip intact and that anything
 * unrecognised is dropped rather than half-applied.
 */
class WearToggleSyncTests {

    private fun toggle(scope: String, id: String, enabled: Boolean) =
        WearToggleSync.Toggle(scope, id, enabled)

    @Test
    fun roundTripsEveryScope() {
        val toggles = listOf(
            toggle(WearToggleSync.SCOPE_EXCHANGE, ExchangeToggles.ID_GADGETBRIDGE, true),
            toggle(WearToggleSync.SCOPE_ALERT, "3", false),
            toggle(WearToggleSync.SCOPE_PREF, "prediction", true),
        )
        assertEquals(toggles, WearToggleSync.decode(WearToggleSync.encode(toggles)))
    }

    @Test
    fun anEmptyOrUnreadablePayloadYieldsNothing() {
        assertTrue(WearToggleSync.decode(null).isEmpty())
        assertTrue(WearToggleSync.decode(ByteArray(0)).isEmpty())
        assertTrue(WearToggleSync.decode("garbage".toByteArray()).isEmpty())
    }

    @Test
    fun aLineWithoutAUsableBooleanIsSkipped() {
        // "maybe" is not a state; applying it as false would silently turn
        // something off.
        val payload = "x:gadgetbridge=maybe\nx:xdrip_broadcast=true\n".toByteArray()
        val decoded = WearToggleSync.decode(payload)
        assertEquals(1, decoded.size)
        assertEquals(ExchangeToggles.ID_XDRIP_BROADCAST, decoded[0].id)
        assertTrue(decoded[0].enabled)
    }

    @Test
    fun malformedLinesDoNotTakeTheRestOfThePayloadWithThem() {
        val payload = "\nnot-a-line\nx:gadgetbridge=true\n=broken\n".toByteArray()
        val decoded = WearToggleSync.decode(payload)
        assertEquals(1, decoded.size)
        assertEquals(ExchangeToggles.ID_GADGETBRIDGE, decoded[0].id)
    }

    @Test
    fun everyExchangeToggleHasAStableDistinctId() {
        // The ids travel on the wire; a duplicate or a rename would silently
        // point a switch at the wrong output.
        val ids = ExchangeToggles.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        listOf(
            ExchangeToggles.ID_LIBREVIEW,
            ExchangeToggles.ID_PATCHED_LIBRE,
            ExchangeToggles.ID_XDRIP_BROADCAST,
            ExchangeToggles.ID_GLUCODATA,
            ExchangeToggles.ID_GADGETBRIDGE,
            ExchangeToggles.ID_WATCHDRIP,
            ExchangeToggles.ID_XDRIP_WEBSERVER,
            ExchangeToggles.ID_SEPARATE,
        ).forEach { assertTrue("missing $it", ids.contains(it)) }
        // The ids are the wire format's field names, so neither separator may
        // appear in one or a payload would parse into the wrong toggle.
        assertTrue(ids.none { it.contains(':') || it.contains('=') })
    }

    @Test
    fun idsAreLookedUpByExactMatch() {
        assertEquals(
            ExchangeToggles.ID_GADGETBRIDGE,
            ExchangeToggles.byId(ExchangeToggles.ID_GADGETBRIDGE)?.id,
        )
        assertEquals(null, ExchangeToggles.byId("nope"))
        assertEquals(null, ExchangeToggles.byId(null))
    }
}
