package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `each event belongs to exactly one point`() {
        val readings = listOf(0L, 5 * minute, 10 * minute, 25 * minute) // a 15 minute gap before the last one
        val events = listOf(-2 * minute, 0L, 1 * minute, 5 * minute, 7 * minute, 11 * minute, 24 * minute, 26 * minute)
        val owners = events.associateWith { event ->
            readings.filter { point ->
                val (from, to) = eventBucketBounds(readings, point)
                event > from && event <= to
            }
        }
        owners.forEach { (event, points) -> assertWithMessage("owners of event at $event").that(points).hasSize(1) }
        // an event exactly on a reading belongs to that reading, an event just after it to the next one
        assertThat(owners[5 * minute]).containsExactly(5 * minute)
        assertThat(owners[7 * minute]).containsExactly(10 * minute)
        // events in a gap go to the reading that ends the gap
        assertThat(owners[11 * minute]).containsExactly(25 * minute)
        // an event after the newest reading is not lost
        assertThat(owners[26 * minute]).containsExactly(25 * minute)
    }

    @Test
    fun `first point gets a 5 minute bucket`() {
        val (from, to) = eventBucketBounds(listOf(0L, 5 * minute), 0L)
        assertThat(from).isEqualTo(-5 * minute)
        assertThat(to).isEqualTo(0L)
    }

    @Test
    fun `a time that is not a point gets the 5 minutes before it`() {
        val (from, to) = eventBucketBounds(emptyList(), 20 * minute)
        assertThat(from).isEqualTo(15 * minute)
        assertThat(to).isEqualTo(20 * minute)
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
}
