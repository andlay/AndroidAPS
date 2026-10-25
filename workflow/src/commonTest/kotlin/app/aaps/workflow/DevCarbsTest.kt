package app.aaps.workflow

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DEV carbs running total: where runs start and end, and what is counted. */
class DevCarbsTest {

    private fun at(minutes: Int): Long = minutes * 60_000L

    /** A reading every 5 minutes, with these grams of carbs each. */
    private fun steps(vararg carbs: Double): List<DevCarbsStep> =
        carbs.mapIndexed { i, g -> DevCarbsStep(at(i * 5), at(i * 5), g) }

    private fun point(minutes: Int, carbs: Double) = GraphDataPoint(at(minutes), carbs)

    @Test
    fun oneReadingThatJumpsDownAndBackDoesNotEndTheRun() {
        // The jump down is taken off the total and the jump back adds it again
        val result = devCarbsRuns(steps(5.0, 5.0, -4.0, 4.0, 2.0))

        assertEquals(listOf(listOf(point(-5, 0.0), point(0, 5.0), point(5, 10.0), point(10, 6.0), point(15, 10.0), point(20, 12.0))), result)
    }

    @Test
    fun aTurnEndsTheRunAtItsPeak() {
        // Back by more than 3 g at two readings in a row: the new run starts at the peak and has the move since then
        val result = devCarbsRuns(steps(5.0, 5.0, -4.0, -4.0, -1.0))

        assertEquals(
            listOf(
                listOf(point(-5, 0.0), point(0, 5.0), point(5, 10.0)),
                listOf(point(5, 0.0), point(10, -4.0), point(15, -8.0), point(20, -9.0))
            ),
            result
        )
    }

    @Test
    fun smallDeviationsAreCounted() {
        val result = devCarbsRuns(steps(1.0, 1.0, 1.0, 1.0, 1.0))

        assertEquals(listOf(listOf(point(-5, 0.0), point(0, 1.0), point(5, 2.0), point(10, 3.0), point(15, 4.0), point(20, 5.0))), result)
    }

    @Test
    fun noRunWhileTheTotalHardlyMoves() {
        assertTrue(devCarbsRuns(steps(1.0, -1.0, 2.0, -2.0, 1.0)).isEmpty())
    }

    @Test
    fun aLongGapEndsTheRunAndItsFilledReadingsAreNotCounted() {
        val result = devCarbsRuns(
            listOf(
                DevCarbsStep(at(0), at(0), 5.0),
                DevCarbsStep(at(5), at(5), 5.0),
                DevCarbsStep(at(10), at(10), -10.0, filled = true),
                DevCarbsStep(at(15), at(15), -10.0, filled = true),
                DevCarbsStep(at(20), at(20), -10.0, filled = true),
                DevCarbsStep(at(25), at(25), 5.0),
                DevCarbsStep(at(30), at(30), 5.0)
            )
        )

        assertEquals(
            listOf(
                listOf(point(-5, 0.0), point(0, 5.0), point(5, 10.0)),
                listOf(point(20, 0.0), point(25, 5.0), point(30, 10.0))
            ),
            result
        )
    }

    @Test
    fun oneOrTwoMissingReadingsAreBridged() {
        val result = devCarbsRuns(
            listOf(
                DevCarbsStep(at(0), at(0), 5.0),
                DevCarbsStep(at(5), at(5), 5.0),
                DevCarbsStep(at(10), at(10), 2.0, filled = true),
                DevCarbsStep(at(15), at(15), 2.0, filled = true),
                DevCarbsStep(at(20), at(20), 1.0)
            )
        )

        assertEquals(listOf(listOf(point(-5, 0.0), point(0, 5.0), point(5, 10.0), point(10, 12.0), point(15, 14.0), point(20, 15.0))), result)
    }

    @Test
    fun aGapInTheStepsEndsTheRunToo() {
        val result = devCarbsRuns(
            listOf(
                DevCarbsStep(at(0), at(0), 5.0),
                DevCarbsStep(at(5), at(5), 5.0),
                DevCarbsStep(at(30), at(30), 5.0),
                DevCarbsStep(at(35), at(35), 5.0)
            )
        )

        assertEquals(2, result.size)
        assertEquals(point(25, 0.0), result[1].first())
    }

    @Test
    fun aReadingIsCountedOnce() {
        // The graph steps every 5 minutes; a late reading gives two steps the same reading
        val result = devCarbsRuns(
            listOf(
                DevCarbsStep(at(0), at(0), 5.0),
                DevCarbsStep(at(5), at(0), 5.0),
                DevCarbsStep(at(10), at(9), 5.0),
                DevCarbsStep(at(15), at(14), 5.0)
            )
        )

        assertEquals(listOf(listOf(point(-5, 0.0), point(0, 5.0), point(10, 10.0), point(15, 15.0))), result)
    }
}
