package app.aaps.ui.compose.overview.graphs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.ui.compose.testing.AapsScreenFixture
import app.aaps.ui.compose.testing.setAapsContent
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the graph edit sheet the way a user adds a calculated trace: the "add" button, the
 * calculation chips and the window field. Each change goes back into the sheet, like the overview does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GraphTracesBottomSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var screen: AapsScreenFixture
    private lateinit var context: Context
    private var latest: List<GraphTrace> = emptyList()

    @Before
    fun setUp() {
        screen = AapsScreenFixture()
        context = RuntimeEnvironment.getApplication()
    }

    private fun text(id: Int) = context.getString(id)

    private fun show(initial: List<GraphTrace>) {
        latest = initial
        compose.setAapsContent(screen) {
            var traces by remember { mutableStateOf(initial) }
            GraphTracesBottomSheet(
                title = "Graph 3",
                traces = traces,
                availableSeries = SeriesType.entries.filter { it != SeriesType.IOB && it != SeriesType.PREDICTIONS },
                height = 100,
                onHeightChange = {},
                onTracesChange = { traces = it; latest = it },
                onDismiss = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `adding an ewma and changing it to a rolling sum`() {
        show(listOf(GraphTrace(SeriesType.STEPS)))

        compose.onNodeWithText(text(app.aaps.ui.R.string.graph_trace_add)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(latest).containsExactly(GraphTrace(SeriesType.STEPS), GraphTrace(SeriesType.STEPS, TraceTransform.EWMA)).inOrder()

        compose.onAllNodesWithText(text(app.aaps.ui.R.string.graph_transform_sum))[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(latest[1].transform).isEqualTo(TraceTransform.ROLLING_SUM)
    }

    @Test
    fun `turning the second of two series into an ewma`() {
        show(listOf(GraphTrace(SeriesType.HEART_RATE), GraphTrace(SeriesType.STEPS)))

        // Open the options of the steps trace, then pick EWMA
        compose.onAllNodesWithText(context.getString(app.aaps.core.ui.R.string.steps_shortname))[1].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(app.aaps.ui.R.string.graph_transform_ewma)).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(latest[1]).isEqualTo(GraphTrace(SeriesType.STEPS, TraceTransform.EWMA))
    }
}
