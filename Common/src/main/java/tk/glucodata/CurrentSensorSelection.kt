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

    /**
     * Called with the sensor whose reading was just accepted as main. With no selection at all,
     * that sensor becomes the selection. Otherwise this is [syncNativeToManaged].
     *
     * Removing the main sensor clears both slots, and re-adding it never selects it again: the
     * add path asks [Natives.lastsensorname], which names native `last()` when nothing is
     * selected, and the re-added sensor stays finished until its first reading, so the resolver
     * could not pick it yet anyway. Native consumers (web server, watch value) then fall back to
     * `last()`, an unrelated old sensor, for good. A live main reading is the first point where
     * the right sensor is known. An explicit selection is never replaced here.
     *
     * Returns true when the sensor was adopted.
     */
    @JvmStatic
    fun followMainReading(sensorId: String?): Boolean = followMainReading(sensorId, NativeBackend)

    internal fun followMainReading(sensorId: String?, backend: Backend): Boolean = synchronized(lock) {
        if (sensorId.isNullOrBlank()) return false
        if (ManagedCurrentSensor.get() == null && backend.selectedNative() == null) {
            select(sensorId, backend)
            return true
        }
        syncNativeToManaged(sensorId, backend)
        return false
    }

    internal fun syncNativeToManaged(mainSensorId: String?, backend: Backend): Unit = synchronized(lock) {
        val managed = ManagedCurrentSensor.get() ?: return
        if (mainSensorId.isNullOrBlank() || !SensorIdentity.matches(mainSensorId, managed)) return
        val current = backend.selectedNative()
        if (current != null && SensorIdentity.matches(current, managed)) return
        val mirror = backend.mirrorName(managed) ?: return
        backend.setNative(mirror)
    }
}
