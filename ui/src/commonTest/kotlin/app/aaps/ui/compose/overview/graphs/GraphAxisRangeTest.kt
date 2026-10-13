package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AxisRange
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Axis ranges the user types, and the traces of the BG graph. */
class GraphAxisRangeTest {

    @Test
    fun autoKeepsTheFoundRange() {
        assertEquals(2.0 to 12.0, AxisRange.AUTO.applyTo(2.0, 12.0))
    }

    @Test
    fun eachTypedEndReplacesOnlyItsOwnEnd() {
        assertEquals(3.0 to 12.0, AxisRange(min = 3.0).applyTo(2.0, 12.0))
        assertEquals(2.0 to 20.0, AxisRange(max = 20.0).applyTo(2.0, 12.0))
        assertEquals(0.0 to 5.0, AxisRange(0.0, 5.0).applyTo(2.0, 12.0))
    }

    @Test
    fun aRangeWithNothingLeftToShowIsNotUsed() {
        // A typed min above the data's max: the graph would be empty, so the caller keeps its own range
        assertNull(AxisRange(min = 15.0).applyTo(2.0, 12.0))
    }

    @Test
    fun bgStaysOnTheBgGraphWhenAThirdAxisGroupIsAdded() {
        val bg = GraphTrace(SeriesType.BG)
        val edited = listOf(bg, GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS))

        // The oldest other group goes, BG's axis always stays; BG itself is not stored
        assertEquals(listOf(GraphTrace(SeriesType.STEPS)), bgTracesAfterEdit(edited))
    }

    @Test
    fun calculationsOverBgAreKeptOnTheBgGraph() {
        val ewma = GraphTrace(SeriesType.BG, TraceTransform.EWMA, 30)

        assertEquals(listOf(ewma, GraphTrace(SeriesType.COB)), bgTracesAfterEdit(listOf(GraphTrace(SeriesType.BG), ewma, GraphTrace(SeriesType.COB))))
    }

    @Test
    fun anIobGraphHasTheBasalBandOnItsRight() {
        assertEquals(BASAL_AXIS_KEY, rightAxisKey(GraphConfig.DEFAULT_IOB_GRAPH.traces))
        assertNull(rightAxisKey(listOf(GraphTrace(SeriesType.COB))))
    }

    @Test
    fun aRangeIsResetWhenItsAxisGetsAnotherSeries() {
        val graph = SecondaryGraph(listOf(GraphTrace(SeriesType.COB)), leftRange = AxisRange(0.0, 60.0))

        assertEquals(AxisRange.AUTO, graph.withTraces(listOf(GraphTrace(SeriesType.HEART_RATE))).leftRange)
    }

    @Test
    fun aRangeIsKeptWhenOnlyACalculationIsAdded() {
        val graph = SecondaryGraph(listOf(GraphTrace(SeriesType.HEART_RATE)), leftRange = AxisRange(40.0, 160.0))

        val edited = graph.withTraces(listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA, 30)))

        assertEquals(AxisRange(40.0, 160.0), edited.leftRange)
    }

    @Test
    fun theBasalRangeIsResetWhenTheRightAxisGetsTraces() {
        val graph = GraphConfig.DEFAULT_IOB_GRAPH.copy(rightRange = AxisRange(max = 3.0))

        val edited = graph.withTraces(listOf(GraphTrace(SeriesType.IOB), GraphTrace(SeriesType.HEART_RATE)))

        assertEquals(AxisRange.AUTO, edited.rightRange)
    }
}
