package tk.glucodata

import android.content.Context

/**
 * The phone's numbers / ConnectIQ hub (direction.md §4 category P, and the ruling on
 * #465: an interface on the field).
 *
 * `nums.AllData` is 1114 lines in `src/mobile` and shared code held it as
 * `Applic.numdata` -- a public field, read from `NumberView`, `Notify`, `SensorBluetooth`,
 * `SuperGattCallback` and `MainActivity`. A registry does not remove a held reference, so
 * the field is typed as this instead, the phone's class implements it, and `Specific`
 * supplies the instance.
 *
 * The method names are the phone class's own, lower case and all. Renaming nine methods in
 * a file that size would be churn in a change whose subject is the seam, and the interface
 * exists to describe that class's surface rather than to rename it.
 *
 * `devices` used to be read directly from shared code (`numdata.devices == null`), which is
 * a field crossing the seam; it is [hasGarminDevices] now.
 */
interface NumberDataHub {
    fun sendglucose(ident: String, tim: Long, glu: Float, rate: Float, off: Int)
    fun startall()
    fun stopalarm()
    fun sendlabels()
    fun sendmessages()
    fun onCleared()
    fun initIQ(context: Context)
    fun changedback(base: Int)
    fun deletelast(base: Int, pos: Int, end: Int)

    /**
     * True once the hub has a ConnectIQ device, which is what decides whether it needs
     * `initIQ`. The watch's copy of the class was a `devices` field nothing ever filled, so
     * the answer there was always no.
     */
    fun hasGarminDevices(): Boolean
}

object NumberDataAccess {
    /**
     * The no-op a flavour without the hub gets. Explicit rather than default interface
     * methods, so a method the phone's class forgets to implement fails to compile instead
     * of quietly doing nothing.
     */
    private val none = object : NumberDataHub {
        override fun sendglucose(ident: String, tim: Long, glu: Float, rate: Float, off: Int) {}
        override fun startall() {}
        override fun stopalarm() {}
        override fun sendlabels() {}
        override fun sendmessages() {}
        override fun onCleared() {}
        override fun initIQ(context: Context) {}
        override fun changedback(base: Int) {}
        override fun deletelast(base: Int, pos: Int, end: Int) {}
        override fun hasGarminDevices(): Boolean = false
    }

    @Volatile
    private var hub: NumberDataHub? = null

    @JvmStatic
    fun register(hub: NumberDataHub) {
        this.hub = hub
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = hub != null

    /**
     * The registered hub, or the no-op. Never null: shared code holds this in a field and
     * reads it from about a dozen places on both flavours, and the watch's copy was all
     * no-ops, so the no-op is what the watch gets by default. Absence stays a distinct,
     * testable state: [isRegistered] is false there.
     */
    @JvmStatic
    fun get(): NumberDataHub = hub ?: none
}
