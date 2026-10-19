package tk.glucodata

/**
 * The mobile-only journal treatment uploader, as the shared code needs it
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing, and [JournalTreatmentUploadAccess] then reports "nothing to receive"
 * and a successful no-op upload (P2), which is what the old absent-class path
 * did.
 */
interface JournalTreatmentUploadBridge {
    fun getReceiveTreatments(): Boolean

    fun uploadAll(useV3: Boolean): Boolean
}
