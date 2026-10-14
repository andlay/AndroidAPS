package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.CarbsAbsorbedPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two axes on one graph meet at zero, and the carbs absorbed forecast joins the past bars. */
class GraphZeroAlignTest {

    private fun zeroFraction(min: Double, max: Double) = -min / (max - min)

    @Test
    fun bothZerosAreAtTheSameHeightAfterTheLeftAxisIsRounded() {
        // COB 0..16 on the left, carbs absorbed hanging 0..-2.7 on the right: the case on the phone
        val ranges = alignedToNicePrimary(0.0, 16.0, -2.7, 0.1, targetFraction = 0.48)

        assertTrue(abs(zeroFraction(ranges.aMin, ranges.aMax) - zeroFraction(ranges.bMin, ranges.bMax)) < 1e-9)
        // Nothing is cut off
        assertTrue(ranges.aMax >= 16.0 && ranges.bMin <= -2.7)
    }

    @Test
    fun theForecastStartsFiveMinutesAfterTheNewestReading() {
        val lastPast = 1_000_000L
        // The loop ran 2 minutes after the reading, so the forecast came 7 minutes after it
        val forecast = listOf(CarbsAbsorbedPoint(lastPast + 7 * 60_000L, 1.0, false), CarbsAbsorbedPoint(lastPast + 12 * 60_000L, 0.5, false))

        val retimed = forecastAfter(lastPast, forecast)

        assertEquals(listOf(lastPast + 5 * 60_000L, lastPast + 10 * 60_000L), retimed.map { it.timestamp })
        assertEquals(listOf(1.0, 0.5), retimed.map { it.grams })
    }

    @Test
    fun anOldForecastIsLeftAsItIs() {
        val forecast = listOf(CarbsAbsorbedPoint(10_000_000L, 1.0, true))

        assertEquals(forecast, forecastAfter(10_000_000L - 60 * 60_000L, forecast))
    }
}
