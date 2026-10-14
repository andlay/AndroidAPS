package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The values shown when holding a graph. */
class GraphInspectTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000_000L

    private fun bg(minutes: Int, value: Double = 6.0) = BgDataPoint(t0 + minutes * minute, value, BgRange.IN_RANGE, BgType.REGULAR)
    private fun point(minutes: Int, value: Double) = GraphDataPoint(t0 + minutes * minute, value)

    private val readings = listOf(bg(0, 5.0), bg(5, 5.5), bg(10, 6.0))

    @Test
    fun `snaps to the nearest reading`() {
        val data = inspectDataAt(timestamp = t0 + 6 * minute, bgReadings = readings, boluses = emptyList(), carbs = emptyList())

        assertThat(data.timestamp).isEqualTo(t0 + 5 * minute)
        assertThat(data.bg?.value).isEqualTo(5.5)
    }

    @Test
    fun `no reading within 5 minutes gives no glucose, and the finger's time`() {
        val data = inspectDataAt(timestamp = t0 + 30 * minute, bgReadings = readings, boluses = emptyList(), carbs = emptyList())

        assertThat(data.bg).isNull()
        assertThat(data.timestamp).isEqualTo(t0 + 30 * minute)
    }

    @Test
    fun `a sampled series takes its nearest point within 5 minutes`() {
        val iob = listOf(point(0, 1.0), point(5, 1.2), point(10, 1.4))

        assertThat(traceValueAt(iob, t0 + 6 * minute, GraphTrace(SeriesType.IOB))).isEqualTo(1.2)
        assertThat(traceValueAt(iob, t0 + 30 * minute, GraphTrace(SeriesType.IOB))).isNull()
    }

    @Test
    fun `basal is the last change before the time, however long ago`() {
        val basal = listOf(point(-30, 0.8), point(4, 1.5), point(20, 0.0))

        assertThat(traceValueAt(basal, t0 + 5 * minute, GraphTrace(SeriesType.BASAL))).isEqualTo(1.5)
        assertThat(traceValueAt(basal, t0 + 3 * minute, GraphTrace(SeriesType.BASAL))).isEqualTo(0.8)
    }

    @Test
    fun `steps are the period the time is in, not the next one`() {
        val steps = listOf(point(0, 100.0), point(5, 400.0))

        // 4 minutes is still in the period that started at 0, though 5 is nearer
        assertThat(traceValueAt(steps, t0 + 4 * minute, GraphTrace(SeriesType.STEPS))).isEqualTo(100.0)
        assertThat(traceValueAt(steps, t0 + 20 * minute, GraphTrace(SeriesType.STEPS))).isNull()
    }

    @Test
    fun `values leave out traces with no data and COB at 0 g`() {
        val cob = GraphTrace(SeriesType.COB)
        val hr = GraphTrace(SeriesType.HEART_RATE)
        val iob = GraphTrace(SeriesType.IOB)
        val points = mapOf(cob to listOf(point(5, 0.2)), hr to emptyList(), iob to listOf(point(5, 0.0)))

        // IOB 0 is a value; COB 0 g is not worth a place
        assertThat(inspectValues(listOf(iob, cob, hr), points, t0 + 5 * minute)).containsExactly(InspectValue(iob, 0.0))
    }

    @Test
    fun `UAM carbs show only for a rise that UAM counts`() {
        val uam = GraphTrace(SeriesType.UAM_CARBS)
        val points = mapOf(uam to listOf(point(0, 0.0), point(5, 2.4)))

        // A UAM carbs point covers the 5 minutes after it, like a DEV bar
        assertThat(inspectValues(listOf(uam), points, t0 + 7 * minute)).containsExactly(InspectValue(uam, 2.4))
        assertThat(inspectValues(listOf(uam), points, t0 + 2 * minute)).isEmpty()
    }

    @Test
    fun `one line per graph, with the basal band and the activity overlay of an IOB graph`() {
        val hr = GraphTrace(SeriesType.HEART_RATE)
        val config = GraphConfig(
            bgOverlays = listOf(SeriesType.ACTIVITY),
            bgTraces = listOf(hr),
            secondaryGraphs = listOf(GraphConfig.DEFAULT_IOB_GRAPH.copy(activityOverlay = true), SecondaryGraph.of(listOf(SeriesType.COB)))
        )

        val rows = inspectRowTraces(config)

        assertThat(rows).hasSize(3)
        assertThat(rows[0]).containsExactly(hr, GraphTrace(SeriesType.ACTIVITY)).inOrder()
        assertThat(rows[1]).containsExactly(GraphTrace(SeriesType.IOB), GraphTrace(SeriesType.BASAL), GraphTrace(SeriesType.ACTIVITY)).inOrder()
        assertThat(rows[2]).containsExactly(GraphTrace(SeriesType.COB))
    }

    @Test
    fun `the pop-up goes above the touched graph when it fits, else under it`() {
        // Graph from 100 to 200, gap 4
        assertThat(inspectPopupTop(touchedTop = 100f, touchedBottom = 200f, popupHeight = 60f, gap = 4f)).isEqualTo(36f)
        assertThat(inspectPopupTop(touchedTop = 40f, touchedBottom = 200f, popupHeight = 60f, gap = 4f)).isEqualTo(204f)
    }

    @Test
    fun `the finger's place on a graph gives the time under it, kept inside the plot`() {
        val geometry = GraphGeometryHolder()
        assertThat(geometry.xAt(50f)).isNull()

        geometry.left = 10f
        geometry.right = 110f
        geometry.firstVisibleX = 300.0
        geometry.xPerPixel = 2.0

        assertThat(geometry.xAt(60f)).isEqualTo(400.0)
        assertThat(geometry.xAt(0f)).isEqualTo(300.0)
        assertThat(geometry.xAt(500f)).isEqualTo(500.0)
    }

    @Test
    fun `holding a graph under BG starts inspecting from that graph, lifting ends it`() {
        val state = GraphInspectState()

        state.startHold(graphIndex = 1, x = 42.0)
        assertThat(state.fingerDown).isTrue()
        assertThat(state.source).isEqualTo(1)
        assertThat(state.targetX).isEqualTo(42.0)

        state.endHold()
        assertThat(state.fingerDown).isFalse()
    }

    @Test
    fun `only valid treatments within half a reading interval belong to the reading`() {
        val near = BolusGraphPoint(t0 + 5 * minute + 60_000, 0.4, BolusType.SMB, isValid = true, label = "0.40 U")
        val far = BolusGraphPoint(t0 + 5 * minute + 4 * minute, 1.0, BolusType.NORMAL, isValid = true, label = "1.00 U")
        val invalid = BolusGraphPoint(t0 + 5 * minute, 2.0, BolusType.NORMAL, isValid = false, label = "2.00 U")
        val carbs = CarbsGraphPoint(t0 + 5 * minute - 60_000, 20.0, isValid = true, label = "20 g")

        val data = inspectDataAt(
            timestamp = t0 + 5 * minute,
            bgReadings = readings,
            boluses = listOf(near, far, invalid),
            carbs = listOf(carbs)
        )

        assertThat(data.boluses).containsExactly(near)
        assertThat(data.carbs).containsExactly(carbs)
    }

    @Test
    fun `COB is left out of the pop-up when it is 0 g`() {
        assertThat(shownCob(null)).isNull()
        assertThat(shownCob(0.0)).isNull()
        // Rounds to 0 g, so it would read "0 g"
        assertThat(shownCob(0.4)).isNull()
        assertThat(shownCob(0.6)).isEqualTo(1)
        assertThat(shownCob(23.7)).isEqualTo(24)
    }

    @Test
    fun `leaving the plotted area with the finger down keeps inspecting, lifting it ends`() {
        val state = GraphInspectState()
        val marker = GraphInspectMarker(state)
        state.fingerDown = true
        state.pointerDown = true

        // Vico hides the marker when the finger is past the readings, e.g. over the axis labels
        state.visibilityListener.onHidden(marker)
        assertThat(state.fingerDown).isTrue()

        state.pointerDown = false
        state.visibilityListener.onHidden(marker)
        assertThat(state.fingerDown).isFalse()
    }
}
