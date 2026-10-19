package tk.glucodata.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicInteger
import tk.glucodata.Log
import tk.glucodata.OutboundApiSettings

/**
 * Thin wrapper over the telephony stack.
 *
 * Keeps every platform quirk that the escalation logic must not care about in
 * one place: permission and hardware checks, multipart splitting, and the
 * sent/delivered callbacks that feed the destination's status card.
 */
@Keep
object SmsGateway {
    private const val LOG_ID = "SmsGateway"
    private val requestCodes = AtomicInteger(7100)

    @Keep
    enum class Availability {
        READY,
        NO_TELEPHONY,
        PERMISSION_MISSING
    }

    @JvmStatic
    fun availability(context: Context): Availability = when {
        !hasTelephony(context) -> Availability.NO_TELEPHONY
        !hasPermission(context) -> Availability.PERMISSION_MISSING
        else -> Availability.READY
    }

    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    @JvmStatic
    fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * Sends one text. Returns true when it was handed to the telephony stack;
     * actual delivery is reported later through [SmsDeliveryReceiver].
     */
    fun send(
        context: Context,
        destinationId: String,
        number: String,
        text: String
    ): Boolean {
        val appContext = context.applicationContext
        if (number.isBlank() || text.isBlank()) return false
        val availability = availability(appContext)
        if (availability != Availability.READY) {
            Log.e(LOG_ID, "SMS not sent to $number: $availability")
            return false
        }
        val manager = resolveManager(appContext) ?: run {
            Log.e(LOG_ID, "No SmsManager available")
            return false
        }
        return try {
            val parts = manager.divideMessage(text)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents += resultIntent(
                    context = appContext,
                    action = SmsDeliveryReceiver.ACTION_SENT,
                    destinationId = destinationId,
                    number = number,
                    part = index,
                    partCount = parts.size
                )
            }
            if (parts.size == 1) {
                manager.sendTextMessage(number, null, parts[0], sentIntents[0], null)
            } else {
                manager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            }
            true
        } catch (th: Throwable) {
            Log.e(LOG_ID, "sendTextMessage failed: ${Log.stackline(th)}")
            OutboundApiSettings.recordAttempt(
                appContext,
                destinationId,
                -1,
                "SMS to $number failed: ${th.javaClass.simpleName}"
            )
            false
        }
    }

    private fun resolveManager(context: Context): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (th: Throwable) {
        Log.e(LOG_ID, "resolveManager failed: $th")
        null
    }

    private fun resultIntent(
        context: Context,
        action: String,
        destinationId: String,
        number: String,
        part: Int,
        partCount: Int
    ): PendingIntent {
        val intent = Intent(context, SmsDeliveryReceiver::class.java)
            .setAction(action)
            .putExtra(SmsDeliveryReceiver.EXTRA_DESTINATION_ID, destinationId)
            .putExtra(SmsDeliveryReceiver.EXTRA_NUMBER, number)
            .putExtra(SmsDeliveryReceiver.EXTRA_PART, part)
            .putExtra(SmsDeliveryReceiver.EXTRA_PART_COUNT, partCount)
        return PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
