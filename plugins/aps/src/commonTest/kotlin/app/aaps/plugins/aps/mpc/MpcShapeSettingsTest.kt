package app.aaps.plugins.aps.mpc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MpcShapeSettingsTest {

    private fun close(a: Double, b: Double, tol: Double) = assertTrue(abs(a - b) <= tol * maxOf(1.0, abs(b)), "$a != $b")

    @Test
    fun defaultsAreTheFittedShape() {
        // the preference defaults, read back through the conversion, give the fitted model (ISF and carb ratio of the replay)
        val readable = MpcShapeSettings(
            peakPosition = MpcDoubleKey.PeakPosition.defaultValue,
            halfWidth = MpcDoubleKey.HalfWidth.defaultValue,
            typicalMinutes = MpcDoubleKey.RiseLength.defaultValue,
            lengthSpreadPct = MpcDoubleKey.RiseLengthSpread.defaultValue,
            typicalGrams = MpcDoubleKey.TypicalMeal.defaultValue,
            sizeSpreadPct = MpcDoubleKey.MealSizeSpread.defaultValue,
            readinessPct = MpcIntKey.MealReadiness.defaultValue.toDouble(),
            tailHalfLifeMinutes = MpcDoubleKey.TailHalfLife.defaultValue
        )
        val s = readable.toShape(isf = 46.8, ic = 13.92)
        val f = MpcModel.Shape.FITTED
        close(s.a, f.a, 1e-6); close(s.b, f.b, 1e-6)
        close(s.height, f.height, 1e-4); close(s.heightSd, f.heightSd, 1e-9)
        close(s.length, f.length, 1e-9); close(s.lengthSd, f.lengthSd, 1e-9)
        close(s.mealBias, 0.0, 1e-12); close(s.tailTau, f.tailTau, 1e-9)
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
