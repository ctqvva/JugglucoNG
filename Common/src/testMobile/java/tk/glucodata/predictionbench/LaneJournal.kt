package tk.glucodata.predictionbench

import tk.glucodata.data.journal.CarbEntry
import tk.glucodata.data.journal.IobDose
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIobMath
import tk.glucodata.data.prediction.PredictionModelProfile

/** Insulin and carbohydrate on board at one moment, by the app's own IOB/COB arithmetic. */
class OnBoard(val iobUnits: Float, val activeInsulinUnits: Float, val cobGrams: Float) {
    companion object {
        val NONE = OnBoard(0f, 0f, 0f)
    }
}

/**
 * One person's journal, indexed once so on-board amounts can be read at thousands of past
 * moments. Each lookup takes an explicit knowledge cut-off: an entry counts at time `t` only
 * if it happened by `t` and had been logged (and last edited) by `knownAt`.
 */
class LaneJournal(entries: List<JournalEntry>, presets: Map<Long, JournalInsulinPreset>) {
    private class Item(val timestamp: Long, val knownFrom: Long, val dose: IobDose?, val carbs: CarbEntry?)

    private val items: List<Item> = entries.mapNotNull { e ->
        val amount = e.amount?.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
        val knownFrom = maxOf(e.createdAt, e.updatedAt)
        when (e.type) {
            JournalEntryType.INSULIN -> {
                val preset = e.insulinPresetId?.let(presets::get)?.takeIf { it.countsTowardIob } ?: return@mapNotNull null
                Item(e.timestamp, knownFrom, IobDose(e.timestamp, amount, preset.curvePoints), null)
            }
            JournalEntryType.CARBS -> Item(e.timestamp, knownFrom, null,
                CarbEntry(e.timestamp, amount, e.durationMinutes?.toFloat()))
            else -> null
        }
    }.sortedBy { it.timestamp }
    private val times = LongArray(items.size) { items[it].timestamp }

    fun onBoard(t: Long, knownAt: Long, lookbackMs: Long = 8L * 60 * 60_000): OnBoard {
        if (items.isEmpty()) return OnBoard.NONE
        var hi = times.binarySearch(t).let { if (it >= 0) it else -it - 2 }
        while (hi + 1 < times.size && times[hi + 1] == t) hi++
        if (hi < 0) return OnBoard.NONE
        var lo = times.binarySearch(t - lookbackMs).let { if (it >= 0) it else -it - 1 }
        while (lo > 0 && times[lo - 1] == t - lookbackMs) lo--
        val doses = ArrayList<IobDose>()
        val carbs = ArrayList<CarbEntry>()
        for (i in lo..hi) {
            val item = items[i]
            if (item.knownFrom > knownAt) continue
            item.dose?.let(doses::add)
            item.carbs?.let(carbs::add)
        }
        val iob = JournalIobMath.compute(doses, t)
        val cob = JournalIobMath.activeCarbsGrams(carbs, t) { PredictionModelProfile.DEFAULT_CARB_ABSORPTION_GRAMS_PER_HOUR }
        return OnBoard(iob.iobUnits, iob.eiobUnits, cob)
    }
}
