package app.aaps.plugins.aps.mpc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MpcControllerTest {

    private val settings = MpcController.MealSmbSettings(target = 100.0, maxSmb = 1.6, maxIob = 5.0)
    private val noInsulin = DoubleArray(MpcController.HORIZON + 1)
    private val noPast = DoubleArray(25)

    /** 26 BG values, newest first, from a function of minutes ago */
    private fun view(bg: (Int) -> Double) = DoubleArray(26) { j -> bg(j * 5) }

    /** Flat at 110 until 30 min ago, then rising faster and faster: a meal starting */
    private val mealStarting = view { m -> if (m >= 30) 110.0 else 110.0 + (30 - m) * (30 - m) / 15.0 }

    private fun mealSmb(
        v: DoubleArray, iob: Double = 0.0, smbAllowed: Boolean = true, s: MpcController.MealSmbSettings = settings
    ) = MpcController().mealSmb(1000, v, noPast, noInsulin, iob = iob, basal = 0.8, tempRate = 0.8, isf = 46.8, smbAllowed = smbAllowed, settings = s)

    @Test
    fun flatBgAtTargetSeesNoMealAndGivesNoSmb() {
        val d = mealSmb(view { 100.0 })
        assertFalse(d.mealSeen)
        assertEquals(0.0, d.units)
        assertEquals(0.0, d.mealChance)
        assertTrue(d.options.isEmpty())
        assertTrue(d.futures.meal.all { it == 0.0 })
    }

    @Test
    fun risingBgGivesAnSmb() {
        val d = mealSmb(mealStarting)
        assertTrue(d.mealSeen)
        assertTrue(d.units > 0.0, "units ${d.units}")
        assertTrue(d.futures.meal.sum() > d.futures.non.sum())
        assertEquals(d.units, d.options.first().units)
    }

    @Test
    fun noSmbWhenVanillaDoesNotAllowOne() {
        val d = mealSmb(mealStarting, smbAllowed = false)
        assertTrue(d.mealSeen)
        assertEquals(0.0, d.units)
    }

    @Test
    fun noSmbAboveMaxIob() {
        val d = mealSmb(mealStarting, iob = 5.0)
        assertEquals(0.0, d.units)
    }

    @Test
    fun smbStaysWithinTheLargestSmbAndMaxIob() {
        val small = MpcController.MealSmbSettings(target = 100.0, maxSmb = 0.3, maxIob = 5.0)
        assertTrue(mealSmb(mealStarting, s = small).units <= 0.3 + 1e-9)
        assertTrue(mealSmb(mealStarting, iob = 4.8).units <= 0.2 + 1e-9)
    }

    @Test
    fun highBgCountsAsAMealSeen() {
        val d = mealSmb(view { 200.0 })
        assertTrue(d.mealSeen)
    }

    @Test
    fun secondCallForTheSameStepReusesTheFutures() {
        val c = MpcController()
        val a = c.futures(1000, mealStarting, noPast, 46.8)
        val b = c.futures(1000, mealStarting, noPast, 46.8)
        assertSame(a, b)
    }

    @Test
    fun aRisingImpactHasMealSizeScenarios() {
        val f = MpcController().futures(1000, mealStarting, noPast, 46.8)
        assertEquals(MpcController.SCENARIOS, f.scenarios?.size)
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
}
