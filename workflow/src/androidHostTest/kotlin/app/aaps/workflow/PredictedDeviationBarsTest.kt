package app.aaps.workflow

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.predictedDeviationBars
import app.aaps.core.interfaces.overview.graph.predictedDeviationsStart
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PredictedDeviationBarsTest {

    private val minute = 60_000L
    private val run = 1_000_000_000L

    @Test
    fun `bars start at the BG reading of the run, where the measured bars end`() {
        assertThat(predictedDeviationsStart(run, run - 2 * minute)).isEqualTo(run - 2 * minute)
    }

    @Test
    fun `bars start at the run when the reading is missing, old or after it`() {
        assertThat(predictedDeviationsStart(run, null)).isEqualTo(run)
        assertThat(predictedDeviationsStart(run, run - 15 * minute)).isEqualTo(run)
        assertThat(predictedDeviationsStart(run, run + minute)).isEqualTo(run)
    }

    @Test
    fun `step i is a bar from 5 i minutes, closed by a last point`() {
        val bars = predictedDeviationBars(listOf(18.0, 9.0), run) { it / 18.0 }
        assertThat(bars).containsExactly(
            GraphDataPoint(run, 1.0),
            GraphDataPoint(run + 5 * minute, 0.5),
            GraphDataPoint(run + 10 * minute, 0.5)
        ).inOrder()
    }

    @Test
    fun `no steps give no bars`() {
        assertThat(predictedDeviationBars(null, run) { it }).isEmpty()
        assertThat(predictedDeviationBars(emptyList(), run) { it }).isEmpty()
    }
}
