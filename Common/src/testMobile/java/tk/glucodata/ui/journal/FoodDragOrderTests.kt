package tk.glucodata.ui.journal

import org.junit.Assert.*
import org.junit.Test

class FoodDragOrderTests {
    @Test
    fun failedPersistenceRestoresTheDatabaseOrder() {
        val state = FoodDragOrder()
        val ids = listOf(1L, 2L, 3L)
        state.start(2L, ids)
        state.move(2L, 3L, ids)
        var completion: ((Boolean) -> Unit)? = null
        state.finish(ids) { moved, before, done ->
            assertEquals(setOf(2L), moved)
            assertNull(before)
            completion = done
        }
        assertEquals(listOf(1L, 3L, 2L), state.order(ids))
        completion!!(false)
        assertEquals(ids, state.order(ids))
    }

    @Test
    fun incomingFoodsArePreservedAndRemovedDraggedFoodCannotBeResurrected() {
        val state = FoodDragOrder()
        state.start(1L, listOf(1L, 2L, 3L))
        state.move(1L, 3L, listOf(1L, 2L, 3L))
        assertEquals(listOf(2L, 3L, 1L, 4L), state.order(listOf(1L, 2L, 3L, 4L)))
        var persisted = false
        state.finish(listOf(2L, 3L, 4L)) { _, _, _ -> persisted = true }
        assertFalse(persisted)
        assertEquals(listOf(2L, 3L, 4L), state.order(listOf(2L, 3L, 4L)))
    }
}
