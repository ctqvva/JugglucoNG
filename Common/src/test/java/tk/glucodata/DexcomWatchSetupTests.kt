package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Bluetooth slot a watch pairs a phone's G7 on. Each slot has its own key;
 * two devices on one slot take it from each other, so the watch must never
 * pick the slot the phone is using.
 */
class DexcomWatchSetupTests {
    @Test
    fun theWatchUsesTheWatchSlotWhenThePhoneDoesNot() {
        assertEquals(DexcomBluetoothSlot.SMART_WATCH, watchSlotFor(DexcomBluetoothSlot.MOBILE_APP))
        assertEquals(DexcomBluetoothSlot.SMART_WATCH, watchSlotFor(DexcomBluetoothSlot.RECEIVER_OR_PUMP))
    }

    @Test
    fun aPhoneOnTheWatchSlotLeavesTheWatchTheAppSlot() {
        // The sensor-card picker lets the phone pair as a watch (#644, #862).
        assertEquals(DexcomBluetoothSlot.MOBILE_APP, watchSlotFor(DexcomBluetoothSlot.SMART_WATCH))
    }
}
