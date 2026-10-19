package tk.glucodata.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.annotation.Keep
import tk.glucodata.Log
import tk.glucodata.OutboundApiSettings

/**
 * Receives the sent/failed callbacks that [SmsGateway] attaches to every text and
 * folds them into the destination's status card, so the settings screen shows
 * whether the carrier actually took the message rather than only that we tried.
 */
@Keep
class SmsDeliveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val destinationId = intent.getStringExtra(EXTRA_DESTINATION_ID).orEmpty()
        if (destinationId.isBlank()) return
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)

        // Only the last part decides the outcome; earlier parts would otherwise
        // overwrite a failure with a success from the same message.
        if (part < partCount - 1 && resultCode == Activity.RESULT_OK) return

        if (resultCode == Activity.RESULT_OK) {
            Log.i(LOG_ID, "SMS accepted by carrier for $number")
            OutboundApiSettings.recordSuccess(context.applicationContext, destinationId, 200)
        } else {
            val reason = describe(resultCode)
            Log.e(LOG_ID, "SMS to $number rejected: $reason")
            OutboundApiSettings.recordAttempt(
                context.applicationContext,
                destinationId,
                -resultCode,
                "SMS to $number failed: $reason"
            )
        }
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "carrier rate limit"
        else -> "error $code"
    }

    companion object {
        private const val LOG_ID = "SmsDeliveryReceiver"
        const val ACTION_SENT = "tk.glucodata.sms.SENT"
        const val EXTRA_DESTINATION_ID = "destination_id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PART = "part"
        const val EXTRA_PART_COUNT = "part_count"
    }
}
