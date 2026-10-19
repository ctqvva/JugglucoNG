package tk.glucodata.drivers.mq

import java.net.URLEncoder
import java.util.Locale

object MQVendorIdentity {
    private val serialPattern = Regex("[A-Z][0-9]{6,15}")
    private val macPattern = Regex("[0-9A-F]{12}")

    /** The advertised/printed serial, e.g. W25101399, is the vendor bleId. A MAC is not. */
    fun bleId(name: String?): String? = name?.trim()?.uppercase(Locale.US)
        ?.removePrefix("S/N:")?.trim()?.takeIf { serialPattern.matches(it) && !macPattern.matches(it) }

    fun mac(address: String?): String? {
        val compact = address?.trim()?.replace(":", "")?.uppercase(Locale.US) ?: return null
        return compact.takeIf(macPattern::matches)?.chunked(2)?.joinToString(":")
    }

    internal fun startForm(serial: String, address: String, account: String, qrCode: String): String {
        val id = requireNotNull(bleId(serial)) { "MQ vendor bleId must be a transmitter serial" }
        val mac = requireNotNull(mac(address)) { "MQ vendor session requires a Bluetooth MAC" }
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        return "bleId=${encode(id)}&mac=${encode(mac)}&account=${encode(account)}&qrCode=${encode(qrCode)}"
    }
}
