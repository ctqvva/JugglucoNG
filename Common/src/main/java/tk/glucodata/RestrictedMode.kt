package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a restricted phone may still do. Everything not listed here — settings, data
 * clearing, sensor resets, advanced sensor controls — is closed while restricted.
 *
 * [allowedSensorTypes] holds sensor family names as the phone's sensor picker spells them
 * (`SensorType.name`); shared code does not know that enum.
 */
data class RestrictedModeConfig(
    val enabled: Boolean = false,
    val allowSensorChange: Boolean = false,
    val allowedSensorTypes: Set<String> = emptySet(),
    val allowJournal: Boolean = true,
    val allowCalibration: Boolean = false,
    val allowAlarmStop: Boolean = false,
    val hasPin: Boolean = false,
    val biometricUnlock: Boolean = false,
)

/** The configuration plus whether this app session has been unlocked to full mode. */
data class RestrictedModeState(
    val config: RestrictedModeConfig = RestrictedModeConfig(),
    val unlocked: Boolean = false,
) {
    /** Restrictions apply now. */
    val locked: Boolean get() = config.enabled && !unlocked

    val canChangeSensors: Boolean get() = !locked || config.allowSensorChange
    val canEditJournal: Boolean get() = !locked || config.allowJournal
    val canCalibrate: Boolean get() = !locked || config.allowCalibration
    val canStopAlarms: Boolean get() = !locked || config.allowAlarmStop
    val canClearData: Boolean get() = !locked
}

/**
 * Restricted mode: a simplified app for a child, or anyone who should follow their glucose
 * without reaching the settings. An adult switches to full mode for the session, with the
 * PIN when one is set; the session falls back to restricted [RELOCK_GRACE_MS] after the app
 * leaves the screen, and on every process start.
 *
 * The configuration lives in its own preference file, [PREFS_NAME], which settings export
 * and import leave alone: a backup restored onto a child's phone must not switch the
 * restrictions off, and the PIN hash is not something to pass around in a backup file.
 *
 * Only the phone writes it. The watch never has the file, so every check there reads the
 * defaults (not restricted).
 */
object RestrictedMode {
    const val PREFS_NAME = "restricted_mode"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOW_SENSOR_CHANGE = "allow_sensor_change"
    private const val KEY_ALLOWED_SENSOR_TYPES = "allowed_sensor_types"
    private const val KEY_ALLOW_JOURNAL = "allow_journal"
    private const val KEY_ALLOW_CALIBRATION = "allow_calibration"
    private const val KEY_ALLOW_ALARM_STOP = "allow_alarm_stop"
    private const val KEY_BIOMETRIC = "biometric_unlock"
    private const val KEY_PIN = "pin_hash"
    private const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_ELAPSED = "pin_lockout_until_elapsed_ms"
    private const val KEY_LOCKOUT_DURATION = "pin_lockout_duration_ms"
    private const val KEY_LOCKOUT_BOOT = "pin_lockout_boot"

    /** How long the app may be off screen before full mode ends by itself. */
    const val RELOCK_GRACE_MS = 60_000L

    private val stateFlow = MutableStateFlow(RestrictedModeState())
    private var loaded = false
    /** When the app left the screen, on the elapsed-time clock; 0 while it is on screen. */
    @Volatile private var backgroundedAtElapsed = 0L

    /** Live state for the UI. Reading it loads the stored configuration once. */
    @JvmStatic
    val state: StateFlow<RestrictedModeState>
        get() {
            ensureLoaded()
            expireIfDue()
            return stateFlow.asStateFlow()
        }

    /**
     * The state now. Full mode that has been off screen past the grace ends here, so an
     * alarm receiver or a Quick Settings tile reading this never sees a stale unlock.
     */
    @JvmStatic
    fun current(): RestrictedModeState {
        ensureLoaded()
        expireIfDue()
        return stateFlow.value
    }

    private fun expireIfDue(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        val since = backgroundedAtElapsed
        if (since > 0L && stateFlow.value.unlocked && shouldRelock(since, nowElapsed)) lock()
    }

    /** Restrictions apply right now. */
    @JvmStatic
    fun isLocked(): Boolean = current().locked

    /** The family name the phone's sensor picker uses for Libre (`SensorType.LIBRE`). */
    const val LIBRE_FAMILY = "LIBRE"

    /** Whether a sensor of this family (a `SensorType` name) may be added right now. */
    @JvmStatic
    fun canAddSensorFamily(family: String): Boolean = current().let {
        !it.locked || (it.config.allowSensorChange && family in it.config.allowedSensorTypes)
    }

