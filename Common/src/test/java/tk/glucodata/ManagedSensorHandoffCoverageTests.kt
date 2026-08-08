package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handoff transfers a sensor by copying its stored record, auth material and
 * session state to the other device. A driver whose keys are not on the export
 * list hands over a sensor the receiving device cannot connect to, and nothing
 * fails loudly — the watch simply waits to connect forever, which is exactly how
 * Sibionics behaved.
 */
class ManagedSensorHandoffCoverageTests {
    private val serial = "46HU804EBJ4"

    private fun assertExported(key: String) {
        assertTrue(
            "$key would not travel with a handoff",
            ManagedSensorHandoff.exportsKeyForSensor(key, serial),
        )
    }

    @Test
    fun sibionicsSessionStateTravelsWithTheSensor() {
        // Without these the watch has an identity but no way to talk to the
        // sensor, or no idea where the session had got to.
        assertExported("sibionics_managed_auth_key_hint_$serial")
        assertExported("sibionics_managed_short_code_$serial")
        assertExported("sibionics_managed_protocol_$serial")
        assertExported("sibionics_managed_start_time_$serial")
        assertExported("sibionics_managed_algorithm_state_$serial")
        assertExported("sibionics_managed_last_index_$serial")
    }

    @Test
    fun everyManagedDriverNamespaceIsCovered() {
        assertExported("aidex_device_id_$serial")
        assertExported("anytime_device_id_$serial")
        assertExported("icanhealth_device_id_$serial")
        assertExported("mq_device_id_$serial")
        assertExported("ottai_keya_$serial")
        assertExported("sibionics_managed_protocol_$serial")
        assertExported("nightscout_follower_state_$serial")
        assertExported("api_glucose_source_state_$serial")
    }

    @Test
    fun unrelatedSettingsStayOnTheDeviceThatOwnsThem() {
        // Only per-sensor state travels: app-wide preferences belong to the
        // device they were set on.
        assertFalse(ManagedSensorHandoff.exportsKeyForSensor("dashboard_journal_enabled", serial))
        assertFalse(ManagedSensorHandoff.exportsKeyForSensor("notification_font_weight", serial))
        assertFalse(
            ManagedSensorHandoff.exportsKeyForSensor("sibionics_managed_legacy_migration_version", serial),
        )
    }

    @Test
    fun anotherSensorsStateIsNotHandedOver() {
        assertFalse(ManagedSensorHandoff.exportsKeyForSensor("ottai_keya_6CA04230E260", serial))
        assertFalse(ManagedSensorHandoff.exportsKeyForSensor("sibionics_managed_protocol_OTHER123", serial))
    }
}
