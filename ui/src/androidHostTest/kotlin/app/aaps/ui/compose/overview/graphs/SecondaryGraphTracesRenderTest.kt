package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.HeartRateGraphData
import app.aaps.core.interfaces.overview.graph.PlotStyle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.StepsGraphData
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.ui.compose.testing.AapsScreenFixture
import app.aaps.ui.compose.testing.OverviewViewModelFixture
import app.aaps.ui.compose.testing.setAapsContent
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Draws a secondary graph with calculated traces on real looking heart rate and steps data, to make
 * sure no combination of traces, axes and plot styles crashes the chart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SecondaryGraphTracesRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var screen: AapsScreenFixture
    private lateinit var viewModels: OverviewViewModelFixture

    private val end = 1_760_000_000_000L
    private val start = end - 6 * 3600_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        screen = AapsScreenFixture()
        viewModels = OverviewViewModelFixture(screen)
        // Heart rate about every minute, with odd seconds and a gap of an hour
        viewModels.cache.heartRateGraphFlow.value = HeartRateGraphData(
            (0 until 360).filter { it !in 120..180 }.map { m -> GraphDataPoint(start + m * 60_000L + (m * 7919L % 60_000L), 70.0 + 30 * sin(m / 20.0)) }
        )
        // Steps on the 5 minute grid
        viewModels.cache.stepsGraphFlow.value = StepsGraphData(
            (0 until 72).map { i -> GraphDataPoint(start + i * 300_000L + 123_456L, if (i % 7 == 0) 0.0 else (i * 37 % 500).toDouble()) }
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun render(traces: List<GraphTrace>) {
        val graph = viewModels.graphViewModel
        compose.setAapsContent(screen) {
            SecondaryGraphCompose(
                viewModel = graph,
                traces = traces,
                scrollState = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End),
                zoomState = rememberVicoZoomState(zoomEnabled = false),
                derivedTimeRange = start to end,
                nowTimestamp = end,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
        compose.waitForIdle()
    }

    @Test fun `heart rate with its ewma`() = render(listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA)))

    @Test fun `ewma first, then the series`() = render(listOf(GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA), GraphTrace(SeriesType.HEART_RATE)))

    @Test fun `steps bars with their rolling sum`() = render(listOf(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM, 60)))

    @Test fun `heart rate with a steps rolling sum`() = render(listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM, 60)))

    @Test fun `heart rate ewma with steps`() = render(listOf(GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA, 45), GraphTrace(SeriesType.STEPS)))

    @Test fun `a rolling sum alone`() = render(listOf(GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM, 60)))

    @Test fun `calculated traces as bars and points`() = render(
        listOf(
            GraphTrace(SeriesType.HEART_RATE, TraceTransform.ROLLING_MEAN, 30, style = PlotStyle.BARS),
            GraphTrace(SeriesType.STEPS, TraceTransform.EWMA, 30, style = PlotStyle.POINTS)
        )
    )

    @Test fun `heart rate as bars`() = render(listOf(GraphTrace(SeriesType.HEART_RATE, style = PlotStyle.BARS)))

    @Test fun `ewma over deviations`() = render(listOf(GraphTrace(SeriesType.DEVIATIONS), GraphTrace(SeriesType.DEVIATIONS, TraceTransform.EWMA)))

    @Test
    fun `changing the traces while the graph is shown`() {
        val graph = viewModels.graphViewModel
        var traces by mutableStateOf(listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS)))
        compose.setAapsContent(screen) {
            SecondaryGraphCompose(
                viewModel = graph,
                traces = traces,
                scrollState = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End),
                zoomState = rememberVicoZoomState(zoomEnabled = false),
                derivedTimeRange = start to end,
                nowTimestamp = end,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
        compose.waitForIdle()
        val steps = listOf(
            listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS, TraceTransform.EWMA)),
            listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM)),
            listOf(GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA), GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM)),
            listOf(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.STEPS, TraceTransform.ROLLING_SUM)),
            listOf(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.STEPS, TraceTransform.EWMA)),
            listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.HEART_RATE, TraceTransform.EWMA)),
            listOf(GraphTrace(SeriesType.HEART_RATE))
        )
        for (next in steps) {
            compose.runOnIdle { traces = next }
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }
    }
}
