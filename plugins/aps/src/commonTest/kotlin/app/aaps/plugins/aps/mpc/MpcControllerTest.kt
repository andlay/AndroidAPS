package app.aaps.plugins.aps.mpc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MpcControllerTest {

    private val settings = MpcController.Settings()
    private val noInsulin = DoubleArray(MpcController.HORIZON + 1)
    private val noPast = DoubleArray(25)

    /** 26 BG values, newest first, from a function of minutes ago */
    private fun view(bg: (Int) -> Double) = DoubleArray(26) { j -> bg(j * 5) }

    @Test
    fun flatBgAtTargetGivesNoSmbAndNoMeal() {
        val c = MpcController()
        val d = c.step(1000, view { 100.0 }, noPast, noInsulin, iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = settings)
        assertEquals(0.0, d.units)
        assertEquals(0.0, d.mealChance)
        assertTrue(d.futures.meal.all { it == 0.0 })
    }

    @Test
    fun risingBgGivesAnSmb() {
        val c = MpcController()
        // flat at 110 until 30 min ago, then rising faster and faster: a meal starting
        val d = c.step(1000, view { m -> if (m >= 30) 110.0 else 110.0 + (30 - m) * (30 - m) / 15.0 }, noPast, noInsulin,
            iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = settings)
        assertTrue(d.units > 0.0, "units ${d.units}")
        assertTrue(d.futures.meal.sum() > d.futures.non.sum())
    }

    @Test
    fun noSmbBelowSmbMinimumBg() {
        val c = MpcController()
        val d = c.step(1000, view { 75.0 }, noPast, noInsulin, iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = settings)
        assertEquals(0.0, d.units)
        assertEquals(0.0, d.rate)   // below the low line with nothing coming: zero temp
    }

    @Test
    fun noSmbAboveMaxIob() {
        val c = MpcController()
        val d = c.step(1000, view { m -> 200.0 + (30 - minOf(m, 30)) }, noPast, noInsulin,
            iob = 5.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = settings)
        assertEquals(0.0, d.units)
    }

    @Test
    fun secondCallForTheSameStepReusesTheFutures() {
        val c = MpcController()
        val v = view { m -> if (m >= 30) 110.0 else 110.0 + (30 - m) * (30 - m) / 15.0 }
        val a = c.futures(1000, v, noPast, 46.8)
        val b = c.futures(1000, v, noPast, 46.8)
        assertSame(a, b)
    }

    @Test
    fun insulinCurveGivesOneUnitInTotal() {
        // the activity of 1 U summed over its life is 1 U
        var sum = 0.0
        var t = 0.0
        while (t < MpcController.CURVE.td) {
            sum += MpcController.CURVE.activity(1.0, t + 0.5); t += 1.0
        }
        assertTrue(abs(sum - 1.0) < 0.01, "sum $sum")
    }

    @Test
    fun steadyBgStepsTheLinesDownAndAChangePutsThemBack() {
        val c = MpcController()
        val withStepDown = MpcController.Settings(stepDownMax = 27.0)
        val shifts = (0L..20L).map { k ->
            c.step(1000 + k, view { 140.0 }, noPast, noInsulin, iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = withStepDown).stepDown
        }
        // 9 mg/dL after each 30 steady minutes, never more than the largest step
        assertEquals(listOf(0.0, 9.0, 18.0, 27.0, 27.0), listOf(shifts[0], shifts[6], shifts[12], shifts[18], shifts[20]))
        // BG starts to move: straight back
        val d = c.step(1021, view { m -> if (m >= 30) 140.0 else 140.0 + (30 - m) * 2.0 }, noPast, noInsulin,
            iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = withStepDown)
        assertEquals(0.0, d.stepDown)
    }

    @Test
    fun noStepDownWhenOff() {
        val c = MpcController()
        val shifts = (0L..20L).map { k ->
            c.step(1000 + k, view { 140.0 }, noPast, noInsulin, iob = 0.0, basal = 0.8, target = 100.0, isfProfile = 46.8, settings = settings).stepDown
        }
        assertTrue(shifts.all { it == 0.0 })
    }
}
