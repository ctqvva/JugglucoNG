package tk.glucodata.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import tk.glucodata.HealthConnection
import tk.glucodata.MainActivity
import tk.glucodata.Natives
import tk.glucodata.OutboundApiSettings
import tk.glucodata.SensorBluetooth
import tk.glucodata.alerts.SensorHandoverRuntime

/**
 * Native and preference reads behind the "Apps on this phone" screen, kept out of the
 * composable file (see ArchitectureGateTests).
 */
internal object PhoneAppsSettings {
    val healthConnectSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun healthConnectEnabled(): Boolean = healthConnectSupported && Natives.gethealthConnect()

    fun setHealthConnectEnabled(activity: Activity?, enabled: Boolean) {
        Natives.sethealthConnect(enabled)
        if (enabled) {
            MainActivity.tryHealth = 5
            (activity as? MainActivity)?.let(HealthConnection::init)
        } else {
            MainActivity.tryHealth = 0
            HealthConnection.stop()
        }
    }

    /** Consumers of the exchange payload that live outside the phone-apps screen. */
    fun hasOtherTrendConsumer(): Boolean =
        OutboundApiSettings.isEnabled() || Natives.getgadgetbridge() || Natives.getwatchdrip()

    /**
     * Only the xDrip-style broadcast carries the source name. Stays visible while enabled,
     * so turning the broadcast off does not strand the claim out of reach.
     */
    fun showsLibre2Identity(xdripEnabled: Boolean, reportAsLibre2: Boolean): Boolean =
        xdripEnabled || reportAsLibre2

    /**
     * The computed trend only ever reaches an ExchangeGlucosePayload consumer, so the row is
     * noise until one of them is on. Stays visible while the setting itself is enabled,
     * otherwise turning the last consumer off would strand it out of reach.
     */
    fun showsAppTrend(
        xdripEnabled: Boolean,
        patchedLibreEnabled: Boolean,
        glucodataEnabled: Boolean,
        computedTrend: Boolean,
        otherConsumer: Boolean = hasOtherTrendConsumer(),
    ): Boolean =
        computedTrend || xdripEnabled || patchedLibreEnabled || glucodataEnabled || otherConsumer
}

/** Native and preference state behind the "Connection and handover" screen. */
internal object SensorConnectionSettings {
    private fun prefs(context: Context) =
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    fun handoverEnabled(context: Context): Boolean =
        prefs(context).getBoolean(SensorHandoverRuntime.PREF_ENABLED, false)

    fun setHandoverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SensorHandoverRuntime.PREF_ENABLED, enabled).apply()
    }

    fun handoverOldAction(context: Context): Int =
        prefs(context).getInt(SensorHandoverRuntime.PREF_OLD_ACTION, SensorHandoverRuntime.OLD_ACTION_DEACTIVATE)

    fun setHandoverOldAction(context: Context, action: Int) {
        prefs(context).edit().putInt(SensorHandoverRuntime.PREF_OLD_ACTION, action).apply()
    }

    var highPriority: Boolean
        get() = Natives.getpriority()
        set(value) = Natives.setpriority(value)

    var autoConnect: Boolean
        get() = Natives.getAndroid13()
        set(value) = SensorBluetooth.setAutoconnect(value)
}
