package tk.glucodata

/**
 * Writes the main-sensor selection: the managed slot ([ManagedCurrentSensor]) and native
 * `current`, which the web server, the watch value and native history follow.
 *
 * [select] and [syncNativeToManaged] share one lock. A sync reads the managed id and then
 * writes native current; without the lock a user selection landing between those two steps
 * was overwritten by the sync's stale target.
 */
object CurrentSensorSelection {
    internal interface Backend {
        fun hasNativeBacking(sensorId: String): Boolean
        fun nativeName(sensorId: String): String?
        fun mirrorName(sensorId: String): String?
        /** Native current with no fallback; [Natives.lastsensorname] falls back to the last sensor. */
        fun selectedNative(): String?
        fun setNative(name: String)
    }

    private object NativeBackend : Backend {
        override fun hasNativeBacking(sensorId: String) = SensorIdentity.hasNativeSensorBacking(sensorId)
        override fun nativeName(sensorId: String): String? = SensorIdentity.resolveNativeSensorName(sensorId)
        override fun mirrorName(sensorId: String): String? = SensorIdentity.nativeMirrorSensorName(sensorId)
        override fun selectedNative(): String? = runCatching { Natives.currentsensorname() }.getOrNull()
        override fun setNative(name: String) = Natives.setcurrentsensor(name)
    }

    private val lock = Any()

    @JvmStatic
    fun select(serial: String?): Unit = select(serial, NativeBackend)

    internal fun select(serial: String?, backend: Backend): Unit = synchronized(lock) {
        if (serial.isNullOrEmpty()) {
            ManagedCurrentSensor.clear()
            backend.setNative("")
            return
        }
        if (backend.hasNativeBacking(serial)) {
            // The managed-current slot is only for sensors that cannot be
            // represented by native lastsensorname. Selecting any native-backed
            // sensor must clear a previous managed primary, otherwise
            // SensorIdentity.resolveMainSensor() keeps returning that stale
            // managed id ahead of the native current sensor.
            ManagedCurrentSensor.clear()
            backend.setNative(backend.nativeName(serial)?.takeIf { it.isNotEmpty() } ?: serial)
            return
        }
        ManagedCurrentSensor.set(serial)
        backend.mirrorName(serial)?.let {
            backend.setNative(it)
            return
        }
        val current = backend.selectedNative()
        if (!current.isNullOrEmpty() && !backend.hasNativeBacking(current)) {
            backend.setNative("")
        }
    }

    /**
     * Point native current at the managed main sensor's native mirror, once that mirror
     * exists. Selecting a managed sensor before its first reading, or an install that picked
     * it before [select] did this, otherwise leaves native consumers on the old sensor.
     *
     * [mainSensorId] is the sensor actually resolved as main: a stale managed id that lost
     * the main role (not active any more) must not drag native onto its old mirror.
     */
    @JvmStatic
    fun syncNativeToManaged(mainSensorId: String?): Unit = syncNativeToManaged(mainSensorId, NativeBackend)

    internal fun syncNativeToManaged(mainSensorId: String?, backend: Backend): Unit = synchronized(lock) {
        val managed = ManagedCurrentSensor.get() ?: return
        if (mainSensorId.isNullOrBlank() || !SensorIdentity.matches(mainSensorId, managed)) return
        val current = backend.selectedNative()
        if (current != null && SensorIdentity.matches(current, managed)) return
        val mirror = backend.mirrorName(managed) ?: return
        backend.setNative(mirror)
    }
}
