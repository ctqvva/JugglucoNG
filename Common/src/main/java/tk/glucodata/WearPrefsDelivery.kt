package tk.glucodata

/** Requests get a fresh snapshot even when an identical earlier send was lost. */
internal class WearPrefsDelivery(
    private val encode: () -> ByteArray,
    private val send: (String?, ByteArray) -> Unit,
) {
    fun push() = sendSnapshot(null)

    fun pushTo(nodeName: String?) {
        if (nodeName != null) sendSnapshot(nodeName)
    }

    private fun sendSnapshot(nodeName: String?) {
        val payload = encode()
        if (payload.isNotEmpty()) send(nodeName, payload)
    }
}
