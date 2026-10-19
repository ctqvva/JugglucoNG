package tk.glucodata.drivers.mq

internal object MQNativeSensorRemoval {
    fun removeAndConfirm(
        sensorId: String,
        nativeName: String,
        remove: (String) -> Boolean,
        activeSensors: () -> Array<String>?,
    ): Boolean {
        if (!remove(nativeName)) return false
        val active = activeSensors() ?: return false
        return active.none { MQConstants.matchesCanonicalOrKnownNativeAlias(sensorId, it) }
    }
}
