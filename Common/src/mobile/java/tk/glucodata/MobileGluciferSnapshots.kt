package tk.glucodata

import android.content.Context

object MobileGluciferSnapshots : GluciferSnapshotBridge {
    override fun journalJson(days: Int, includeNotes: Boolean, atMillis: Long): String =
        OutboundApiJournalSnapshot.gluciferJournalJson(days, includeNotes, atMillis)

    override fun predictionJson(context: Context, sensorId: String?, glucoseTime: Long): String =
        GluciferPredictionSnapshot.snapshotJson(context, sensorId, glucoseTime)
}
