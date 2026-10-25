package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class BgAxisScaleTest {

    private val minute = 60_000L

    @Test
    fun `BG axis follows the visible readings, not the whole day`() {
        fun bg(t: Long, v: Double) = BgDataPoint(t, v, BgRange.IN_RANGE, BgType.BUCKETED)
        val day = listOf(bg(0L, 14.1), bg(60 * minute, 7.0), bg(65 * minute, 8.0))
        // whole day: the 14.1 high sets the top
        assertThat(bgAxisScale(day, null, 3.9, 10.0).max).isAtLeast(14.1)
        // the default 6 h window: the 14.1 high is out of view, the top drops back to the high mark
        val sixHours = bgAxisScale(day, 50 * minute to 410 * minute, 3.9, 10.0)
        assertThat(sixHours.max).isLessThan(14.1)
        assertThat(sixHours.max).isAtLeast(10.0)
        assertThat(sixHours.min).isAtMost(3.9)
        // a window with no readings falls back to all of them
        assertThat(bgAxisScale(day, 200 * minute to 300 * minute, 3.9, 10.0).max).isAtLeast(14.1)
    }

    @Test
    fun `BG axis fits the visible readings when zoomed in`() {
        fun bg(t: Long, v: Double) = BgDataPoint(t, v, BgRange.IN_RANGE, BgType.BUCKETED)
        val flat = listOf(bg(0L, 5.2), bg(5 * minute, 5.5), bg(10 * minute, 5.8))
        // 30 minutes visible: the axis no longer has to reach the 3.9 and 10.0 marks
        val zoomed = bgAxisScale(flat, 0L to 30 * minute, 3.9, 10.0)
        assertThat(zoomed.min).isGreaterThan(3.9)
        assertThat(zoomed.max).isLessThan(10.0)
        assertThat(zoomed.min).isAtMost(5.2)
        assertThat(zoomed.max).isAtLeast(5.8)
        // but it is never smaller than a quarter of the target range, so 0.6 of noise does not fill the graph
        assertThat(zoomed.max - zoomed.min).isAtLeast((10.0 - 3.9) / 4)
    }
}