    /**
     * Whether an NFC tap on a Libre must be refused because it would add a sensor. Libre 3
     * NFC only activates or pairs, so it needs Libre allowed; a Libre 1/2 tap is refused
     * only for a sensor this phone does not have yet, so reading a known one keeps working.
     */
    @JvmStatic
    fun refusesNfcSensor(isLibre3: Boolean, serial: String?): Boolean {
        if (canAddSensorFamily(LIBRE_FAMILY)) return false
        if (isLibre3 || serial.isNullOrBlank()) return true
        val known = runCatching { Natives.activeSensors() }.getOrNull() ?: return true
        return known.none { SensorIdentity.matches(it, serial) }
    }

    /** Whether an alarm may be stopped outright rather than only snoozed. */
    @JvmStatic
    fun allowsAlarmStop(): Boolean = current().canStopAlarms

    fun update(context: Context, transform: (RestrictedModeConfig) -> RestrictedModeConfig) {
        synchronized(this) {
            ensureLoaded(context)
            val before = stateFlow.value.config
            val after = transform(before).copy(hasPin = before.hasPin)
            prefs(context).edit()
                .putBoolean(KEY_ENABLED, after.enabled)
                .putBoolean(KEY_ALLOW_SENSOR_CHANGE, after.allowSensorChange)
                .putStringSet(KEY_ALLOWED_SENSOR_TYPES, after.allowedSensorTypes)
                .putBoolean(KEY_ALLOW_JOURNAL, after.allowJournal)
                .putBoolean(KEY_ALLOW_CALIBRATION, after.allowCalibration)
                .putBoolean(KEY_ALLOW_ALARM_STOP, after.allowAlarmStop)
                .putBoolean(KEY_BIOMETRIC, after.biometricUnlock && after.hasPin)
                .apply()
            // Switching restricted mode on from the settings keeps this session in full
            // mode: the adult is still on the settings screen and decides when to lock.
            val unlocked = if (!before.enabled && after.enabled) true else stateFlow.value.unlocked
            stateFlow.value = RestrictedModeState(
                after.copy(biometricUnlock = after.biometricUnlock && after.hasPin),
                unlocked,
            )
        }
    }

    /** Ends restrictions for this app session. */
    fun unlock() {
        synchronized(this) {
            ensureLoaded()
            stateFlow.value = stateFlow.value.copy(unlocked = true)
        }
    }

    /** Returns to restricted mode now. */
    fun lock() {
        synchronized(this) {
            ensureLoaded()
            stateFlow.value = stateFlow.value.copy(unlocked = false)
        }
    }

    fun onAppBackgrounded() {
        backgroundedAtElapsed = SystemClock.elapsedRealtime().coerceAtLeast(1L)
    }

    fun onAppForegrounded() {
        expireIfDue()
        backgroundedAtElapsed = 0L
    }

    /** Both times on the elapsed-time clock, which the phone's time setting cannot move. */
    internal fun shouldRelock(backgroundedAtElapsed: Long, nowElapsed: Long): Boolean =
        nowElapsed - backgroundedAtElapsed >= RELOCK_GRACE_MS || nowElapsed < backgroundedAtElapsed

    // --- PIN ---------------------------------------------------------------------------

    sealed class PinCheck {
        object Accepted : PinCheck()
        /** Wrong PIN; [lockoutMs] is the wait it started, 0 for none. */
        data class Rejected(val lockoutMs: Long) : PinCheck()
        data class LockedOut(val remainingMs: Long) : PinCheck()
    }

