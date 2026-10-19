package tk.glucodata

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process scheduler for the Telegram "stale" / "missed reading" edit.
 *
 * The producer ([OutboundApi.sendTelegram]) calls [schedule] right after a
 * successful send. We queue a single [Runnable] per destination in a static
 * [Handler]; when it fires, we post a **new** Telegram message ("⚠️ Stale" at
 * the stale threshold, "⚪ Missed reading" at the missed threshold) rather than
 * editing the bubble. This preserves the last valid reading in the bubble and
 * leaves stale/missed events as distinct, timestamped messages in the chat
 * history for easy retrospective review.
 *
 * Limitation: this is in-process only. If the app process is killed between
 * sends, the stale check is lost until the next CGM reading arrives and
 * re-schedules. That's acceptable for a personal 1:1 CGM bubble — the worst
 * case is one missed stale period after a phone restart, with no
 * notification cost.
 */
object TelegramStaleCheckWork {

    private val workerThread = HandlerThread("telegram-stale-check").also { it.start() }
    private val handler = Handler(workerThread.looper)
    private val pending = ConcurrentHashMap<String, Runnable>()

    private const val STALE_PREFIX = "telegram_stale_check:"
    private const val TRANSIENT_RETRY_DELAY_MS = 60_000L
    // Derived from TRANSIENT_RETRY_DELAY_MS to keep the invariant self-enforcing:
    // a recipient that just posted STALE is skipped on the 60 s retry cycle but
    // re-evaluated when the missed-threshold timer fires later.
    private const val STALE_THROTTLE_MS = TRANSIENT_RETRY_DELAY_MS + 10_000L

    fun schedule(context: Context, destinationId: String, delayMs: Long) {
        val key = STALE_PREFIX + destinationId
        pending.remove(key)?.let { handler.removeCallbacks(it) }
        val appContext = context.applicationContext
        val runnable = Runnable {
            pending.remove(key)
            run(appContext, destinationId)
        }
        pending[key] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    fun cancel(destinationId: String) {
        val key = STALE_PREFIX + destinationId
        pending.remove(key)?.let { handler.removeCallbacks(it) }
    }

    private fun run(context: Context, destinationId: String) {
        val config = OutboundApiSettings.load(context)
        val destination = config.findDestination(destinationId) ?: return
        if (!destination.enabled || !destination.staleEnabled) return
        if (destination.normalizedPreset() != OutboundApiSettings.PRESET_TELEGRAM_BOT) return

        val now = System.currentTimeMillis()
        val staleThresholdMs = destination.staleThresholdMinutes.coerceIn(1, 120) * 60_000L
        val missedThresholdMs = destination.missedThresholdMinutes.coerceIn(
            destination.staleThresholdMinutes.coerceIn(1, 120) + 1,
            240
        ) * 60_000L

        var earliestNextDelayMs = Long.MAX_VALUE
        val recipients = destination.recipients()
        for (recipient in recipients) {
            val lastSentMs = destination.lastSentAtMsByRecipient[recipient] ?: 0L
            if (lastSentMs <= 0L) continue
            val elapsedMs = now - lastSentMs
            val status = when {
                elapsedMs >= missedThresholdMs -> OutboundApiSettings.TUNNEL_STATUS_MISSED
                elapsedMs >= staleThresholdMs -> OutboundApiSettings.TUNNEL_STATUS_STALE
                else -> continue
            }
            val lastStaleMs = destination.lastStaleAtMsByRecipient[recipient] ?: 0L
            if (lastStaleMs > 0L && now - lastStaleMs < STALE_THROTTLE_MS) continue
            val hasActiveBubble = (destination.lastMessageIdByRecipient[recipient] ?: 0L) > 0L
            if (!hasActiveBubble) continue

            val text = renderStaleText(status, now)
            val result = postSend(destination, recipient, text)
            when (result) {
                true -> {
                    OutboundApiSettings.recordStaleAt(context, destinationId, recipient, now)
                    if (status == OutboundApiSettings.TUNNEL_STATUS_STALE) {
                        val remaining = (lastSentMs + missedThresholdMs) - now
                        if (remaining > 0) earliestNextDelayMs = minOf(
                            earliestNextDelayMs,
                            remaining + OutboundApiSettings.STALE_CHECK_SLACK_MS
                        )
                    }
                }
                false -> {
                    // Definitive Telegram rejection (bot blocked, wrong chat_id, etc.) —
                    // clear state so next reading starts a fresh bubble.
                    OutboundApiSettings.clearRecipientState(
                        context = context,
                        destinationId = destinationId,
                        recipient = recipient
                    )
                }
                null -> {
                    // Transient network error — reschedule so the notification is
                    // retried rather than silently dropped.
                    earliestNextDelayMs = minOf(earliestNextDelayMs, TRANSIENT_RETRY_DELAY_MS)
                }
            }
        }
        if (earliestNextDelayMs < Long.MAX_VALUE) {
            schedule(context, destinationId, earliestNextDelayMs)
        }
    }

    private fun renderStaleText(status: String, nowMs: Long): String {
        val time = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()
        ).format(Date(nowMs))
        val emoji = OutboundApi.statusEmoji(status)
        val label = when (status) {
            OutboundApiSettings.TUNNEL_STATUS_MISSED -> "Missed reading."
            OutboundApiSettings.TUNNEL_STATUS_STALE -> "Stale — waiting for next reading."
            else -> "Stale."
        }
        return "$emoji $label ($time)"
    }

    /**
     * Posts a new stale/missed message to the chat (does not edit the existing bubble).
     * Returns true on 2xx, false on a definitive API rejection (4xx),
     * null on transient network failure (caller should leave state intact).
     */
    private fun postSend(
        destination: OutboundApiSettings.Destination,
        recipient: String,
        text: String
    ): Boolean? {
        val sendUrl = destination.resolvedUrl()
        val body = JSONObject()
            .put("chat_id", recipient)
            .put("text", text)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            val connection = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                destination.headers.split('\n')
                    .filter { it.contains(':') }
                    .forEach { line ->
                        val (k, v) = line.split(':', limit = 2).let { it[0].trim() to it[1].trim() }
                        if (k.equals("Content-Type", ignoreCase = true)) return@forEach
                        setRequestProperty(k, v)
                    }
            }
            try {
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                // 429 (rate limit) and 5xx (server error) are transient — leave state intact.
                when {
                    code in 200..299 -> true
                    code == 429 || code >= 500 -> null
                    else -> false
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
