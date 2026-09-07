package tk.glucodata

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GluciferBackfillStatusTests {
    @Test fun `unacknowledged transitions are reconciled even if the desired state returns to its old value`() {
        val state = JSONObject().put("backfill_active", true)
        assertFalse(GluciferBackfillStatus.needsReport(state, true))
        assertTrue(GluciferBackfillStatus.needsReport(state, false))
        state.put("status_pending", GluciferBackfillStatus.build("phone", false))
        assertTrue(GluciferBackfillStatus.needsReport(state, true))
    }

    @Test fun `older receivers do not get unsupported status messages`() {
        assertFalse(GluciferBackfillStatus.supported("{}"))
        assertTrue(GluciferBackfillStatus.supported("{\"capabilities\":[\"backfill_status\"]}"))
    }

    @Test fun `transition acknowledgement must match identity and boolean state`() {
        val payload = GluciferBackfillStatus.build("phone", true)
        val ack = JSONObject(payload.toString()).put("status", "accepted")
        assertTrue(GluciferBackfillStatus.acknowledged(ack.toString(), payload))
        ack.put("active", false)
        assertFalse(GluciferBackfillStatus.acknowledged(ack.toString(), payload))
        ack.put("active", true).put("status_id", "wrong")
        assertFalse(GluciferBackfillStatus.acknowledged(ack.toString(), payload))
        assertFalse(payload.has("glucose"))
        assertFalse(payload.has("alerts"))
    }
}
