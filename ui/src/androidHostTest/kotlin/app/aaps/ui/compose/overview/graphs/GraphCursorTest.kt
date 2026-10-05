package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class GraphCursorTest {

    private val minute = 60_000L

    @Test
    fun `canvas x and x-value convert both ways`() {
        val geometry = GraphGeometryHolder().apply {
            left = 40f
            right = 400f
            startPadding = 11f
            xSpacing = 2.5f
            minX = 0.0
            xStep = 1.0
            scroll = 300f
        }
        assertThat(geometry.isReady).isTrue()
        for (x in listOf(120.0, 150.5, 260.0)) {
            assertThat(geometry.xValueAt(geometry.canvasXOf(x))).isWithin(1e-6).of(x)
        }
        // left edge of the plot = (scroll - startPadding) / xSpacing minutes from the start
        assertThat(geometry.xValueAt(40f)).isWithin(1e-6).of((300.0 - 11.0) / 2.5)
    }

    @Test
    fun `geometry is not ready before the first draw`() {
        assertThat(GraphGeometryHolder().isReady).isFalse()
    }

    @Test
    fun `nearest point is used only when close enough`() {
        val points = listOf(GraphDataPoint(0L, 1.0), GraphDataPoint(5 * minute, 2.0), GraphDataPoint(10 * minute, 3.0))
        assertThat(points.nearestTo(6 * minute)?.value).isEqualTo(2.0)
        assertThat(points.nearestTo(10 * minute + 3 * minute)?.value).isEqualTo(3.0)
        assertThat(points.nearestTo(10 * minute + 4 * minute)).isNull()
        assertThat(emptyList<GraphDataPoint>().nearestTo(0L)).isNull()
    }

    @Test
    fun `step series gives the value in effect, not the nearest one`() {
        // basal 0.8 from 0, zero temp from 30 min, back to 0.8 from 60 min
        val basal = listOf(GraphDataPoint(0L, 0.8), GraphDataPoint(30 * minute, 0.0), GraphDataPoint(60 * minute, 0.8))
        assertThat(basal.valueInEffectAt(29 * minute)).isEqualTo(0.8)
        assertThat(basal.valueInEffectAt(30 * minute)).isEqualTo(0.0)
        assertThat(basal.valueInEffectAt(59 * minute)).isEqualTo(0.0)
        assertThat(basal.valueInEffectAt(-1 * minute)).isNull()
    }

    @Test
    fun `events are stops of their own between readings`() {
        val readings = listOf(0L, 5 * minute, 10 * minute)
        val smbs = listOf(2 * minute, 7 * minute)
        val stops = cursorStops(readings, smbs)
        assertThat(stops).containsExactly(0L, 2 * minute, 5 * minute, 7 * minute, 10 * minute).inOrder()
        // dragging past 2 minutes lands on the SMB, not on a reading
        assertThat(stops.nearestStop(2 * minute + 20_000)).isEqualTo(2 * minute)
        assertThat(stops.nearestStop(4 * minute)).isEqualTo(5 * minute)
    }

    @Test
    fun `an event at the time of a reading is one stop`() {
        assertThat(cursorStops(listOf(0L, 5 * minute), listOf(5 * minute))).containsExactly(0L, 5 * minute).inOrder()
        assertThat(emptyList<Long>().nearestStop(0L)).isNull()
    }

    @Test
    fun `rows follow the lines from top to bottom and swap when they cross`() {
        fun order(iob: Double, activity: Double) = listOf(
            ScreenOrderItem("IOB", pinFirst = false, height = iob),
            ScreenOrderItem("BAS", pinFirst = true, height = 0.8),
            ScreenOrderItem("ACT", pinFirst = false, height = activity)
        ).inScreenOrder()
        assertThat(order(iob = 2.0, activity = 1.0)).containsExactly("BAS", "IOB", "ACT").inOrder()
        assertThat(order(iob = 1.0, activity = 2.0)).containsExactly("BAS", "ACT", "IOB").inOrder()
    }

    @Test
    fun `rows without a value go last in their usual order`() {
        val rows = listOf(
            ScreenOrderItem("BG", pinFirst = false, height = null),
            ScreenOrderItem("UAM", pinFirst = false, height = 6.0),
            ScreenOrderItem("TARG", pinFirst = false, height = null),
            ScreenOrderItem("IOB", pinFirst = false, height = 8.0)
        )
        assertThat(rows.inScreenOrder()).containsExactly("IOB", "UAM", "BG", "TARG").inOrder()
    }

    @Test
    fun `BG axis follows the visible readings, not the whole day`() {
        fun bg(t: Long, v: Double) = BgDataPoint(t, v, BgRange.IN_RANGE, BgType.BUCKETED)
        val day = listOf(bg(0L, 14.1), bg(60 * minute, 7.0), bg(65 * minute, 8.0))
        // whole day: the 14.1 high sets the top
        assertThat(bgAxisScale(day, null, 3.9, 10.0).max).isAtLeast(14.1)
        // only the last hour is visible: the top drops back to the high mark range
        val visible = bgAxisScale(day, 50 * minute to 70 * minute, 3.9, 10.0)
        assertThat(visible.max).isLessThan(14.1)
        assertThat(visible.max).isAtLeast(10.0)
        // a window with no readings falls back to all of them
        assertThat(bgAxisScale(day, 200 * minute to 300 * minute, 3.9, 10.0).max).isAtLeast(14.1)
    }
}
