package tk.glucodata

/**
 * Registration seam for [NightscoutTreatmentImportBridge] (plan P1/Q1).
 *
 * NightscoutFollowerManager used to find
 * `tk.glucodata.data.journal.NightscoutJournalFollowerImporter` by name. No keep
 * rule protected it, so in a minified build the lookup threw and followed
 * treatments were silently never imported. Explicit registration leaves ordinary
 * interface calls behind.
 *
 * The method keeps the old reflective failure contract: a failure inside the
 * importer is logged and degrades to zero.
 */
object NightscoutTreatmentImportAccess {
    private const val TAG = "NightscoutTreatmentImportAccess"

    @Volatile
    private var bridge: NightscoutTreatmentImportBridge? = null

    @JvmStatic
    fun register(bridge: NightscoutTreatmentImportBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun importTreatments(sensorId: String, treatmentsJson: String): Int =
        runCatching { bridge?.importTreatments(sensorId, treatmentsJson) }
            .onFailure { Log.stack(TAG, "importTreatments failed", it) }
            .getOrNull() ?: 0
}
