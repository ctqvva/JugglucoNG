package tk.glucodata.drivers.aidex.bloodcontrol

import tk.glucodata.drivers.aidex.AiDexProvisionedKeys
import tk.glucodata.drivers.aidex.AiDexProvisioningStore.ConditionalSaveResult
import tk.glucodata.drivers.aidex.AiDexProvisioningStore.ConditionalSaveStatus

/** One connection's automatic material. Unvalidated candidates never reach the store/registry. */
internal class AiDexStagedPairingMaterial(
    val serial: String,
    keys: AiDexProvisionedKeys,
    expectedRevision: String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val keys = AiDexProvisionedKeys(keys.secret.copyOf(), keys.iv.copyOf())
    var expectedRevision: String? = expectedRevision
        private set
    var validated = false
        private set
    private var retryAt = 0L
    private var failures = 0
    private var superseded = false
    private var saved = false

    fun validate() { validated = true }

    /** Called by valid live frames; a failed write retries the same material, without a fetch. */
    fun persistIfDue(save: (String?) -> ConditionalSaveResult): ConditionalSaveStatus? {
        if (superseded) return ConditionalSaveStatus.SUPERSEDED
        if (saved) return ConditionalSaveStatus.SAVED
        if (!validated || failures >= MAX_WRITE_ATTEMPTS || now() < retryAt) return null
        val result = save(expectedRevision)
        when (result.status) {
            ConditionalSaveStatus.SAVED -> saved = true
            ConditionalSaveStatus.SUPERSEDED -> superseded = true
            ConditionalSaveStatus.RETRYABLE_FAILURE -> {
                expectedRevision = result.revision
                val delays = longArrayOf(15_000, 60_000, 5 * 60_000, 15 * 60_000)
                retryAt = now() + delays[minOf(failures, delays.lastIndex)]
                failures++
            }
        }
        return result.status
    }

    companion object { private const val MAX_WRITE_ATTEMPTS = 5 }
}
