package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovedSecondaryGraphTest {

    private val cob = SecondaryGraph(listOf(SeriesType.COB))
    private val dev = SecondaryGraph(listOf(SeriesType.DEVIATIONS, SeriesType.BGI), height = 150)
    private val hr = SecondaryGraph(listOf(SeriesType.HEART_RATE))
    private val config = GraphConfig(secondaryGraphs = listOf(cob, dev, hr))

    @Test
    fun aGraphMovesUpAndDownWithItsSettings() {
        assertEquals(listOf(dev, cob, hr), movedSecondaryGraph(config, 1, 0)?.secondaryGraphs)
        assertEquals(listOf(cob, hr, dev), movedSecondaryGraph(config, 1, 2)?.secondaryGraphs)
    }

    @Test
    fun theFirstCannotGoUpAndTheLastCannotGoDown() {
        assertNull(movedSecondaryGraph(config, 0, -1))
        assertNull(movedSecondaryGraph(config, 2, 3))
        assertNull(movedSecondaryGraph(config, 1, 1))
    }
}
