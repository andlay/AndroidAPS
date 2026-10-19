package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class FutureActivityPeakTest {

    private val now = 1_000_000L
    private val step = 5 * 60_000L
    private fun points(vararg values: Double) = values.mapIndexed { i, v -> GraphDataPoint(now + (i + 1) * step, v) }

    @Test
    fun `rising activity gives the time of its highest point`() {
        assertThat(futureActivityPeak(points(0.01, 0.02, 0.03, 0.025, 0.01), now)).isEqualTo(now + 3 * step)
    }

    @Test
    fun `falling activity has no future peak`() {
        assertThat(futureActivityPeak(points(0.03, 0.02, 0.01), now)).isNull()
    }

    @Test
    fun `no insulin acting has no peak`() {
        assertThat(futureActivityPeak(points(0.0, 0.0, 0.0), now)).isNull()
    }

    @Test
    fun `points at or before now are ignored`() {
        val past = listOf(GraphDataPoint(now - step, 0.5), GraphDataPoint(now, 0.4))
        assertThat(futureActivityPeak(past + points(0.01, 0.02, 0.015), now)).isEqualTo(now + 2 * step)
    }

    @Test
    fun `fewer than two future points give no peak`() {
        assertThat(futureActivityPeak(points(0.02), now)).isNull()
        assertThat(futureActivityPeak(emptyList(), now)).isNull()
    }
}
