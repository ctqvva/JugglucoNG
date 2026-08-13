package tk.glucodata

import android.content.Intent

/**
 * The exchange outputs that are a plain on/off, and can therefore be flipped
 * from the watch without carrying the phone's configuration screens across.
 *
 * Most of these are recipient lists rather than booleans: "on" discovers the
 * apps that listen for the broadcast and stores them, "off" clears the list.
 * Nothing the user typed is lost either way — the list is derived from what is
 * installed — so they belong here after all.
 *
 * Still phone-only: Nightscout and the outbound API, which are enabled by a URL
 * and a destination list. Turning those off from a watch would mean discarding
 * something the user actually entered.
 */
object ExchangeToggles {

    private const val LOG_ID = "ExchangeToggles"

    /** Stable ids; they travel on the wire, so they must not be renamed. */
    const val ID_LIBREVIEW = "libreview"
    const val ID_PATCHED_LIBRE = "patched_libre"
    const val ID_XDRIP_BROADCAST = "xdrip_broadcast"
    const val ID_GLUCODATA = "glucodata"
    const val ID_EVERSENSE = "eversense"
    const val ID_GADGETBRIDGE = "gadgetbridge"
    const val ID_WATCHDRIP = "watchdrip"
    const val ID_XDRIP_WEBSERVER = "xdrip_webserver"
    const val ID_SEPARATE = "separate"

    class Toggle(
        val id: String,
        val labelResId: Int,
        private val getter: () -> Boolean,
        private val setter: (Boolean) -> Unit,
    ) {
        fun isEnabled(): Boolean = runCatching(getter).getOrDefault(false)

        fun setEnabled(on: Boolean) {
            runCatching { setter(on) }.onFailure { Log.stack(LOG_ID, "set $id", it) }
        }
    }

    /**
     * Every package that listens for [action], as the phone's own switches
     * resolve them. An empty array is how each of these broadcasts is turned
     * off natively.
     */
    private fun listenersFor(action: String?): Array<String> {
        val target = action ?: return emptyArray()
        val context = Applic.app ?: return emptyArray()
        return runCatching {
            context.packageManager
                .queryBroadcastReceivers(Intent(target), 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .toTypedArray()
        }.getOrDefault(emptyArray())
    }

    private fun broadcastToggle(
        id: String,
        labelResId: Int,
        action: String?,
        getter: () -> Boolean,
        setRecipients: (Array<String>) -> Unit,
        refresh: () -> Unit,
    ) = Toggle(
        id,
        labelResId,
        getter,
        { on ->
            setRecipients(if (on) listenersFor(action) else emptyArray())
            refresh()
        },
    )

    @JvmStatic
    val all: List<Toggle> by lazy {
        listOf(
            broadcastToggle(
                ID_PATCHED_LIBRE,
                R.string.exchange_toggle_patched_libre,
                "com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING",
                { Natives.getlibrelinkused() },
                { Natives.setlibrelinkRecepters(it) },
                { XInfuus.setlibrenames() },
            ),
            broadcastToggle(
                ID_XDRIP_BROADCAST,
                R.string.exchange_toggle_xdrip_broadcast,
                "com.eveningoutpost.dexdrip.BgEstimate",
                { Natives.getxbroadcast() },
                { Natives.setxdripRecepters(it) },
                { SendLikexDrip.setreceivers() },
            ),
            broadcastToggle(
                ID_GLUCODATA,
                R.string.exchange_toggle_glucodata,
                "glucodata.Minute",
                { Natives.getJugglucobroadcast() },
                { Natives.setglucodataRecepters(it) },
                { JugglucoSend.setreceivers() },
            ),
            broadcastToggle(
                ID_EVERSENSE,
                R.string.exchange_toggle_eversense,
                EverSense.glucoseaction,
                { Natives.geteverSensebroadcast() },
                { Natives.seteverSenseRecepters(it) },
                { EverSense.setreceivers() },
            ),
            Toggle(
                ID_GADGETBRIDGE,
                R.string.exchange_toggle_gadgetbridge,
                { Natives.getgadgetbridge() },
                {
                    Natives.setgadgetbridge(it)
                    // The dispatch path reads the static, not native, so both
                    // have to move or the switch appears to do nothing.
                    SuperGattCallback.doGadgetbridge = it
                },
            ),
            Toggle(
                ID_WATCHDRIP,
                R.string.exchange_toggle_watchdrip,
                { Natives.getwatchdrip() },
                {
                    Natives.setwatchdrip(it)
                    SuperGattCallback.doWearInt = it
                },
            ),
            Toggle(
                ID_LIBREVIEW,
                R.string.exchange_toggle_libreview,
                { Natives.getuselibreview() },
                { Natives.setuselibreview(it) },
            ),
            Toggle(
                ID_XDRIP_WEBSERVER,
                R.string.exchange_toggle_xdrip_webserver,
                { Natives.getusexdripwebserver() },
                { Natives.setusexdripwebserver(it) },
            ),
            Toggle(
                ID_SEPARATE,
                R.string.exchange_toggle_separate,
                { Natives.getSeparate() },
                {
                    Natives.setSeparate(it)
                    Notify.alertseparate = it
                },
            ),
        )
    }

    @JvmStatic
    fun byId(id: String?): Toggle? = all.firstOrNull { it.id == id }
}
