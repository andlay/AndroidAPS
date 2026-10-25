package app.aaps.plugins.aps.mpc

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The meal shape in values a user can read, and the exact conversion to and from [MpcModel.Shape].
 *
 * - [peakPosition]: where the carb effect peaks, as a share of the rise (0 = start, 1 = end)
 * - [halfWidth]: how long the carb effect stays above half its peak, as a share of the rise
 * - [typicalMinutes]: length of a typical rise, min; [lengthSpreadPct]: how much it varies (+/- %)
 * - [typicalGrams]: carbs of a typical meal; [sizeSpreadPct]: how much meal size varies (+/- %)
 * - [readinessPct]: how readily a rise is called a meal; 100 = as fitted, 200 = twice the odds
 * - [tailHalfLifeMinutes]: after the peak, the carb effect halves every this many minutes
 *
 * Grams need the profile ISF and carb ratio: a meal of G grams raises BG by G x ISF / IC in total.
 */
class MpcShapeSettings(
    val peakPosition: Double,
    val halfWidth: Double,
    val typicalMinutes: Double,
    val lengthSpreadPct: Double,
    val typicalGrams: Double,
    val sizeSpreadPct: Double,
    val readinessPct: Double,
    val tailHalfLifeMinutes: Double
) {

    fun toShape(isf: Double, ic: Double): MpcModel.Shape {
        val (a, b) = exponents(peakPosition, halfWidth)
        val length = typicalMinutes / 5
        val height = typicalGrams * isf / ic / (length * areaFactor(a, b))
        return MpcModel.Shape(
            a = a, b = b, height = height, heightSd = ln(1 + sizeSpreadPct / 100),
            length = length, lengthSd = ln(1 + lengthSpreadPct / 100),
            mealBias = ln(readinessPct / 100), tailTau = tailHalfLifeMinutes / ln(2.0)
        )
    }

    companion object {

        fun of(shape: MpcModel.Shape, isf: Double, ic: Double): MpcShapeSettings {
            val m = shape.a / (shape.a + shape.b)
            return MpcShapeSettings(
                peakPosition = m,
                halfWidth = width(shape.a, shape.b),
                typicalMinutes = shape.length * 5,
                lengthSpreadPct = (exp(shape.lengthSd) - 1) * 100,
                typicalGrams = shape.height * shape.length * areaFactor(shape.a, shape.b) * ic / isf,
                sizeSpreadPct = (exp(shape.heightSd) - 1) * 100,
                readinessPct = exp(shape.mealBias) * 100,
                tailHalfLifeMinutes = shape.tailTau * ln(2.0)
            )
        }

        /** The pulse u^a (1-u)^b, scaled to 1 at its peak */
        fun unitPulse(u: Double, a: Double, b: Double): Double {
            if (u <= 0 || u >= 1) return 0.0
            val m = a / (a + b)
            return u.pow(a) * (1 - u).pow(b) / (m.pow(a) * (1 - m).pow(b))
        }

        /** Area under the unit pulse over u = 0..1 (Simpson's rule) */
        fun areaFactor(a: Double, b: Double): Double {
            val n = 2000
            var sum = 0.0
            for (i in 0..n) {
                val w = if (i == 0 || i == n) 1.0 else if (i % 2 == 1) 4.0 else 2.0
                sum += w * unitPulse(i.toDouble() / n, a, b)
            }
            return sum / (3.0 * n)
        }

        /** Share of the rise the pulse stays above half its peak */
        fun width(a: Double, b: Double): Double {
            val m = a / (a + b)
            return half(a, b, m, 1.0) - half(a, b, 0.0, m)
        }

        /** The point in [lo, hi] where the unit pulse crosses 0.5 (bisection; the pulse is monotone there) */
        private fun half(a: Double, b: Double, lo: Double, hi: Double): Double {
            var l = lo
            var h = hi
            val rising = unitPulse((lo + hi) / 2, a, b) < unitPulse(hi - 1e-9, a, b)
            repeat(80) {
                val mid = (l + h) / 2
                val above = unitPulse(mid, a, b) >= 0.5
                if (above == rising) h = mid else l = mid
            }
            return (l + h) / 2
        }

        /** Exponents a, b with peak at [peak] and half-height width [width]: a wider pulse has smaller a + b */
        fun exponents(peak: Double, width: Double): Pair<Double, Double> {
            var lo = 0.2
            var hi = 400.0
            repeat(100) {
                val k = (lo + hi) / 2
                if (width(peak * k, (1 - peak) * k) > width) lo = k else hi = k
            }
            val k = (lo + hi) / 2
            return peak * k to (1 - peak) * k
        }
    }
}
