// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3Session.kt — Transmitter/probe lifecycle model and bring-up sequence.
//
// Recovered from decompiled `Cgm3TransmitterModel`, `TransmitterManager` and
// `CGMPairUtil` (v1.16.1). This file captures the ORDER of operations and the
// lifecycle states so the future GATT manager (`Cgm3BleManager`, not yet
// written — needs one live session to validate) can follow it exactly.
// Pure JVM, no Android dependency.
// Full analysis: docs/aidex-g9-cgm3-protocol.md (5.2, 5.5).

package tk.glucodata.drivers.aidex.cgm3

object Cgm3Session {

    /** Transmitter bond states (2-year reusable unit). */
    enum class TransmitterState {
        /** Never paired. */
        NEW,

        /** Bonded + session key installed, streaming. */
        BONDED,

        /** Transmitter reports expiry (`ble_state_transmitter_alert_didExpired`). */
        EXPIRED,

        /** Transmitter fault (`cgm3_deviceStatus_warning_7`). */
        FAULT,

        /** Explicit unpair (normal → reusable, forced → bricked for others). */
        UNPAIRED,
    }

    /** Probe states (15-day disposable unit under a bonded transmitter). */
    enum class ProbeState {
        /** No probe seen yet. */
        ABSENT,

        /** Probe implanted, needs activation code (`步骤2`). */
        AWAITING_ACTIVATION,

        /** Activating (`newSensorOnline`, 0x2E chunks in flight). */
        ACTIVATING,

        /** Active wear (`resumeSensor` on reconnects). */
        ACTIVE,

        /** Reconnect-with-new-probe dialog pending user confirmation. */
        RECONNECT_DIALOG,

        /** Probe expired (`ble_state_sensor_expired*`, `cgm3` variants). */
        EXPIRED,
    }

    /**
     * Bring-up step order, recovered from
     * `Cgm3TransmitterModel.Companion.instance`. Each step maps 1:1 to a
     * native `BleController` call.
     */
    enum class BringUpStep {
        SET_MAC,
        SET_SN,
        SET_NAME,
        SET_HOST_ADDRESS,
        SET_ID,
        SET_KEY,
        SET_SN_WITH_MATERIAL,
        ENABLE_KEEP_CONNECT,
        ATTACH_CALLBACK,
        PAIR,
    }

    /** Ordered bring-up program for a bonded or new transmitter. */
    val BRING_UP_ORDER: List<BringUpStep> = listOf(
        BringUpStep.SET_MAC,
        BringUpStep.SET_SN,
        BringUpStep.SET_NAME,
        BringUpStep.SET_HOST_ADDRESS,
        BringUpStep.SET_ID,
        BringUpStep.SET_KEY,
        BringUpStep.SET_SN_WITH_MATERIAL,
        BringUpStep.ENABLE_KEEP_CONNECT,
        BringUpStep.ATTACH_CALLBACK,
        BringUpStep.PAIR,
    )

    /**
     * Probe-rollover program (same bond + session key, new probe):
     * confirm reconnect dialog → `resumeSensor` (0x23) or `newSensorOnline`
     * (0x20 + 0x2E chunks) with the activation code → history backfill.
     */
    enum class RolloverStep {
        CONFIRM_RECONNECT,
        RESUME_OR_ACTIVATE,
        BACKFILL,
    }

    /** Maximum probe wear (`一次性探头，最多佩戴15天`). */
    const val PROBE_MAX_DAYS: Int = 15

    /** Transmitter design life (reusable unit). */
    const val TRANSMITTER_LIFE_YEARS: Int = 2

    /**
     * Whether a BLE advertisement name belongs to the CGM3 dynamic-secret
     * family. Default rules (no cloud list): exact `AiDEX F` / `AiDEX S`;
     * cloud `BleNameInfo` rows extend this per name (`special==1`).
     * Transmitter-serial suffix patterns still need one live sample.
     */
    fun isDynamicSecretName(
        bleName: String,
        cloudSpecialNames: Set<String> = emptySet(),
    ): Boolean {
        if (bleName in Cgm3Provisioning.DEFAULT_DYNAMIC_NAMES) return true
        return bleName in cloudSpecialNames
    }
}
