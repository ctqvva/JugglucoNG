package tk.glucodata

/**
 * Registration seam for [JournalTreatmentUploadBridge] (plan P1/Q1).
 *
 * NightPost used to find `tk.glucodata.data.journal.JournalTreatmentUploader` by
 * name and invoke `uploadAll`/`getReceiveTreatments`. No keep rule protected it,
 * so in a minified build the lookup threw and every treatment upload silently
 * failed. Explicit registration leaves ordinary interface calls behind.
 *
 * Every method keeps the old reflective failure contract: a failure inside the
 * uploader is logged and degrades to the default.
 */
object JournalTreatmentUploadAccess {
    private const val TAG = "JournalTreatmentUploadAccess"

    @Volatile
    private var bridge: JournalTreatmentUploadBridge? = null

    @JvmStatic
    fun register(bridge: JournalTreatmentUploadBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** False without a journal, so NightPost treats uploads as a no-op success. */
    @JvmStatic
    fun getReceiveTreatments(): Boolean =
        runCatching { bridge?.getReceiveTreatments() }
            .onFailure { Log.stack(TAG, "getReceiveTreatments failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun uploadAll(useV3: Boolean): Boolean =
        runCatching { bridge?.uploadAll(useV3) }
            .onFailure { Log.stack(TAG, "uploadAll failed", it) }
            .getOrNull() ?: true
}
