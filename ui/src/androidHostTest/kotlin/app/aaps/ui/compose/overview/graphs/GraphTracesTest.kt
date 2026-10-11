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
    fun `bars touch like the DEV bars and the last one ends after the usual gap`() {
        val bars = barPoints(listOf(0.0 to 100.0, 5.0 to 50.0, 10.0 to 0.0, 15.0 to 20.0))

        // Each value is held until the next point; only the end gets a closing point at zero
        assertThat(bars).containsExactly(0.0 to 100.0, 5.0 to 50.0, 10.0 to 0.0, 15.0 to 20.0, 20.0 to 0.0).inOrder()
    }

    @Test
    fun `a bar before a gap is not stretched over the gap`() {
        val bars = barPoints(listOf(0.0 to 1.0, 5.0 to 1.0, 10.0 to 1.0, 100.0 to 2.0))

        assertThat(bars).containsExactly(0.0 to 1.0, 5.0 to 1.0, 10.0 to 1.0, 15.0 to 0.0, 100.0 to 2.0, 105.0 to 0.0).inOrder()
        // x grows strictly, so the Square line can draw it
        assertThat(bars.zipWithNext().all { (a, b) -> b.first > a.first }).isTrue()
    }

    @Test
    fun `a second point at the same time is dropped`() {
        assertThat(barPoints(listOf(0.0 to 1.0, 0.0 to 9.0, 5.0 to 2.0))).containsExactly(0.0 to 1.0, 5.0 to 2.0, 10.0 to 0.0).inOrder()
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
    fun `a series can have any number of calculations, or none`() {
        val hr = GraphTrace(SeriesType.HEART_RATE)
        var traces = listOf(hr)
        repeat(3) { traces = traces.withNewDerived()!! }

        assertThat(traces).containsExactly(
            hr,
            GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA, 30),
            GraphTrace(SeriesType.HEART_RATE, TraceTransform.ROLLING_MEAN, 30),
            GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA, 60)
        ).inOrder()
        // All on one axis
        assertThat(traces.map { it.axisKey() }.distinct()).hasSize(1)
        // The series itself can go, the calculations stay
        assertThat(traces.toggleRaw(SeriesType.HEART_RATE)).hasSize(3)
        assertThat(emptyList<GraphTrace>().withNewDerived()).isNull()
    }

    @Test
    fun `a second axis is added and a third pushes out the oldest`() {
        val traces = listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA))

        val withSteps = traces.toggleRaw(SeriesType.STEPS)
        assertThat(withSteps).containsExactly(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA), GraphTrace(SeriesType.STEPS)).inOrder()

        // COB needs a third axis: the oldest group (heart rate) goes
        assertThat(withSteps.toggleRaw(SeriesType.COB)).containsExactly(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.COB)).inOrder()

        // A rolling sum has its own scale, so turning the EWMA into one also makes a new axis group
        val summed = withSteps.replaced(1, GraphTrace(SeriesType.HEART_RATE, TraceTransform.ROLLING_SUM, 60))
        assertThat(summed).containsExactly(GraphTrace(SeriesType.HEART_RATE, TraceTransform.ROLLING_SUM, 60), GraphTrace(SeriesType.STEPS)).inOrder()
    }

    @Test
    fun `bgi and dev share an axis`() {
        assertThat(GraphTrace(SeriesType.BGI).axisKey()).isEqualTo(GraphTrace(SeriesType.DEVIATIONS, TraceTransform.EWMA).axisKey())
        assertThat(GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM, 30).axisKey())
            .isNotEqualTo(GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM, 60).axisKey())
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

    @Test
    fun `a step series is sampled every step with the value in effect`() {
        val samples = sampleSteps(points(0 to 1.0, 12 to 0.0, 20 to 0.5), 5 * minute)

        assertThat(samples.map { it.timestamp / minute }).containsExactly(0L, 5L, 10L, 15L, 20L).inOrder()
        assertThat(samples.map { it.value }).containsExactly(1.0, 1.0, 1.0, 0.0, 0.5).inOrder()
    }

    @Test
    fun `basal is a step series drawn as an area`() {
        assertThat(GraphTrace(SeriesType.BASAL).effectiveStyle()).isEqualTo(PlotStyle.AREA)
        assertThat(GraphTrace(SeriesType.BASAL).isStylable).isTrue()
    }
}
