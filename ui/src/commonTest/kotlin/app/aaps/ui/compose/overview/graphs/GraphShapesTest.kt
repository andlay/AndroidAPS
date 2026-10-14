package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.SeriesType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Steps drawn as bars, heart rate as a line broken where readings are missing. */
class GraphShapesTest {

    @Test
    fun barsTouchAndDropToZeroAtAGapAndAtTheEnd() {
        val steps = listOf(0.0 to 100.0, 5.0 to 200.0, 30.0 to 50.0)

        assertEquals(listOf(0.0 to 100.0, 5.0 to 200.0, 10.0 to 0.0, 30.0 to 50.0, 35.0 to 0.0), barPoints(steps))
    }

    @Test
    fun aLineIsBrokenWhereReadingsAreMissing() {
        val hr = listOf(0.0 to 70.0, 5.0 to 72.0, 10.0 to 75.0, 60.0 to 90.0, 65.0 to 92.0)

        assertEquals(listOf(listOf(0.0 to 70.0, 5.0 to 72.0, 10.0 to 75.0), listOf(60.0 to 90.0, 65.0 to 92.0)), splitAtGaps(hr))
    }

    @Test
    fun onlyStepsAndHeartRateGetTheirOwnShape() {
        val pts = listOf(0.0 to 1.0, 60.0 to 2.0)

        assertEquals(listOf(barPoints(pts)), shapedRuns(SeriesType.STEPS, pts))
        assertEquals(2, shapedRuns(SeriesType.HEART_RATE, pts).size)
        assertEquals(listOf(pts), shapedRuns(SeriesType.COB, pts))
    }
}
