package tk.glucodata

import java.nio.ByteBuffer
import tk.glucodata.alerts.AlertType

/** An absolute quiet deadline, never a duration restarted on message receipt. */
data class WearAlertAcknowledgement(val kind: Int, val snoozed: Boolean, val actedAtMs: Long, val untilMs: Long) {
    val isCancellation: Boolean get() = untilMs == actedAtMs

    fun isValid(nowMs: Long): Boolean = AlertType.fromId(kind) in AlertType.settingsEntries &&
        actedAtMs > 0 && actedAtMs <= nowMs + 60_000 && untilMs >= actedAtMs &&
        untilMs - actedAtMs <= MAX_DURATION_MS

    fun supersedes(previous: WearAlertAcknowledgement?): Boolean = previous == null ||
        actedAtMs > previous.actedAtMs

    fun encode(): ByteArray = ByteBuffer.allocate(22).put(1.toByte()).putInt(kind)
        .put(if (snoozed) 1.toByte() else 0.toByte()).putLong(actedAtMs).putLong(untilMs).array()

    companion object {
        const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
        fun decode(data: ByteArray?, nowMs: Long): WearAlertAcknowledgement? {
            if (data == null || data.size != 22) return null
            val buffer = ByteBuffer.wrap(data)
            if (buffer.get().toInt() != 1) return null
            val kind = buffer.int
            val action = buffer.get().toInt()
            if (action !in 0..1) return null
            return WearAlertAcknowledgement(kind, action == 1, buffer.long, buffer.long)
                .takeIf { it.isValid(nowMs) }
        }
    }
}
