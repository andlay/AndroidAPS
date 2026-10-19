package app.aaps.workflow

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivityHorizonTest {

    private val now = 1_000_000_000L
    private val fiveHours = T.hours(5).msecs()

    @Test
    fun `activity on the BG graph reaches now plus DIA`() {
        val graphs = GraphConfig(bgOverlays = listOf(SeriesType.ACTIVITY), iobOverlays = emptyList(), secondaryGraphs = emptyList())
        assertThat(activityHorizon(now, graphs, fiveHours)).isEqualTo(now + fiveHours)
    }

    @Test
    fun `activity on the IOB graph or a secondary graph counts too`() {
        val onIob = GraphConfig(bgOverlays = emptyList(), iobOverlays = listOf(SeriesType.ACTIVITY), secondaryGraphs = emptyList())
        val onSecondary = GraphConfig(bgOverlays = emptyList(), iobOverlays = emptyList(), secondaryGraphs = listOf(SecondaryGraph(listOf(SeriesType.ACTIVITY))))
        assertThat(activityHorizon(now, onIob, fiveHours)).isEqualTo(now + fiveHours)
        assertThat(activityHorizon(now, onSecondary, fiveHours)).isEqualTo(now + fiveHours)
    }

    @Test
    fun `no activity curve shown leaves the horizon alone`() {
        val graphs = GraphConfig(bgOverlays = listOf(SeriesType.PREDICTIONS), iobOverlays = emptyList(), secondaryGraphs = listOf(SecondaryGraph(listOf(SeriesType.COB))))
        assertThat(activityHorizon(now, graphs, fiveHours)).isNull()
    }

    @Test
    fun `unknown DIA leaves the horizon alone`() {
        assertThat(activityHorizon(now, GraphConfig(), null)).isNull()
        assertThat(activityHorizon(now, GraphConfig(), -1L)).isNull()
    }

    @Test
    fun `a long DIA is capped at what a prepare pass keeps`() {
        assertThat(activityHorizon(now, GraphConfig(), T.hours(8).msecs())).isEqualTo(now + T.hours(MAX_KEPT_HORIZON_HOURS).msecs())
    }
}
