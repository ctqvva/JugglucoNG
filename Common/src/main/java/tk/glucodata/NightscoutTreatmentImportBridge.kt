package tk.glucodata

/**
 * The mobile-only Nightscout journal follower importer, as the shared code needs
 * it (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [NightscoutTreatmentImportAccess] then imports nothing (P2).
 */
interface NightscoutTreatmentImportBridge {
    fun importTreatments(sensorId: String, treatmentsJson: String): Int
}
