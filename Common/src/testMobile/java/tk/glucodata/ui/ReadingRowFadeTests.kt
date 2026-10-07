package tk.glucodata.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingRowFadeTests {
    private val active = Color.Red
    private val stock = Color.Green
    private val stale = Color.Blue

    @Test
    fun newestRowStartsAccentedSettlesThenGoesStale() {
        assertEquals(active, activeRowColor(0L, active, stock, stale))
        assertEquals(stock, activeRowColor(60_000L, active, stock, stale))
        assertEquals(stock, activeRowColor(89_999L, active, stock, stale))
        assertEquals(stale, activeRowColor(150_000L, active, stock, stale))
        assertEquals(stale, activeRowColor(10 * 60_000L, active, stock, stale))
    }

    @Test
    fun dividerHidesWhileAccentedAndAgainWhenStale() {
        assertEquals(0f, activeRowDividerAlpha(0L), 0f)
        assertEquals(0.05f, activeRowDividerAlpha(30_000L), 1e-6f)
        assertEquals(0.1f, activeRowDividerAlpha(75_000L), 0f)
        assertEquals(0f, activeRowDividerAlpha(150_000L), 1e-6f)
    }
}
