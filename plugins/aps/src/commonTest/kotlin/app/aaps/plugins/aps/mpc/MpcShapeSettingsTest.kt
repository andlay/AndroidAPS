package app.aaps.plugins.aps.mpc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MpcShapeSettingsTest {

    private fun close(a: Double, b: Double, tol: Double) = assertTrue(abs(a - b) <= tol * maxOf(1.0, abs(b)), "$a != $b")

    @Test
    fun defaultsAreTheFittedShapeTwentyPercentHigherAndLonger() {
        // the two meal settings at their defaults, with the fixed rest of the shape: the fitted model with the
        // carb effect 20 % higher and 20 % longer (ISF and carb ratio of the replay)
        val f = MpcModel.Shape.FITTED
        val fixed = MpcMealSmb.FIXED_SHAPE
        close(MpcDoubleKey.TypicalMeal.defaultValue, fixed.typicalGrams * 1.44, 1e-3)
        close(MpcDoubleKey.RiseLength.defaultValue, fixed.typicalMinutes * 1.2, 1e-3)
        val readable = MpcShapeSettings(
            peakPosition = fixed.peakPosition,
            halfWidth = fixed.halfWidth,
            typicalMinutes = MpcDoubleKey.RiseLength.defaultValue,
            lengthSpreadPct = fixed.lengthSpreadPct,
            typicalGrams = MpcDoubleKey.TypicalMeal.defaultValue,
            sizeSpreadPct = fixed.sizeSpreadPct,
            readinessPct = fixed.readinessPct,
            tailHalfLifeMinutes = fixed.tailHalfLifeMinutes
        )
        val s = readable.toShape(isf = 46.8, ic = 13.92)
        close(s.a, f.a, 1e-6); close(s.b, f.b, 1e-6)
        close(s.height, f.height * 1.2, 1e-3); close(s.heightSd, f.heightSd, 1e-9)
        close(s.length, f.length * 1.2, 1e-3); close(s.lengthSd, f.lengthSd, 1e-9)
        close(s.mealBias, f.mealBias, 1e-9); close(s.tailTau, f.tailTau, 1e-9)
    }

    @Test
    fun roundTrip() {
        val r = MpcShapeSettings.of(MpcModel.Shape.FITTED, 47.0, 14.0)
        val s = r.toShape(47.0, 14.0)
        close(s.a, MpcModel.Shape.FITTED.a, 1e-6); close(s.b, MpcModel.Shape.FITTED.b, 1e-6); close(s.height, MpcModel.Shape.FITTED.height, 1e-6)
    }

    @Test
    fun sharperPeakHasNarrowerWidth() {
        val (a1, b1) = MpcShapeSettings.exponents(0.4, 0.5)
        val (a2, b2) = MpcShapeSettings.exponents(0.4, 0.3)
        assertTrue(a2 + b2 > a1 + b1)
        close(MpcShapeSettings.width(a2, b2), 0.3, 1e-6)
        close(a2 / (a2 + b2), 0.4, 1e-9)
    }
}
