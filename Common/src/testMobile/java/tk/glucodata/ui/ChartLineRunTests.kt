package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLineRunTests {

    @Test
    fun connectedRunProducesNoDots() {
        val run = ChartLineRun()
        run.begin(0f, 0f)
        run.extend()
        run.extend()
        run.flush()

        assertTrue(run.isolatedPoints.isEmpty())
    }

    @Test
    fun runWithASinglePointIsReportedSoItCanBeDrawn() {
        val run = ChartLineRun()
        run.begin(12f, 34f)
        run.flush()

        assertEquals(1, run.isolatedPoints.size)
        assertEquals(12f, run.isolatedPoints[0].x, 0f)
        assertEquals(34f, run.isolatedPoints[0].y, 0f)
    }

    @Test
    fun startingANewRunClosesThePreviousOne() {
        val run = ChartLineRun()
        run.begin(1f, 1f)      // lone point
        run.begin(2f, 2f)      // lone point
        run.extend()           // ...now connected
        run.begin(3f, 3f)      // lone point
        run.flush()

        assertEquals(listOf(1f, 3f), run.isolatedPoints.map { it.x })
    }

    @Test
    fun flushIsIdempotent() {
        val run = ChartLineRun()
        run.begin(5f, 5f)
        run.flush()
        run.flush()

        assertEquals(1, run.isolatedPoints.size)
    }
}
