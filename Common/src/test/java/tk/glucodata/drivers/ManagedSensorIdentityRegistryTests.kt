package tk.glucodata.drivers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedSensorIdentityRegistryTests {

    private object Owner : ManagedSensorIdentityAdapter
    private object Other : ManagedSensorIdentityAdapter
    private object Throwing : ManagedSensorIdentityAdapter

    private fun ids(adapter: ManagedSensorIdentityAdapter): List<String> = when (adapter) {
        Owner -> listOf("X-2222267V4E")
        Other -> listOf("6CA04230E260")
        else -> throw IllegalStateException("prefs unavailable")
    }

    @Test
    fun isPersistedByOtherDriver_seesAnotherDriversSensor() {
        assertTrue(
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, "6ca04230e260", listOf(Owner, Other), ::ids,
            )
        )
    }

    @Test
    fun isPersistedByOtherDriver_ignoresTheOwnersOwnSensor() {
        assertFalse(
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, "X-2222267V4E", listOf(Owner, Other), ::ids,
            )
        )
        assertFalse(
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, null, listOf(Owner, Other), ::ids,
            )
        )
        assertFalse(
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, "  ", listOf(Owner, Other), ::ids,
            )
        )
    }

    @Test
    fun isPersistedByOtherDriver_stopsAtTheFirstOwner() {
        // A later adapter that cannot read its records is never reached once an earlier one owns
        // the id, so the answer does not depend on it.
        assertTrue(
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, "6CA04230E260", listOf(Other, Throwing), ::ids,
            )
        )
    }

    /**
     * Fails closed on purpose. The guard stops an AiDex row from taking over another driver's
     * sensor id; a driver whose records cannot be read is unknown, not empty, so the throw must
     * reach addAiDexSensor, whose catch refuses the bind. Swallowing it here would let the
     * takeover through exactly when the other driver's storage is damaged.
     */
    @Test
    fun isPersistedByOtherDriver_letsAnUnreadableDriverRefuseTheBind() {
        assertThrows(IllegalStateException::class.java) {
            ManagedSensorIdentityRegistry.isPersistedByOtherDriver(
                Owner, "6CA04230E260", listOf(Throwing, Other), ::ids,
            )
        }
    }
}
