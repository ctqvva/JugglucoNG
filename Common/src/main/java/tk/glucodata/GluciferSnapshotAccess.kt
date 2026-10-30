package tk.glucodata

import android.content.Context

/** Phone-only snapshots registered before the shared exporter starts. */
interface GluciferSnapshotBridge {
    fun journalJson(days: Int, includeNotes: Boolean, atMillis: Long): String
    fun predictionJson(context: Context, sensorId: String?, glucoseTime: Long): String
}

object GluciferSnapshotAccess {
    @Volatile private var bridge: GluciferSnapshotBridge? = null

    @JvmStatic
    fun register(provider: GluciferSnapshotBridge) { bridge = provider }

    // Missing or failed journal access must never look like an empty journal:
    // that would make the exporter delete acknowledged entries on the receiver.
    fun journalJson(days: Int, includeNotes: Boolean, atMillis: Long): String =
        checkNotNull(bridge) { "Glucifer journal provider unavailable" }
            .journalJson(days, includeNotes, atMillis)

    fun predictionJson(context: Context, sensorId: String?, glucoseTime: Long): String =
        bridge?.predictionJson(context, sensorId, glucoseTime) ?: "[]"
}
