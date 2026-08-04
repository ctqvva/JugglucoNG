package tk.glucodata.drivers.aidex.native.protocol

import android.content.Context
import android.util.Log

/**
 * Redundant app-private storage for the stable AiDex PAIR credential.
 *
 * The two copies live in separate SharedPreferences files and are committed synchronously.
 * A conflicting pair is never guessed or overwritten automatically. Android backup is disabled
 * for this app, so cross-device recovery is deliberately handled by [AiDexPairKeyBackup].
 */
object AiDexPairKeyVault {
    private const val TAG = "AiDexPairKeyVault"
    private const val PRIMARY_PREFS = "AiDexPairKeysPrimary"
    private const val RECOVERY_PREFS = "AiDexPairKeysRecovery"
    private const val LEGACY_PREFS = "AiDexNativePrefs"
    private const val ENTRY_PREFIX = "pairKey_v1_"

    enum class ImportResult {
        SAVED,
        ALREADY_PRESENT,
        CONFLICT,
        INVALID,
    }

    @Synchronized
    fun load(context: Context, serial: String): ByteArray? {
        val bareSerial = AiDexPairKeyBackup.canonicalBareSerial(serial)
        val copies = readCopies(context, bareSerial)
        if (copies.isEmpty()) return migrateLegacy(context, serial, bareSerial)
        val distinct = copies.distinctBy { it.toList() }
        if (distinct.size != 1) {
            Log.e(TAG, "Conflicting stored AiDex PAIR credentials for $bareSerial; refusing automatic selection")
            return null
        }
        return distinct.single().copyOf()
    }

    /** Save only when no different credential is already present. */
    @Synchronized
    fun saveIfAbsent(context: Context, serial: String, pairKey: ByteArray): Boolean {
        if (pairKey.size != AiDexPairKeyBackup.PAIR_KEY_BYTES) return false
        val bareSerial = AiDexPairKeyBackup.canonicalBareSerial(serial)
        val existingCopies = readCopies(context, bareSerial)
        if (existingCopies.any { !it.contentEquals(pairKey) }) {
            Log.e(TAG, "Refusing to overwrite a different stored AiDex PAIR credential for $bareSerial")
            return false
        }
        if (existingCopies.size == 2) return true

        val payload = AiDexPairKeyBackup.encode(bareSerial, pairKey) ?: return false
        val key = entryKey(bareSerial)
        val primarySaved = context.getSharedPreferences(PRIMARY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, payload).commit()
        val recoverySaved = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, payload).commit()
        if (!primarySaved || !recoverySaved) {
            Log.e(TAG, "AiDex PAIR credential persistence was only partially committed for $bareSerial")
        }
        return primarySaved || recoverySaved
    }

    @Synchronized
    fun exportPayload(context: Context, serial: String): String? {
        val pairKey = load(context, serial) ?: return null
        return AiDexPairKeyBackup.encode(serial, pairKey)
    }

    @Synchronized
    fun importPayload(context: Context, payload: String): ImportResult {
        val record = AiDexPairKeyBackup.decode(payload) ?: return ImportResult.INVALID
        val existingCopies = readCopies(context, record.bareSerial)
        if (existingCopies.any { !it.contentEquals(record.pairKey) }) return ImportResult.CONFLICT
        if (existingCopies.isNotEmpty() && existingCopies.all { it.contentEquals(record.pairKey) }) {
            saveIfAbsent(context, record.bareSerial, record.pairKey)
            return ImportResult.ALREADY_PRESENT
        }
        return if (saveIfAbsent(context, record.bareSerial, record.pairKey)) {
            ImportResult.SAVED
        } else {
            ImportResult.CONFLICT
        }
    }

    /** Clear only after a successful sensor-side DELETE_BOND acknowledgement. */
    @Synchronized
    fun clearAfterConfirmedUnpair(context: Context, serial: String): Boolean {
        val bareSerial = AiDexPairKeyBackup.canonicalBareSerial(serial)
        val key = entryKey(bareSerial)
        val primaryCleared = context.getSharedPreferences(PRIMARY_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).commit()
        val recoveryCleared = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).commit()

        val legacyEditor = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
        legacyKeys(serial, bareSerial).forEach(legacyEditor::remove)
        val legacyCleared = legacyEditor.commit()
        return primaryCleared && recoveryCleared && legacyCleared
    }

    private fun readCopies(context: Context, bareSerial: String): List<ByteArray> {
        val key = entryKey(bareSerial)
        return listOf(PRIMARY_PREFS, RECOVERY_PREFS).mapNotNull { prefsName ->
            val payload = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString(key, null) ?: return@mapNotNull null
            val record = AiDexPairKeyBackup.decode(payload) ?: run {
                Log.e(TAG, "Ignoring corrupt AiDex PAIR credential copy in $prefsName for $bareSerial")
                return@mapNotNull null
            }
            record.pairKey.takeIf { record.bareSerial == bareSerial }?.copyOf()
        }
    }

    private fun migrateLegacy(context: Context, serial: String, bareSerial: String): ByteArray? {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val candidates = legacyKeys(serial, bareSerial).mapNotNull { key ->
            prefs.getString(key, null)?.let(AiDexPairKeyBackup::decodePairKeyHex)
        }.distinctBy { it.toList() }
        if (candidates.size != 1) {
            if (candidates.size > 1) {
                Log.e(TAG, "Conflicting legacy AiDex PAIR credentials for $bareSerial; refusing migration")
            }
            return null
        }
        val pairKey = candidates.single()
        if (!saveIfAbsent(context, bareSerial, pairKey)) return null
        Log.i(TAG, "Migrated legacy AiDex PAIR credential for $bareSerial into redundant storage")
        return pairKey.copyOf()
    }

    private fun legacyKeys(serial: String, bareSerial: String): Set<String> = setOf(
        "pairKey_$serial",
        "pairKey_$bareSerial",
        "pairKey_X-$bareSerial",
    )

    private fun entryKey(bareSerial: String): String = "$ENTRY_PREFIX$bareSerial"
}
