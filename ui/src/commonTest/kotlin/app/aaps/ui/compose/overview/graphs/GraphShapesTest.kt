package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.SeriesType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Steps drawn as bars, heart rate as a line broken where readings are missing, the colour of a DEV carbs run. */
class GraphShapesTest {

    @Test
    fun barsTouchAndDropToZeroAtAGapAndAtTheEnd() {
        // Every 5 minutes, then 30 minutes without data
        val steps = listOf(0.0 to 100.0, 5.0 to 200.0, 10.0 to 150.0, 40.0 to 50.0)

        assertEquals(listOf(0.0 to 100.0, 5.0 to 200.0, 10.0 to 150.0, 15.0 to 0.0, 40.0 to 50.0, 45.0 to 0.0), barPoints(steps))
    }

    @Test
    fun aLineIsBrokenWhereReadingsAreMissing() {
        val hr = listOf(0.0 to 70.0, 5.0 to 72.0, 10.0 to 75.0, 60.0 to 90.0, 65.0 to 92.0)

        assertEquals(listOf(listOf(0.0 to 70.0, 5.0 to 72.0, 10.0 to 75.0), listOf(60.0 to 90.0, 65.0 to 92.0)), splitAtGaps(hr))
    }

    @Test
    fun onlyStepsAndHeartRateGetTheirOwnShape() {
        // Every 5 minutes, then 50 minutes without data
        val pts = listOf(0.0 to 1.0, 5.0 to 2.0, 10.0 to 3.0, 60.0 to 4.0)

        assertEquals(listOf(barPoints(pts)), shapedRuns(SeriesType.STEPS, pts))
        assertEquals(2, shapedRuns(SeriesType.HEART_RATE, pts).size)
        assertEquals(listOf(pts), shapedRuns(SeriesType.COB, pts))
    }

    @Test
    fun aDevCarbsRunTakesItsColourFromItsLargestPoint() {
        assertTrue(isPositiveRun(listOf(0.0 to 0.0, 5.0 to 2.0, 10.0 to 4.0)))
        assertFalse(isPositiveRun(listOf(0.0 to 0.0, 5.0 to -1.5, 10.0 to -3.0)))
        // A move back that is not a turn yet can go a little past zero for one reading
        assertTrue(isPositiveRun(listOf(0.0 to 0.0, 5.0 to 3.5, 10.0 to -0.5, 15.0 to 6.0)))
        // DEV carbs come as runs already, the graph does not split them again
        val run = listOf(0.0 to 0.0, 5.0 to 2.0, 10.0 to 0.0, 15.0 to 3.0)
        assertEquals(listOf(run), shapedRuns(SeriesType.DEV_CARBS, run))
    }
}
