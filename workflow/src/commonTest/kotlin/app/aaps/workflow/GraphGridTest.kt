package app.aaps.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphGridTest {

    private fun min(m: Long) = m * 60_000L

    @Test
    fun theGridPassesThroughTheNewestReading() {
        // Graph from 08:01:40, newest reading 11:34
        val from = min(8 * 60 + 1) + 40_000L
        val newest = min(11 * 60 + 34)
        val start = gridStartThrough(from, min(13 * 60), newest)

        assertEquals(0L, (newest - start) % GRAPH_STEP_MS)
        // Starts as early as it can, but not before the graph does
        assertTrue(start >= from && start - GRAPH_STEP_MS < from)
    }

    @Test
    fun withoutAReadingInTheRangeTheGridStartsAtTheGraphStart() {
        assertEquals(min(60), gridStartThrough(min(60), min(120), null))
        assertEquals(min(60), gridStartThrough(min(60), min(120), min(30)))
        assertEquals(min(60), gridStartThrough(min(60), min(120), min(150)))
    }
}
