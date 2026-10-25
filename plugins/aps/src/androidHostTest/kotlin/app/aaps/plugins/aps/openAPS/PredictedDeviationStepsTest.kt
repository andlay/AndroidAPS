package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.Predictions
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PredictedDeviationStepsTest {

    @Test
    fun `each curve is cut to the future steps of its prediction`() {
        val steps = PredictedDeviationSteps()
        steps.add(3.0, 4.0, 5.0)
        steps.add(2.0, 3.5, 4.5)
        steps.add(1.0, 3.0, 4.0)
        // The prediction starts with the run itself, so 3 points have 2 future steps
        val result = steps.build(Predictions(IOB = listOf(100, 110, 115), UAM = listOf(100, 112, 120, 126)))
        assertThat(result.IOB).containsExactly(3.0, 2.0).inOrder()
        assertThat(result.UAM).containsExactly(5.0, 4.5, 4.0).inOrder()
        assertThat(result.COB).isNull()
    }

    @Test
    fun `values are rounded and a value that is not a number becomes 0`() {
        val steps = PredictedDeviationSteps()
        steps.add(1.23456, Double.NaN, Double.POSITIVE_INFINITY)
        val result = steps.build(Predictions(IOB = listOf(1, 2), COB = listOf(1, 2), UAM = listOf(1, 2)))
        assertThat(result.IOB).containsExactly(1.23)
        assertThat(result.COB).containsExactly(0.0)
        assertThat(result.UAM).containsExactly(0.0)
    }

    @Test
    fun `steps stop at 4 hours and no predictions gives no curves`() {
        val steps = PredictedDeviationSteps()
        repeat(60) { steps.add(it.toDouble(), 0.0, 0.0) }
        assertThat(steps.build(Predictions(IOB = List(80) { 100 })).IOB).hasSize(PredictedDeviationSteps.MAX_STEPS)
        val none = steps.build(null)
        assertThat(none.IOB).isNull()
        assertThat(none.COB).isNull()
        assertThat(none.UAM).isNull()
    }
}