    /** Stores a new PIN, or removes it with null. Removing the PIN also drops biometrics. */
    fun setPin(context: Context, pin: String?) {
        synchronized(this) {
            ensureLoaded(context)
            val editor = prefs(context).edit()
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKOUT_UNTIL_ELAPSED)
                .remove(KEY_LOCKOUT_DURATION)
                .remove(KEY_LOCKOUT_BOOT)
            if (pin == null) {
                editor.remove(KEY_PIN).putBoolean(KEY_BIOMETRIC, false)
            } else {
                require(RestrictedModePin.isValid(pin))
                editor.putString(KEY_PIN, RestrictedModePin.encode(pin))
            }
            editor.apply()
            val config = stateFlow.value.config
            stateFlow.value = stateFlow.value.copy(
                config = config.copy(
                    hasPin = pin != null,
                    biometricUnlock = pin != null && config.biometricUnlock,
                )
            )
        }
    }

    /**
     * How long the keypad stays locked after wrong guesses; 0 when it is open. Measured on
     * the elapsed-time clock, so changing the phone's time does not shorten it; after a
     * restart the whole wait is counted again from boot.
     */
    fun pinLockoutRemainingMs(context: Context): Long {
        val prefs = prefs(context)
        return RestrictedModePin.remainingLockoutMs(
            savedBoot = prefs.getInt(KEY_LOCKOUT_BOOT, -1),
            savedUntilElapsed = prefs.getLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L),
            savedDurationMs = prefs.getLong(KEY_LOCKOUT_DURATION, 0L),
            currentBoot = bootCount(context),
            nowElapsed = SystemClock.elapsedRealtime(),
        )
    }

    fun checkPin(context: Context, pin: String): PinCheck {
        synchronized(this) {
            val prefs = prefs(context)
            val remaining = pinLockoutRemainingMs(context)
            if (remaining > 0L) return PinCheck.LockedOut(remaining)
            val stored = prefs.getString(KEY_PIN, null) ?: return PinCheck.Accepted
            if (RestrictedModePin.matches(pin, stored)) {
                prefs.edit()
                    .remove(KEY_FAILED_ATTEMPTS)
                    .remove(KEY_LOCKOUT_UNTIL_ELAPSED)
                    .remove(KEY_LOCKOUT_DURATION)
                    .remove(KEY_LOCKOUT_BOOT)
                    .apply()
                return PinCheck.Accepted
            }
            val failures = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val lockout = RestrictedModePin.lockoutMs(failures)
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, failures)
                .putLong(KEY_LOCKOUT_UNTIL_ELAPSED, if (lockout > 0L) SystemClock.elapsedRealtime() + lockout else 0L)
                .putLong(KEY_LOCKOUT_DURATION, lockout)
                .putInt(KEY_LOCKOUT_BOOT, bootCount(context))
                .apply()
            return PinCheck.Rejected(lockout)
        }
    }

    /** Android's boot counter, so a saved elapsed-time deadline is only trusted on its own boot. */
    private fun bootCount(context: Context): Int = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    // --- storage -----------------------------------------------------------------------

    private fun ensureLoaded(context: Context? = null) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val ctx = context ?: Applic.app ?: return
            val prefs = prefs(ctx)
            val hasPin = prefs.getString(KEY_PIN, null) != null
            stateFlow.value = RestrictedModeState(
                config = RestrictedModeConfig(
                    enabled = prefs.getBoolean(KEY_ENABLED, false),
                    allowSensorChange = prefs.getBoolean(KEY_ALLOW_SENSOR_CHANGE, false),
                    allowedSensorTypes = prefs.getStringSet(KEY_ALLOWED_SENSOR_TYPES, null)
                        ?.toSet().orEmpty(),
                    allowJournal = prefs.getBoolean(KEY_ALLOW_JOURNAL, true),
                    allowCalibration = prefs.getBoolean(KEY_ALLOW_CALIBRATION, false),
                    allowAlarmStop = prefs.getBoolean(KEY_ALLOW_ALARM_STOP, false),
                    hasPin = hasPin,
                    biometricUnlock = hasPin && prefs.getBoolean(KEY_BIOMETRIC, false),
                ),
                unlocked = false,
            )
            loaded = true
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * PIN storage: a salted PBKDF2 hash, never the digits. The PIN only gates a settings screen
 * on the phone it was set on, so the aim is that a backup or a rooted copy of the
 * preference file does not show it in clear text, not resistance to a determined attack on
 * a 4-digit space; the keypad's growing lockout is what slows guessing.
 */
object RestrictedModePin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val VERSION = "p1"

    /** Wrong guesses allowed before the keypad locks. */
    const val FREE_ATTEMPTS = 5

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun encode(pin: String, salt: ByteArray = randomSalt()): String {
        val hash = derive(pin, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return "$VERSION:$ITERATIONS:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}"
    }

    fun matches(pin: String, encoded: String): Boolean {
        val parts = encoded.split(':')
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    /**
     * What is left of a lockout. On the boot it was set, the elapsed-time deadline decides;
     * on any other boot (or when the boot is unknown) the elapsed clock has restarted, so the
     * whole wait is counted from boot again: restarting the phone never shortens it.
     */
    fun remainingLockoutMs(
        savedBoot: Int,
        savedUntilElapsed: Long,
        savedDurationMs: Long,
        currentBoot: Int,
        nowElapsed: Long,
    ): Long {
        if (savedUntilElapsed <= 0L || savedDurationMs <= 0L) return 0L
        val sameBoot = savedBoot >= 0 && savedBoot == currentBoot
        val remaining = if (sameBoot) savedUntilElapsed - nowElapsed else savedDurationMs - nowElapsed
        return remaining.coerceIn(0L, savedDurationMs)
    }

    /**
     * Lockout after [failures] consecutive wrong PINs: none for the first [FREE_ATTEMPTS],
     * then 30 seconds, doubling with each further wrong PIN up to 15 minutes.
     */
    fun lockoutMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = (failures - FREE_ATTEMPTS).coerceAtMost(5)
        return (30_000L shl step).coerceAtMost(15L * 60_000L)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}
