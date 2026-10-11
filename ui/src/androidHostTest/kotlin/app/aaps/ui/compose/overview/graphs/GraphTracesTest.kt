package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.PlotStyle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.exp

class GraphTracesTest {

    private val minute = 60_000L

    private fun points(vararg pairs: Pair<Int, Double>) = pairs.map { (m, v) -> GraphDataPoint(m * minute, v) }

    @Test
    fun `rolling sum and mean use the points in the last window`() {
        val data = points(0 to 10.0, 5 to 20.0, 10 to 30.0, 15 to 40.0)

        val sum = data.transformed(TraceTransform.ROLLING_SUM, 10).map { it.value }
        val mean = data.transformed(TraceTransform.ROLLING_MEAN, 10).map { it.value }

        // Window (t - 10 min, t]: the point exactly 10 minutes back is out
        assertThat(sum).containsExactly(10.0, 30.0, 50.0, 70.0).inOrder()
        assertThat(mean).containsExactly(10.0, 15.0, 25.0, 35.0).inOrder()
    }

    @Test
    fun `rolling sum does not count across a gap`() {
        val data = points(0 to 100.0, 120 to 5.0)

        assertThat(data.transformed(TraceTransform.ROLLING_SUM, 60).map { it.value }).containsExactly(100.0, 5.0).inOrder()
    }

    @Test
    fun `ewma follows the real time between points`() {
        val data = points(0 to 0.0, 30 to 100.0, 1000 to 50.0)

        val ewma = data.transformed(TraceTransform.EWMA, 30).map { it.value }

        assertThat(ewma[0]).isEqualTo(0.0)
        // One window later it has moved 1 - e^-1 of the way
        assertThat(ewma[1]).isWithin(1e-9).of(100.0 * (1 - exp(-1.0)))
        // After a long gap it starts again from the new value
        assertThat(ewma[2]).isWithin(1e-6).of(50.0)
    }

    @Test
    fun `no calculation keeps the points`() {
        val data = points(0 to 1.0)
        assertThat(data.transformed(TraceTransform.NONE, 30)).isSameInstanceAs(data)
    }

    @Test
    fun `bars end at their point and go back to zero`() {
        val bars = barPoints(listOf(5.0 to 100.0, 10.0 to 50.0), startAligned = false)

        assertThat(bars.map { it.second }).containsExactly(100.0, 0.0, 50.0, 0.0).inOrder()
        // x grows strictly, so the Square line can draw it
        assertThat(bars.zipWithNext().all { (a, b) -> b.first > a.first }).isTrue()
        assertThat(bars[1].first).isEqualTo(5.0)
        assertThat(bars[3].first).isEqualTo(10.0)
    }

    @Test
    fun `a bar after a gap is not stretched over the gap`() {
        val bars = barPoints(listOf(0.0 to 1.0, 5.0 to 1.0, 10.0 to 1.0, 100.0 to 1.0), startAligned = false)

        val lastStart = bars[bars.size - 2].first
        assertThat(100.0 - lastStart).isLessThan(5.0)
    }

    @Test
    fun `start aligned bars begin at their point`() {
        val bars = barPoints(listOf(0.0 to 70.0, 5.0 to 80.0), startAligned = true)

        assertThat(bars.first()).isEqualTo(0.0 to 70.0)
        assertThat(bars.zipWithNext().all { (a, b) -> b.first > a.first }).isTrue()
    }

    @Test
    fun `a line is broken where the data has a gap`() {
        val runs = splitAtGaps(listOf(0.0 to 1.0, 5.0 to 1.0, 10.0 to 1.0, 60.0 to 1.0, 65.0 to 1.0))

        assertThat(runs.map { it.size }).containsExactly(3, 2).inOrder()
    }

    @Test
    fun `defaults are bars for steps and a line for heart rate`() {
        assertThat(GraphTrace(SeriesType.STEPS).effectiveStyle()).isEqualTo(PlotStyle.BARS)
        assertThat(GraphTrace(SeriesType.HEART_RATE).effectiveStyle()).isEqualTo(PlotStyle.LINE)
        assertThat(GraphTrace(SeriesType.BGI).effectiveStyle()).isEqualTo(PlotStyle.POINTS)
        assertThat(GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM).effectiveStyle()).isEqualTo(PlotStyle.LINE)
    }

    @Test
    fun `a style on a series that cannot be styled is ignored`() {
        assertThat(GraphTrace(SeriesType.COB, style = PlotStyle.BARS).effectiveStyle()).isEqualTo(PlotStyle.LINE)
    }

    @Test
    fun `toggling a series keeps at most two traces`() {
        val traces = listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA))

        assertThat(traces.toggleRaw(SeriesType.STEPS))
            .containsExactly(GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA), GraphTrace(SeriesType.STEPS)).inOrder()
        assertThat(traces.toggleRaw(SeriesType.HEART_RATE)).containsExactly(GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA))
    }

    @Test
    fun `a new calculated trace uses the series of the first trace`() {
        assertThat(listOf(GraphTrace(SeriesType.STEPS)).withNewDerived())
            .containsExactly(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.STEPS, TraceTransform.EWMA)).inOrder()
        assertThat(listOf(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.COB)).withNewDerived()).isNull()
        assertThat(emptyList<GraphTrace>().withNewDerived()).isNull()
    }

    @Test
    fun `a change that would repeat another trace is not made`() {
        val traces = listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA))

        assertThat(traces.replaced(1, GraphTrace(SeriesType.HEART_RATE))).isSameInstanceAs(traces)
        assertThat(traces.replaced(1, GraphTrace(SeriesType.HEART_RATE, TraceTransform.ROLLING_MEAN))[1].transform).isEqualTo(TraceTransform.ROLLING_MEAN)
    }

    @Test
    fun `a series comes before a calculation over it and DEV slope comes first`() {
        val ewma = GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA)
        val hr = GraphTrace(SeriesType.HEART_RATE)
        assertThat(orderTraces(listOf(ewma, hr))).containsExactly(hr, ewma).inOrder()

        val slope = GraphTrace(SeriesType.DEV_SLOPE)
        assertThat(orderTraces(listOf(hr, slope))).containsExactly(slope, hr).inOrder()
    }
}
