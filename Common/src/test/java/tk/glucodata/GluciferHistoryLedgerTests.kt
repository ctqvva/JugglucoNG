package tk.glucodata

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GluciferHistoryLedgerTests {
    private val now = 1788650000000L
    private fun point(time: Long) = GlucosePoint(time, 123f, 0f)

    @Test fun `hourly rescans skip delivered history and live readings after restart`() {
        val state = JSONObject()
        val points = listOf(point(now - 120000), point(now))
        GluciferHistoryLedger.acknowledge(state, points.map { it.timestamp }, now)
        val restored = JSONObject(state.toString())
        assertTrue(GluciferHistoryLedger.unseen(restored, points).isEmpty())
        val late = point(now - 60000)
        assertEquals(listOf(late), GluciferHistoryLedger.unseen(restored, points + late))
    }

    @Test fun `old acknowledged cursor is preserved when migrating`() {
        val state = JSONObject().put("cursor", now - 60000).put("refresh_at", now - 1)
        assertEquals(listOf(point(now)), GluciferHistoryLedger.unseen(state,
            listOf(point(now - 120000), point(now - 60000), point(now))))
    }

    @Test fun `ledger stays bounded without replaying the dropped prefix`() {
        val state = JSONObject()
        val stamps = (0..20165).map { now - it * 1000L }
        GluciferHistoryLedger.acknowledge(state, stamps, now)
        assertEquals(20160, state.getJSONArray("acknowledged").length())
        assertTrue(GluciferHistoryLedger.unseen(state, stamps.map { point(it) }).isEmpty())
        GluciferHistoryLedger.acknowledge(state, emptyList(), now + GluciferHistory.RETENTION_MS + 1)
        assertEquals(0, state.getJSONArray("acknowledged").length())
    }
}
