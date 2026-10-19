package tk.glucodata.drivers.ottai

/** Entry path after GATT discovery for an Ottai sensor. */
internal enum class OttaiAuthEntryMode {
    STORED_MATERIAL_AUTH,
    V3_CREDENTIAL_BOOTSTRAP,
    BLOCKED,
}

/**
 * A fresh V3 sensor has no local keyA yet. It may enter the server-mediated bootstrap only after
 * the user explicitly requested it, the cloud validated this exact sensor/version, and a session
 * that can sign cgmAuth ([OttaiCloudClient.supportsActiveAuth]) is still available. Everything
 * else is rejected without touching the sensor's authentication characteristics.
 */
internal fun ottaiAuthEntryMode(
    hasAuthKeys: Boolean,
    bootstrapPending: Boolean,
    activeAuthSessionAvailable: Boolean,
    validatedDeviceVersion: String?,
): OttaiAuthEntryMode = when {
    hasAuthKeys -> OttaiAuthEntryMode.STORED_MATERIAL_AUTH
    bootstrapPending && activeAuthSessionAvailable && !validatedDeviceVersion.isNullOrBlank() ->
        OttaiAuthEntryMode.V3_CREDENTIAL_BOOTSTRAP
    else -> OttaiAuthEntryMode.BLOCKED
}
