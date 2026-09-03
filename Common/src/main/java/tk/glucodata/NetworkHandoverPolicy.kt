package tk.glucodata

/** Keeps a replacement Android network from being mistaken for total connectivity loss. */
object NetworkHandoverPolicy {
    @JvmStatic
    fun hasUsableReplacement(remainingInternetNetworks: Int, hasIpAddress: Boolean): Boolean =
        remainingInternetNetworks > 0 && hasIpAddress
}
