package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AutoIsfFactorPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The values of the extra graphs in the pop-up, and the time under a finger on another graph. */
class GraphInspectExtrasTest {

    private fun at(minutes: Int): Long = minutes * 60_000L

    private fun sources(
        heartRate: List<GraphDataPoint> = emptyList(),
        ratio: List<GraphDataPoint> = emptyList(),
        dsMax: List<GraphDataPoint> = emptyList(),
        dsMin: List<GraphDataPoint> = emptyList(),
        varSens: List<GraphDataPoint> = emptyList(),
        autoIsf: List<AutoIsfFactorPoint> = emptyList(),
        steps: List<GraphDataPoint> = emptyList()
    ) = InspectSources(
        absIob = emptyList(), activity = emptyList(), bgi = emptyList(), deviations = emptyList(), devCarbs = emptyList(),
        ratio = ratio, dsMax = dsMax, dsMin = dsMin, varSens = varSens, autoIsf = autoIsf, heartRate = heartRate, steps = steps
    )

    @Test
    fun theNearestValueWithinFiveMinutesIsShown() {
        val hr = listOf(GraphDataPoint(at(0), 60.0), GraphDataPoint(at(10), 70.0))

        assertEquals(listOf(InspectExtra.Value(SeriesType.HEART_RATE, 70.0)), inspectExtrasAt(at(8), listOf(SeriesType.HEART_RATE), sources(heartRate = hr)))
        // Nothing within 5 minutes: left out
        assertTrue(inspectExtrasAt(at(30), listOf(SeriesType.HEART_RATE), sources(heartRate = hr)).isEmpty())
    }

    @Test
    fun eachSeriesIsShownOnceInTheOrderOfTheGraphs() {
        val result = inspectExtrasAt(
            at(0),
            listOf(SeriesType.STEPS, SeriesType.HEART_RATE, SeriesType.STEPS, SeriesType.IOB),
            sources(heartRate = listOf(GraphDataPoint(at(0), 65.0)), steps = listOf(GraphDataPoint(at(0), 120.0)))
        )

        assertEquals(listOf(InspectExtra.Value(SeriesType.STEPS, 120.0), InspectExtra.Value(SeriesType.HEART_RATE, 65.0)), result)
    }

    @Test
    fun seriesWithTheirOwnRules() {
        val result = inspectExtrasAt(
            at(10),
            listOf(SeriesType.SENSITIVITY, SeriesType.DEV_SLOPE, SeriesType.VAR_SENSITIVITY),
            sources(
                ratio = listOf(GraphDataPoint(at(10), 10.0)),
                dsMax = listOf(GraphDataPoint(at(10), 0.4)),
                dsMin = listOf(GraphDataPoint(at(10), -0.2)),
                // A loop run stores it; the run before the time counts even 9 minutes earlier
                varSens = listOf(GraphDataPoint(at(1), 2.6), GraphDataPoint(at(30), 3.0))
            )
        )

        assertEquals(
            listOf(
                InspectExtra.Value(SeriesType.SENSITIVITY, 110.0),
                InspectExtra.Value(SeriesType.DEV_SLOPE, 0.4),
                InspectExtra.Value(SeriesType.DEV_SLOPE, -0.2, isMin = true),
                InspectExtra.Value(SeriesType.VAR_SENSITIVITY, 2.6)
            ),
            result
        )
    }

    @Test
    fun autoIsfShowsAllFactorsOfTheNearestRun() {
        val run = AutoIsfFactorPoint(at(5), acce = 1.2, bg = 1.05, pp = 1.0, dura = 1.0, applied = 1.2)

        assertEquals(listOf(InspectExtra.AutoIsf(run)), inspectExtrasAt(at(6), listOf(SeriesType.AUTOISF), sources(autoIsf = listOf(run))))
        assertEquals(1.05, AutoIsfLine.BG.valueAt(run))
        assertEquals(1.2, AutoIsfLine.APPLIED.valueAt(run))
    }

    @Test
    fun theBgFactorShowsATargetBelowTarget() {
        val above = AutoIsfFactorPoint(at(0), acce = 1.0, bg = 1.05, pp = 1.0, dura = 1.0, applied = 1.05)
        val below = above.copy(bg = 0.94, applied = 0.94)

        assertEquals("🎚️", AutoIsfLine.BG.emojiAt(above))
        assertEquals("🎯", AutoIsfLine.BG.emojiAt(below))
        assertEquals("⤴️", AutoIsfLine.ACCE.emojiAt(below))
    }

    @Test
    fun aFingerOnAnotherGraphGivesTheTimeUnderIt() {
        val geometry = GraphGeometryHolder()
        assertNull(geometry.xAt(100f)) // not drawn yet
        geometry.left = 40f
        geometry.startPadding = 10f
        geometry.xSpacing = 20f // 20 px per 5 minutes
        geometry.xStep = 5.0
        geometry.minX = 0.0
        geometry.scroll = 200f

        // 40 + 10 is the start; scrolled by 200 px, so 250 px in: 250 / 20 * 5 minutes
        assertEquals(62.5, geometry.xAt(50f + 50f))
    }
}
