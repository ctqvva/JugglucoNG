package tk.glucodata

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GluciferJournalTests {
    private val now = 1_788_695_990_000L
    private fun entry(id: String, amount: Double = 2.0) = JSONObject().put("id", id).put("time_ms", now)
        .put("kind", "insulin").put("label", "Rapid").put("amount", amount)
    private fun next(state: JSONObject, entries: List<JSONObject>, enabled: Boolean = true,
        days: Int = 7, notes: Boolean = false) = GluciferJournal.next(state, "phone", entries, enabled, days, notes, now)!!

    @Test fun `journal is disabled by default and old receivers do not get journal payloads`() {
        val destination = OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_GLUCIFER)
        assertFalse(destination.gluciferJournal)
        assertFalse(destination.gluciferJournalNotes)
        assertEquals(7, destination.gluciferJournalDays)
        assertNull(GluciferJournal.next(JSONObject(), "phone", emptyList(), false, 7, false, now))
        assertFalse(GluciferJournal.supported("{}"))
        assertTrue(GluciferJournal.supported("{\"capabilities\":[\"journal_v1\"]}"))
    }
    @Test fun `created edited and deleted entries produce changes without glucose data`() {
        val state = JSONObject()
        val first = next(state, listOf(entry("j1")))
        assertFalse(first.has("glucose"))
        GluciferJournal.accept(state, first)
        assertNull(GluciferJournal.next(state, "phone", listOf(entry("j1")), true, 7, false, now + 86400000))
        val edited = next(state, listOf(entry("j1", 3.0)))
        assertEquals(3.0, edited.getJSONArray("entries").getJSONObject(0).getDouble("amount"), 0.0)
        GluciferJournal.accept(state, edited)
        val deleted = next(state, emptyList())
        assertEquals("j1", deleted.getJSONArray("deleted_ids").getString(0))
    }
    @Test fun `lost acknowledgement retries exact persisted payload then sends newer edit`() {
        val state = JSONObject()
        val first = next(state, listOf(entry("j1")))
        val restored = JSONObject(state.toString())
        val retry = next(restored, listOf(entry("j1", 4.0)))
        assertEquals(first.toString(), retry.toString())
        GluciferJournal.accept(restored, retry)
        val edited = next(restored, listOf(entry("j1", 4.0)))
        assertTrue(edited.getLong("sequence") > first.getLong("sequence"))
        assertEquals(4.0, edited.getJSONArray("entries").getJSONObject(0).getDouble("amount"), 0.0)
    }
    @Test fun `batches are bounded and acknowledged entries are not reuploaded`() {
        val entries = (1..40).map { entry("j$it") }
        val state = JSONObject()
        val delivered = mutableSetOf<String>()
        repeat(3) {
            val batch = next(state, entries)
            val values = batch.getJSONArray("entries")
            assertTrue(values.length() <= 16)
            for (i in 0 until values.length()) assertTrue(delivered.add(values.getJSONObject(i).getString("id")))
            GluciferJournal.accept(state, batch)
        }
        assertEquals(40, delivered.size)
        assertNull(GluciferJournal.next(state, "phone", entries, true, 7, false, now))
    }
    @Test fun `disabling sync clears receiver without retrying pending private notes`() {
        val state = JSONObject()
        val pending = next(state, listOf(entry("j1").put("note", "private note")), notes = true)
        val disabled = next(state, emptyList(), enabled = false)
        assertFalse(disabled.getBoolean("enabled"))
        assertFalse(disabled.toString().contains("private note"))
        assertTrue(disabled.getLong("sequence") > pending.getLong("sequence"))
        GluciferJournal.accept(state, disabled)
        assertEquals(0, state.getJSONObject("acknowledged").length())
    }
    @Test fun `history length changes are sent even when entries are unchanged`() {
        val state = JSONObject()
        GluciferJournal.accept(state, next(state, listOf(entry("j1"))))
        val longer = next(state, listOf(entry("j1")), days = 90)
        assertEquals(90, longer.getInt("history_days"))
        assertEquals(0, longer.getJSONArray("entries").length())
    }
    @Test fun `receipt must match journal type source sequence and status`() {
        val state = JSONObject()
        val payload = next(state, emptyList())
        val receipt = JSONObject().put("schema_version",2).put("type","journal").put("source_id","phone")
            .put("sequence",payload.getLong("sequence")).put("status","accepted")
        assertTrue(GluciferJournal.acknowledged(receipt.toString(),payload))
        assertFalse(GluciferJournal.acknowledged("{}",payload))
        receipt.put("sequence",1.5)
        assertFalse(GluciferJournal.acknowledged(receipt.toString(),payload))
        receipt.put("sequence",payload.getLong("sequence")).put("type","history")
        assertFalse(GluciferJournal.acknowledged(receipt.toString(),payload))
    }
    @Test fun `escaped long notes cannot exceed receiver byte limit`() {
        val entries=(1..16).map { entry("j$it").put("label","\n".repeat(128)).put("note","\u0001".repeat(256)) }
        val payload=next(JSONObject(),entries,notes=true)
        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size<=30000)
        assertTrue(payload.getJSONArray("entries").length() in 1..16)
    }
    @Test fun `object key order does not cause repeat uploads`() {
        val state = JSONObject()
        val original = entry("j1")
        GluciferJournal.accept(state, next(state, listOf(original)))
        val reordered = JSONObject()
        original.keys().asSequence().toList().reversed().forEach { reordered.put(it, original.get(it)) }
        assertNull(GluciferJournal.next(state, "phone", listOf(reordered), true, 7, false, now))
    }

}
