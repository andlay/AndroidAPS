package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.aps.Predictions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictionPointsTest {

    private val start = 1_000_000L
    private val step = 5 * 60 * 1000L

    @Test
    fun `point 0 and values below 40 are left out, the rest are 5 minutes apart`() {
        val points = predictionPoints(Predictions(IOB = listOf(100, 110, 39, 130)), start, afterDose = false, lowMark = 70.0, highMark = 120.0) { it }
        assertEquals(listOf(start + step, start + 3 * step), points.map { it.timestamp })
        assertEquals(listOf(110.0, 130.0), points.map { it.value })
        assertEquals(listOf(BgRange.IN_RANGE, BgRange.HIGH), points.map { it.range })
        assertTrue(points.all { it.type == BgType.IOB_PREDICTION && !it.afterDose })
    }

    @Test
    fun `each curve keeps its type and the after dose flag`() {
        val points = predictionPoints(Predictions(UAM = listOf(100, 101), ZT = listOf(100, 60)), start, afterDose = true, lowMark = 70.0, highMark = 180.0) { it / 18.0 }
        assertEquals(listOf(BgType.UAM_PREDICTION, BgType.ZT_PREDICTION), points.map { it.type })
        assertTrue(points.all { it.afterDose })
        assertEquals(BgRange.LOW, points.last().range)
    }
}
