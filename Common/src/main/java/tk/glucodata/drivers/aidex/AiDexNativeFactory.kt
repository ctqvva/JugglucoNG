// JugglucoNG — AiDex Native Kotlin Driver
// AiDexNativeFactory.kt — Factory bridge for Java ↔ Kotlin `native` package
//
// Java cannot import from a package named `native` (reserved keyword).
// This factory lives in tk.glucodata.drivers.aidex (Java-accessible) and
// delegates to classes in tk.glucodata.drivers.aidex.native.ble (Kotlin-only).
//
// SensorBluetooth.java uses this to create AiDexBleManager without importing
// from a Java-reserved package name.

package tk.glucodata.drivers.aidex

import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.aidex.native.ble.AiDexBleManager
import tk.glucodata.drivers.aidex.native.protocol.AiDexDpCatalogProvider
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry

/**
 * Factory bridge that SensorBluetooth.java can call without importing from
 * the `native` package (which Java cannot do).
 *
 * UI code uses `instanceof AiDexDriver` while these helpers remain for:
 * - Creating native driver instances (createBleManager)
 * - Specifically identifying the native driver (isNativeAiDex)
 *
 * Usage from Java:
 *   AiDexNativeFactory.INSTANCE.createBleManager(serial, dataptr)
 *   AiDexNativeFactory.INSTANCE.isNativeAiDex(callback)
 */
object AiDexNativeFactory {

    private const val TAG = "AiDexNativeFactory"

    /**
     * Create an [AiDexBleManager] (native Kotlin driver) as a [SuperGattCallback].
     *
     * The sole AiDex driver implementation after removal of the vendor-library driver.
     * The sensorGen is set to 0 (conventional value for AiDex).
     *
     * @param serial Sensor serial number (with or without "X-" prefix)
     * @param dataptr Native data pointer from Natives.getdataptr(serial)
     * @return A SuperGattCallback backed by the native Kotlin driver
     */
    @JvmStatic
    fun createBleManager(serial: String, dataptr: Long): SuperGattCallback {
        Log.i(TAG, "Creating native AiDexBleManager for $serial (dataptr=$dataptr)")
        return AiDexBleManager(serial, dataptr, 0)
    }

    /**
     * Install already-decoded, sensor-specific pairing material for subsequent AiDex key
     * exchanges in this process.
     *
     * This is intentionally not a server client: the vendor response contains encrypted fields
     * and the authenticated response contract has not yet been established. Callers must supply
     * exactly two decoded 16-byte values. Their contents are never logged.
     */
    @JvmStatic
    fun installProvisionedPairingMaterial(
        serial: String,
        secret: ByteArray,
        iv: ByteArray,
    ): Boolean = AiDexPairingMaterialRegistry.install(serial, secret, iv)

    /** Remove process-local provisioned pairing material for a sensor. */
    @JvmStatic
    fun clearProvisionedPairingMaterial(serial: String) {
        AiDexPairingMaterialRegistry.remove(serial)
    }

    /**
     * Check whether a [SuperGattCallback] is an instance of our native [AiDexBleManager].
     *
     * Java code can use `instanceof AiDexDriver` for the shared contract; this
     * method specifically identifies the concrete native manager.
     */
    @JvmStatic
    fun isNativeAiDex(callback: SuperGattCallback?): Boolean {
        return callback is AiDexBleManager
    }

    /**
     * Check whether a native AiDex callback is in broadcast-only mode.
     *
     * Prefer using `(callback as? AiDexDriver)?.broadcastOnlyConnection` when possible.
     * This method remains for Java code that needs a static helper.
     *
     * Returns false if the callback is not an AiDexBleManager.
     */
    @JvmStatic
    fun isBroadcastOnly(callback: SuperGattCallback?): Boolean {
        val mgr = callback as? AiDexBleManager ?: return false
        return mgr.broadcastOnlyConnection
    }

    /**
     * Import one or more official OTA/default-param rows into the local overlay
     * catalog. Accepts a single JSON object, an array, or an envelope that
     * contains such objects recursively.
     */
    @JvmStatic
    fun importDefaultParamCatalogJson(json: String): String {
        return try {
            AiDexDpCatalogProvider.importCatalogJson(json).summaryLine()
        } catch (t: Throwable) {
            "import-failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Clear any imported OTA/default-param overlay rows, falling back to the
     * baked snapshot catalog only.
     */
    @JvmStatic
    fun clearImportedDefaultParamCatalog(): String {
        return AiDexDpCatalogProvider.clearImportedCatalog().summaryLine()
    }

    /**
     * Return a short catalog summary for diagnostics/logging.
     */
    @JvmStatic
    fun defaultParamCatalogSummary(): String {
        return AiDexDpCatalogProvider.catalogState().summaryLine()
    }

    /**
     * Return the last native `0x31` compare summary for a specific AiDex manager,
     * or null if the callback is not native or no probe has completed yet.
     */
    @JvmStatic
    fun getDefaultParamDiagnostics(callback: SuperGattCallback?): String? {
        val mgr = callback as? AiDexBleManager ?: return null
        return mgr.getLastDefaultParamDiagnosticsSummary()
    }
}
