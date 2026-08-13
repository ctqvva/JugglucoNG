package tk.glucodata

/**
 * The exchange outputs that are a plain on/off, and can therefore be flipped
 * from the watch without carrying the phone's configuration screens across.
 *
 * Not every output belongs here. Nightscout is enabled by having a URL, the
 * outbound API by having an active destination, and the Juggluco, patched-Libre
 * and EverSense broadcasts by having recipients — for those "off" means
 * discarding configuration, which is not something a switch on a watch should
 * do silently. They stay phone-only, and the watch says so rather than showing
 * a switch that would destroy settings.
 */
object ExchangeToggles {

    /** Stable ids; they travel on the wire, so they must not be renamed. */
    const val ID_LIBREVIEW = "libreview"
    const val ID_XDRIP_BROADCAST = "xdrip_broadcast"
    const val ID_GADGETBRIDGE = "gadgetbridge"
    const val ID_WATCHDRIP = "watchdrip"
    const val ID_XDRIP_WEBSERVER = "xdrip_webserver"

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

    private const val LOG_ID = "ExchangeToggles"

    @JvmStatic
    val all: List<Toggle> by lazy {
        listOf(
            Toggle(
                ID_LIBREVIEW,
                R.string.exchange_toggle_libreview,
                { Natives.getuselibreview() },
                { Natives.setuselibreview(it) },
            ),
            Toggle(
                ID_XDRIP_BROADCAST,
                R.string.exchange_toggle_xdrip_broadcast,
                { Natives.getxbroadcast() },
                { Natives.setxbroadcast(it) },
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
                ID_XDRIP_WEBSERVER,
                R.string.exchange_toggle_xdrip_webserver,
                { Natives.getusexdripwebserver() },
                { Natives.setusexdripwebserver(it) },
            ),
        )
    }

    @JvmStatic
    fun byId(id: String?): Toggle? = all.firstOrNull { it.id == id }
}
