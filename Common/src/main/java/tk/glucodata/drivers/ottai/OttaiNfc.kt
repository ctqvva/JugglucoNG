// JugglucoNG — Ottai CGM driver
// OttaiNfc.kt — read-only NFC wake detection and diagnostics.
//
// Ottai generations expose different NFC technologies. Some Chinese sensors
// expose only raw NFC-A: presenting the RF field wakes them, but Android offers
// no standard memory interface to read. Never send an undocumented transceive
// command here; detecting the supported tag technology is sufficient to tell
// the setup flow that the wake tap happened.
//
// Enabled only while the setup wizard's "NFC dump" mode is on; otherwise taps
// fall straight through to the normal (Libre) NFC handler in MainActivity.

package tk.glucodata.drivers.ottai

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.SystemClock
import tk.glucodata.Applic
import tk.glucodata.Log

object OttaiNfc {
    private const val TAG = "OttaiNfc"
    // Some Android NFC stacks dispatch the same physical tap to the generic
    // foreground handler several seconds after the reader-mode callback.
    private const val CONSUMED_TAG_WINDOW_MS = 30_000L
    private const val NFC_A_TECH = "android.nfc.tech.NfcA"
    private const val NFC_V_TECH = "android.nfc.tech.NfcV"
    private const val MIFARE_ULTRALIGHT_TECH = "android.nfc.tech.MifareUltralight"
    private const val ISO_DEP_TECH = "android.nfc.tech.IsoDep"
    private const val NDEF_TECH = "android.nfc.tech.Ndef"
    private const val MIFARE_CLASSIC_TECH = "android.nfc.tech.MifareClassic"

    internal enum class WakeInterface {
        NFC_V,
        MIFARE_ULTRALIGHT,
        NFC_A_FIELD_ONLY,
        UNSUPPORTED,
    }

    data class Result(
        val details: String,
        val wakeDetected: Boolean,
    )

    /** When true, MainActivity.startnfc routes the tag here instead of to Libre. */
    @Volatile var dumpMode: Boolean = false

    /** Last dump text + an optional UI callback (set by the wizard). */
    @Volatile var lastDump: String? = null
    @Volatile var onResult: ((Result) -> Unit)? = null
    @Volatile private var consumedTagId: String? = null
    @Volatile private var consumeTagUntilMs: Long = 0L
    @Volatile private var wakeHapticPending: Boolean = false
    @Volatile private var activationSensorId: String? = null

    fun armForSetup() {
        dumpMode = true
    }

    fun armForActivationRetry(sensorId: String) {
        activationSensorId = OttaiConstants.canonicalSensorId(sensorId)
        dumpMode = true
    }

    fun disarmActivationRetry(sensorId: String) {
        val canonical = OttaiConstants.canonicalSensorId(sensorId)
        if (activationSensorId.equals(canonical, ignoreCase = true)) {
            activationSensorId = null
            dumpMode = false
        }
    }

    internal fun isActivationRetryArmed(sensorId: String?): Boolean =
        activationSensorId.equals(
            OttaiConstants.canonicalSensorId(sensorId),
            ignoreCase = true,
        )

    @JvmStatic
    fun consumeWakeHaptic(): Boolean {
        if (!wakeHapticPending) return false
        wakeHapticPending = false
        return true
    }

    /** Returns true if the tag belongs to the active/recent Ottai wake flow. */
    @JvmStatic
    fun onTag(tag: Tag): Boolean {
        val tagId = hex(tag.id)
        if (!dumpMode) {
            return tagId == consumedTagId && SystemClock.elapsedRealtime() <= consumeTagUntilMs
        }
        val wakeInterface = classifyTechs(tag.techList)
        // Libre is NFC-V; IsoDep is a pen. Neither is an Ottai wake. Returning true here used to
        // steal the tap, disarm dumpMode, and skip the real handler while activation stayed armed.
        if (!isOttaiWakeInterface(wakeInterface)) return false
        val details = runCatching { dump(tag) }
            .getOrElse { "NFC inspection error: $it" }
        val result = Result(details, true)
        consumedTagId = tagId
        consumeTagUntilMs = SystemClock.elapsedRealtime() + CONSUMED_TAG_WINDOW_MS
        wakeHapticPending = true
        dumpMode = false
        activationSensorId?.let { sensorId ->
            activationSensorId = null
            Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
        }
        lastDump = details
        Log.i(TAG, "\n$details")
        runCatching { onResult?.invoke(result) }
        return true
    }

    internal fun classifyTechs(techs: Array<String>): WakeInterface = when {
        techs.contains(NFC_V_TECH) -> WakeInterface.NFC_V
        techs.contains(ISO_DEP_TECH) -> WakeInterface.UNSUPPORTED
        techs.contains(MIFARE_ULTRALIGHT_TECH) -> WakeInterface.MIFARE_ULTRALIGHT
        techs.contains(NFC_A_TECH) -> WakeInterface.NFC_A_FIELD_ONLY
        else -> WakeInterface.UNSUPPORTED
    }

    /** Ottai/Syai wake is NFC-A field-only or Mifare Ultralight. NFC-V is Libre. */
    internal fun isOttaiWakeInterface(wakeInterface: WakeInterface): Boolean =
        wakeInterface == WakeInterface.NFC_A_FIELD_ONLY ||
            wakeInterface == WakeInterface.MIFARE_ULTRALIGHT

    private fun hex(b: ByteArray?): String =
        b?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: "null"

