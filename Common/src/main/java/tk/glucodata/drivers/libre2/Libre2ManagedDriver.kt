package tk.glucodata.drivers.libre2

import android.nfc.tech.NfcV
import java.security.SecureRandom
import tk.glucodata.AlgNfcV
import tk.glucodata.Log
import tk.glucodata.Natives

/**
 * Entry points of the Libre 2 Gen2 driver for the existing NFC and GATT code. Gen2
 * authentication and transport decryption run here; the native layer processes Libre 1/2
 * data with the open `libre2_imp` backend. No vendor library is involved.
 */
object Libre2ManagedDriver {
    private const val LOG_ID = "Libre2Managed"

    private val secureRandom = SecureRandom()

    private fun gen2Nfc(nfc: NfcV) = Libre2Gen2Nfc({ AlgNfcV.issuenfc(nfc, it) }, secureRandom::nextBytes)

    /** Gen2 activation of a new sensor. */
    @JvmStatic
    fun gen2Activate(nfc: NfcV, uid: ByteArray): Boolean = guarded("activate", false) {
        val driver = gen2Nfc(nfc)
        driver.activate(uid).also { report("activate", it, driver) }
    }

    /**
     * Gen2 enable-streaming; stores the provisioning record and BLE address in the sensor file.
     * True only when the sensor accepted it and the record was saved to the scanned sensor.
     * A sensor just activated has no entry yet ([newSensor]): accepted is enough there, and the
     * next scan creates the entry and enables streaming again.
     */
    @JvmStatic
    fun gen2EnableStreaming(nfc: NfcV, uid: ByteArray, patchInfo: ByteArray, newSensor: Boolean): Boolean = guarded("enable streaming", false) {
        val driver = gen2Nfc(nfc)
        val grant = driver.enableStreaming(uid)
        report("enable streaming", grant != null, driver)
        if (grant == null) return@guarded false
        val saved = Natives.USenabledStreaming(uid, patchInfo, grant.provisioning.toBytes(), grant.bleAddress)
        if (!saved) Log.e(LOG_ID, "Gen2 enable streaming: no sensor entry to save the login record to")
        saved || newSensor
    }

    /** Gen2 authenticated FRAM read, or null. */
    @JvmStatic
    fun gen2ReadFram(nfc: NfcV, uid: ByteArray, offset: Int, length: Int): ByteArray? = guarded("read FRAM", null) {
        val driver = gen2Nfc(nfc)
        driver.readFram(uid, offset, length).also { report("read FRAM", it != null, driver) }
    }

    /** Starts a Gen2 BLE login for one GATT link. */
    @JvmStatic
    fun gen2BleLogin(): Libre2Gen2BleLogin = Libre2Gen2BleLogin(secureRandom::nextBytes)

    private fun report(what: String, ok: Boolean, driver: Libre2Gen2Nfc) {
        if (ok) Log.i(LOG_ID, "Gen2 $what done")
        else Log.e(LOG_ID, "Gen2 $what failed: ${driver.lastFailure}")
    }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        Log.stack(LOG_ID, what, error)
        fallback
    }
}

/**
 * Gen2 BLE login and frame decryption for a single GATT link. The GATT
 * callback creates one per connection attempt and closes it when the link
 * ends, so a late notification from an earlier link has nothing to act on.
 */
class Libre2Gen2BleLogin internal constructor(private val random: (ByteArray) -> Unit) {
    enum class Reply { NEED_MORE, ACCEPTED, REJECTED }

    private var exchange: Libre2Gen2Exchange? = null
    private val reply = ByteArray(Libre2Gen2Protocol.BLE_LOGIN_REPLY_BYTES)
    private var haveFirstPart = false

    /** Why the last step failed, for the connection status line. */
    var failure: String? = null
        private set

    val authenticated: Boolean get() = exchange?.authenticated == true

    /**
     * Answers the sensor's 14-byte challenge. [record] is the stored 10- or
     * 12-byte provisioning record. Returns the 19 bytes to write, or null.
     */
    @Synchronized
    fun answerChallenge(uid: ByteArray?, record: ByteArray?, challenge: ByteArray): ByteArray? {
        close()
        failure = null
        if (uid == null) return failed("No ident")
        val provisioning = Libre2Gen2Provisioning.parse(record) ?: return failed("No Auth")
        val rootSlot = provisioning.rootSlot ?: return failed("Root slot ${provisioning.storedRootSlot} unsupported")
        val opened = Libre2Gen2Exchange.open(rootSlot, uid, ble = true) ?: return failed("No sensor key")
        val hostRandom = ByteArray(Libre2Gen2Protocol.RANDOM_BYTES).also(random)
        val request = opened.buildBleRequest(provisioning, challenge, hostRandom)
        if (request == null) {
            opened.close()
            return failed("Bad challenge")
        }
        exchange = opened
        return request
    }

    /** Feeds the 7- and 18-byte halves of the sensor's answer, in order. */
    @Synchronized
    fun onReplyPart(value: ByteArray): Reply {
        val current = exchange
        if (current == null || current.authenticated) return rejected("Unexpected login reply")
        when (value.size) {
            7 -> {
                value.copyInto(reply, 0)
                haveFirstPart = true
                return Reply.NEED_MORE
            }
            18 -> {
                if (!haveFirstPart) return rejected("Login reply out of order")
                value.copyInto(reply, 7)
                haveFirstPart = false
                return if (current.acceptAuthentication(reply)) Reply.ACCEPTED else rejected("Sensor rejected login")
            }
            else -> return rejected("Login reply length ${value.size}")
        }
    }

    /** Decrypted 46-byte frame, or null when not logged in or the size is wrong. */
    @Synchronized
    fun decryptFrame(frame: ByteArray): ByteArray? = exchange?.decryptBleFrame(frame)

    @Synchronized
    fun close() {
        exchange?.close()
        exchange = null
        haveFirstPart = false
        reply.fill(0)
    }

    private fun failed(reason: String): ByteArray? {
        failure = reason
        return null
    }

    private fun rejected(reason: String): Reply {
        failure = reason
        return Reply.REJECTED
    }
}
