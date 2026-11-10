// MedtrumLinkPolicy.kt — reconnect route and auth dialect decisions, kept free of Android types
// so the choices the driver makes after a lost link can be unit-tested.

package tk.glucodata.drivers.medtrum

/**
 * How the next connection attempt reaches the patch.
 *
 * DIRECT connects to the last known address, which works with the screen off. SCAN looks the
 * patch up by the serial in its advert; it is the only way to follow an address the patch has
 * rotated, but Android delivers no unfiltered scan results while the screen is off, so it is
 * never the only route for long.
 */
class MedtrumReconnectPolicy(private val maxDirectFailures: Int = DEFAULT_MAX_DIRECT_FAILURES) {

    enum class Route { DIRECT, SCAN }

    companion object {
        const val DEFAULT_MAX_DIRECT_FAILURES = 3
    }

    var route: Route = Route.DIRECT
        private set

    private var directFailures = 0

    /** The link came up: the address in use is the right one. */
    fun onConnected() {
        directFailures = 0
        route = Route.DIRECT
    }

    /** A link that had come up went away (range, idle drop, watchdog). The address still holds. */
    fun onLinkLost(): Route {
        directFailures = 0
        route = Route.DIRECT
        return route
    }

    /**
     * An attempt ended without the link coming up. Repeated direct failures fall back to one scan
     * window; a scan window that found nothing goes back to the known address.
     */
    fun onAttemptFailed(): Route {
        if (route == Route.SCAN) {
            route = Route.DIRECT
            directFailures = 0
            return route
        }
        directFailures++
        if (directFailures >= maxDirectFailures) {
            route = Route.SCAN
            directFailures = 0
        }
        return route
    }
}

/**
 * The auth dialect for one link. The vendor app picks it from the advert's firmware byte, which
 * the scan seam does not expose, so the driver starts from the dialect the patch last accepted and
 * tries the other one once per link when it is rejected. Only an accepted dialect is remembered:
 * a single unlucky rejection must not pin every later session to the wrong key.
 */
class MedtrumAuthSession(storedLegacy: Boolean) {

    var legacy: Boolean = storedLegacy
        private set

    private var switched = false

    /** True when the caller should retry once with the other dialect on this link. */
    fun onRejected(): Boolean {
        if (switched) return false
        switched = true
        legacy = !legacy
        return true
    }
}