    private fun dump(tag: Tag): String {
        val sb = StringBuilder()
        val uid = tag.id
        sb.append("== Ottai NFC diagnostics ==\n")
        sb.append("UID=").append(hex(uid))
            .append("  techs=").append(tag.techList.joinToString(",")).append('\n')
        appendUidBreakdown(sb, uid)
        // Collect from every interface a wake tag exposes. NfcV/ISO15693 and IsoDep are
        // deliberately absent: onTag() returns before dump() for those (Libre and pens),
        // so dumping them would only ever transceive against somebody else's sensor.
        // All transceived commands below are standard, read-only MIFARE/NTAG reads — never an
        // undocumented or write command (see file header).
        val techs = tag.techList
        if (techs.contains(MIFARE_ULTRALIGHT_TECH)) runSection(sb, "MifareUltralight") { dumpMifareUltralight(sb, tag) }
        if (techs.contains(NFC_A_TECH)) runSection(sb, "NfcA") { dumpNfcA(sb, tag) }
        if (techs.contains(NDEF_TECH)) runSection(sb, "Ndef") { dumpNdef(sb, tag) }
        if (techs.contains(MIFARE_CLASSIC_TECH)) runSection(sb, "MifareClassic") { dumpMifareClassic(sb, tag) }
        return sb.toString()
    }

    private inline fun runSection(sb: StringBuilder, name: String, body: () -> Unit) {
        sb.append("--- ").append(name).append(" ---\n")
        runCatching { body() }.onFailure { sb.append(name).append(" error: ").append(it).append('\n') }
    }

    /** ISO15693 UID is 8 bytes, LSB-first: [0]=0xE0, [1]=IC manufacturer code, [2..7]=serial. */
    private fun appendUidBreakdown(sb: StringBuilder, uid: ByteArray) {
        if (uid.size != 8) {
            sb.append("uid: ").append(uid.size).append(" bytes (non-ISO15693 layout)\n")
            return
        }
        val prefix = uid[7].toInt() and 0xff
        val mfg = uid[6].toInt() and 0xff
        sb.append("uid.iso15693: prefix=%02x mfg=%02x serialLE=%s\n".format(prefix, mfg, hex(uid.copyOfRange(0, 6))))
    }

    /** Some Ottai M8 sensors expose Mifare Ultralight. Dump all readable pages. */
    private fun dumpMifareUltralight(sb: StringBuilder, tag: Tag) {
        val mu = MifareUltralight.get(tag) ?: run { sb.append("MifareUltralight interface unavailable\n"); return }
        mu.connect()
        try {
            val maxPages = when (mu.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> 16
                MifareUltralight.TYPE_ULTRALIGHT_C -> 48
                else -> 64
            }
            sb.append("MifareUltralight type=").append(mu.type).append(" maxPages=").append(maxPages).append('\n')
            var page = 0
            while (page < maxPages) {
                val data = runCatching { mu.readPages(page) }.getOrNull() ?: break // 4 pages = 16 bytes
                for (i in 0 until 4) if (page + i < maxPages) {
                    sb.append("pg %02x: %s\n".format(page + i, hex(data.copyOfRange(i * 4, i * 4 + 4))))
                }
                page += 4
            }
        } finally {
            runCatching { mu.close() }
        }
    }

    /** NfcA identity: ATQA/SAK/historical, and low-level GET_VERSION / READ if the tag answers. */
    private fun dumpNfcA(sb: StringBuilder, tag: Tag) {
        val a = NfcA.get(tag) ?: run { sb.append("NfcA interface unavailable\n"); return }
        a.connect()
        try {
            sb.append("atqa=").append(hex(a.atqa)).append(" sak=%04x".format(a.sak.toInt() and 0xffff))
                .append(" maxTransceive=").append(a.maxTransceiveLength).append('\n')
            // GET_VERSION (0x60) — standard NTAG/UL identify command, read-only.
            runCatching { a.transceive(byteArrayOf(0x60)) }.getOrNull()
                ?.let { sb.append("GET_VERSION: ").append(hex(it)).append('\n') }
            // READ (0x30) page 0 — standard type-2 read, read-only.
            runCatching { a.transceive(byteArrayOf(0x30, 0x00)) }.getOrNull()
                ?.let { sb.append("READ(0): ").append(hex(it)).append('\n') }
        } finally {
            runCatching { a.close() }
        }
    }

    /** NDEF payload, if the tag carries one. */
    private fun dumpNdef(sb: StringBuilder, tag: Tag) {
        val ndef = Ndef.get(tag) ?: run { sb.append("Ndef interface unavailable\n"); return }
        ndef.connect()
        try {
            sb.append("ndef type=").append(ndef.type).append(" maxSize=").append(ndef.maxSize)
                .append(" writable=").append(ndef.isWritable).append('\n')
            val msg = ndef.cachedNdefMessage ?: ndef.ndefMessage
            msg?.records?.forEachIndexed { idx, rec ->
                sb.append("rec[$idx] tnf=${rec.tnf} type=${hex(rec.type)} payload=${hex(rec.payload)}\n")
            }
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** MIFARE Classic identity (block reads need keys we do not have; report structure only). */
    private fun dumpMifareClassic(sb: StringBuilder, tag: Tag) {
        val mc = MifareClassic.get(tag) ?: run { sb.append("MifareClassic interface unavailable\n"); return }
        mc.connect()
        try {
            sb.append("MifareClassic type=").append(mc.type).append(" size=").append(mc.size)
                .append(" sectors=").append(mc.sectorCount).append(" blocks=").append(mc.blockCount).append('\n')
        } finally {
            runCatching { mc.close() }
        }
    }
}
