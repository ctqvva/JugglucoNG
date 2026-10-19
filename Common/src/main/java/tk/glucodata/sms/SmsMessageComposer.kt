package tk.glucodata.sms

import android.content.Context
import androidx.annotation.Keep
import tk.glucodata.OutboundApi
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.alerts.AlertType

/**
 * Turns a [SmsMessagePlan] into the text a contact receives.
 *
 * Two lines, always in the same order, because someone reading this on a lock
 * screen at night needs the "what" before the numbers:
 *
 * ```
 * Mia: LOW alarm unanswered for 12 min
 * 3.4 mmol/L ↓ 02:14
 * ```
 *
 * The first line is generated and localized; the second is the destination's own
 * message template, so the token vocabulary is the same one the other
 * destinations use.
 */
@Keep
object SmsMessageComposer {

    /** Keeps a text within roughly two SMS segments even with an odd template. */
    private const val MAX_LENGTH = 300

    internal fun currentReading(): OutboundApi.Reading? = OutboundApi.currentReadingOrNull()

    fun alertName(context: Context, alertId: Int): String {
        val type = AlertType.fromId(alertId) ?: return ""
        return runCatching { context.getString(type.nameResId) }.getOrDefault(type.name)
    }

    internal fun compose(
        context: Context,
        destination: OutboundApiSettings.Destination,
        policy: SmsPolicy,
        message: SmsMessagePlan,
        reading: OutboundApi.Reading?
    ): String {
        val headline = headline(context, policy, message)
        val body = reading?.let {
            OutboundApi.renderMessage(
                template = destination.resolvedTemplate(),
                reading = it,
                destination = destination
            )
        }.orEmpty()
        return assemble(policy, headline, body)
    }

    fun composeTest(
        context: Context,
        destination: OutboundApiSettings.Destination,
        policy: SmsPolicy
    ): String {
        val headline = context.getString(R.string.sms_event_test)
        val body = currentReading()?.let {
            OutboundApi.renderMessage(
                template = destination.resolvedTemplate(),
                reading = it,
                destination = destination
            )
        } ?: context.getString(R.string.sms_body_no_reading)
        return assemble(policy, headline, body)
    }

    private fun headline(context: Context, policy: SmsPolicy, message: SmsMessagePlan): String =
        when (message.kind) {
            SmsEventKind.ALARM_UNACKED -> context.getString(
                R.string.sms_event_alarm_unacked,
                message.alertName,
                message.elapsedMinutes
            )
            SmsEventKind.CRITICAL ->
                if (message.episodeKey == SmsEscalation.EPISODE_CRITICAL_HIGH) {
                    context.getString(R.string.sms_event_critical_high)
                } else {
                    context.getString(R.string.sms_event_critical_low)
                }
            SmsEventKind.NO_DATA -> context.getString(
                R.string.sms_event_no_data,
                message.elapsedMinutes + policy.noDataMinutes
            )
            SmsEventKind.ALL_CLEAR -> context.getString(R.string.sms_event_all_clear)
            SmsEventKind.RELAY -> context.getString(R.string.sms_event_relay)
        }

    private fun assemble(policy: SmsPolicy, headline: String, body: String): String {
        val name = policy.subjectName.trim()
        val first = if (name.isEmpty()) headline else "$name: $headline"
        val text = if (body.isBlank()) first else "$first\n$body"
        return if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - 1) + "…"
    }
}
