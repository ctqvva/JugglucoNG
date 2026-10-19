package tk.glucodata

import android.app.Application

/**
 * Supplies the process-wide glucose-alarm handler (direction.md §4, category S).
 *
 * This pair is not a call seam, it is a held reference, and that shapes it. Shared code
 * never called `GlucoseAlarms`; it held one, in a public static typed as the concrete class,
 * and read it from `LossOfSensorAlarm`, `Settings`, `SensorBluetooth` and
 * `AlertRuntimeManager` in `src/main`, from `PhotoScan` in both `src/mobileSi` and
 * `src/wearSi`, and from `SibionicsSetupWizard` on the phone. Both construction sites were
 * in shared code too.
 *
 * So the field is typed to [GlucoseAlarmHandler] (below), which [SuperGlucoseAlarms] in
 * `src/main` implements, and only the *instance* has to come from the flavour -- which is
 * what this registry is for, the same shape as `HealthPermissionsAccess` for LaunchShit.
 *
 * The instance is created lazily at two call sites and reused for the process lifetime, so
 * a factory rather than a ready-made instance: the phone registers
 * `MobileGlucoseAlarms::new` and the watch `WearGlucoseAlarms::new`.
 *
 * Nothing is caught on the phone, per the ruling on #465. If nothing is registered, [create]
 * returns null and the field stays null, which every existing call site already handles --
 * two of them check it explicitly and `AlertRuntimeManager` uses `?.`.
 */
/**
 * What shared code calls on the handler it holds.
 *
 * Deliberately not [SuperGlucoseAlarms]: `handlealarm()` is the flavour override, so the
 * base class does not declare it, and typing the field to the base loses the one method
 * `LossOfSensorAlarm` actually needs. The base implements this instead, so the two methods
 * it already had need no moving and the third is the override.
 */
interface GlucoseAlarmHandler {
    /** Re-arms the alarm, notifies, and schedules the next tick. Overridden per flavour. */
    fun handlealarm()

    /** Arms a loss alarm, if one is configured. */
    fun setLossAlarm()

    /** Arms the reading-age alarm. */
    fun setagealarm(numsec: Long, showtime: Long)
}

object GlucoseAlarmsAccess {
    fun interface Factory {
        fun create(application: Application): GlucoseAlarmHandler
    }

    @Volatile
    private var factory: Factory? = null

    @JvmStatic
    fun register(factory: Factory) {
        this.factory = factory
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = factory != null

    /**
     * The handler for this process, or null when the flavour registered none. Called from
     * the two shared sites that used to construct the concrete class; registration has
     * happened by then, in `Applic.onCreate` and again in `Specific.start()`.
     */
    @JvmStatic
    fun create(application: Application): GlucoseAlarmHandler? = factory?.create(application)
}
