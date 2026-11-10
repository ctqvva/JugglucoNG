package tk.glucodata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object MultiSensorSelection {
    private const val PREFS_NAME = tk.glucodata.settings.SettingsRegistry.FILE
    private const val KEY_SELECTED_ORDER = tk.glucodata.settings.KEY_SENSOR_SELECTION
    const val SEPARATOR = "\n"

    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    private fun prefs() =
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalize(sensorId: String?): String? =
        SensorIdentity.resolveAppSensorId(sensorId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: sensorId?.trim()?.takeIf { it.isNotEmpty() }

    private fun readStored(): List<String> =
        prefs()
            .getString(KEY_SELECTED_ORDER, "")
            .orEmpty()
            .split(SEPARATOR)
            .mapNotNull(::normalize)
            .let(SensorIdentity::distinctLogicalSensorIds)

    private fun writeStored(sensorIds: List<String>) {
        val distinct = SensorIdentity.distinctLogicalSensorIds(sensorIds)
        prefs()
            .edit()
            .putString(KEY_SELECTED_ORDER, distinct.joinToString(SEPARATOR))
            .apply()
        _revision.value = _revision.value + 1L
        // The watch draws the same selection; it learns of a change here, not
        // on its next handshake.
        if (!Applic.isWearable) runCatching { WearPrefsSync.push() }
    }

    /** The stored order was written by something else (the phone's mirror). */
    fun notifyStoredChanged() {
        _revision.value = _revision.value + 1L
    }

    fun selectedOrder(): List<String> = readStored()

    fun setSelectedOrder(sensorIds: List<String>) {
        writeStored(sensorIds)
    }

    fun selectedAvailable(
        availableSensorIds: Iterable<String?>,
        primarySensorId: String?
    ): List<String> {
        val available = SensorIdentity.distinctLogicalSensorIds(availableSensorIds)
        if (available.isEmpty()) {
            return normalize(primarySensorId)?.let(::listOf) ?: emptyList()
        }

        val stored = readStored()
        val selected = stored.filter { selected ->
            available.any { availableId -> SensorIdentity.matches(availableId, selected) }
        }
        if (selected.isNotEmpty()) {
            return selected
        }

        val primary = normalize(primarySensorId)
        val resolvedPrimary = available.firstOrNull { SensorIdentity.matches(it, primary) }
        return listOf(resolvedPrimary ?: available.first())
    }

    fun moveToFront(sensorId: String, availableSensorIds: Iterable<String?> = emptyList()): List<String> {
        val normalized = normalize(sensorId) ?: return selectedOrder()
        val available = SensorIdentity.distinctLogicalSensorIds(availableSensorIds)
        val current = if (available.isEmpty()) {
            readStored()
        } else {
            selectedAvailable(available, primarySensorId = normalized)
        }
        val next = buildList {
            add(normalized)
            current.forEach { sensor ->
                if (!SensorIdentity.matches(sensor, normalized)) add(sensor)
            }
        }
        writeStored(next)
        return next
    }

    fun toggle(
        sensorId: String,
        availableSensorIds: Iterable<String?>,
        primarySensorId: String?
    ): List<String> {
        val normalized = normalize(sensorId) ?: return selectedAvailable(availableSensorIds, primarySensorId)
        val current = selectedAvailable(availableSensorIds, primarySensorId)
        val alreadySelected = current.any { SensorIdentity.matches(it, normalized) }
        val next = when {
            alreadySelected && current.size <= 1 -> current
            alreadySelected -> current.filterNot { SensorIdentity.matches(it, normalized) }
            else -> current + normalized
        }
        writeStored(next)
        return next
    }
}
