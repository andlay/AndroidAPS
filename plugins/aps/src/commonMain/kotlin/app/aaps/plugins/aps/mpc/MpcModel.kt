package app.aaps.plugins.aps.mpc

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fixed parts of the MPC model: the values fitted from the user's own data and the maths that
 * does not depend on state. Ported 1:1 from the closed-loop replay (`replay.js`).
 * A port, not a re-design: the golden test feeds both the same steps and compares the decisions.
 */
object MpcModel {

    /**
     * Shape of one carb impact: a beta pulse u^a (1-u)^b over its length, scaled to its height.
     * [height] (mg/dL per 5 min at the peak) and [length] (5 min steps) are the typical values the fit is
     * pulled toward, with log spreads [heightSd] and [lengthSd]. [mealBias] moves the meal chance (log odds).
     * [tailTau] (min) is how fast the carb effect fades after the peak.
     * The user sets these as readable values (see [MpcShapeSettings]); [FITTED] holds the values fitted to the data.
     */
    class Shape(
        val a: Double, val b: Double, val height: Double, val heightSd: Double, val length: Double, val lengthSd: Double,
        val mealBias: Double = 0.0, val tailTau: Double = 30.0
    ) {

        internal val mx = (a / (a + b)).pow(a) * (b / (a + b)).pow(b)

        companion object {

            val FITTED = Shape(
                a = 1.80333984375, b = 2.997802734375,
                height = 12.580252824902534, heightSd = 0.3692596298487712,
                length = 18.857531421888318, lengthSd = 0.45539491571666757
            )
        }
    }

    /** Chance that a rise is a meal: logistic in (DEV now, area so far, age in steps up to 12). */
    val MEAL_WEIGHTS = doubleArrayOf(-1.8300874282881199, 0.08904500541122336, 0.05411048608570572, -0.2935661802931909)

    /** Noise of DEV for the shape fit, mg/dL. */
    const val FIT_NOISE = 2.5

    /** Carb impact (mg/dL per 5 min) of a pulse at x steps after its start. */
    fun pulse(x: Double, amp: Double, length: Double, shape: Shape): Double =
        if (x <= 0 || x >= length) 0.0 else amp * (x / length).pow(shape.a) * (1 - x / length).pow(shape.b) / shape.mx

    fun mealChance(devNow: Double, area: Double, age: Int, shape: Shape): Double =
        1 / (1 + exp(-(MEAL_WEIGHTS[0] + shape.mealBias + MEAL_WEIGHTS[1] * devNow + MEAL_WEIGHTS[2] * area + MEAL_WEIGHTS[3] * min(age, 12))))

    class Fit(val amp: Double, val length: Double)

    private val LENGTHS: List<Double> = buildList {
        var t = 4
        while (t <= 60) {
            add(t.toDouble()); t += if (t < 30) 1 else 2
        }
    }
    private val HEIGHTS: List<Double> = List(24) { k -> 2 * 25.0.pow(k / 23.0) }

    /** Best height and length for the DEV seen since the impact started (ys[q] at x = q + 0.5), with priors. */
    fun fitPulse(ys: DoubleArray, age: Int, shape: Shape): Fit {
        var best: Fit? = null
        var bestL = 0.0
        for (t in LENGTHS) {
            if (t < max(4.0, 0.5 * age)) continue   // the impact may already be over
            val pT = ((ln(t) - ln(shape.length)) / shape.lengthSd).pow(2)
            for (amp in HEIGHTS) {
                var sse = 0.0
                for (q in ys.indices) {
                    val r = ys[q] - pulse(q + 0.5, amp, t, shape); sse += r * r
                }
                val l = sse / (FIT_NOISE * FIT_NOISE) + pT + ((ln(amp) - ln(shape.height)) / shape.heightSd).pow(2)
                if (best == null || l < bestL) {
                    best = Fit(amp, t); bestL = l
                }
            }
        }
        return best ?: Fit(0.0, shape.length)
    }

    /**
     * The spread of meals that fit the DEV seen so far: the posterior over (height, length) on the fit grid, cut into
     * [n] equal-weight scenarios by total size (height x length, weighted quantiles). The plan is scored over all of
     * them, so an early dose has to be right for a small meal as well as a big one.
     */
    fun scenarios(ys: DoubleArray, age: Int, shape: Shape, n: Int): List<Fit> {
        class Point(val l: Double, val amp: Double, val length: Double) {
            var w = 0.0
            val size = amp * length
        }
        val pts = mutableListOf<Point>()
        for (t in LENGTHS) {
            if (t < max(4.0, 0.5 * age)) continue
            val pT = ((ln(t) - ln(shape.length)) / shape.lengthSd).pow(2)
            for (amp in HEIGHTS) {
                var sse = 0.0
                for (q in ys.indices) {
                    val r = ys[q] - pulse(q + 0.5, amp, t, shape); sse += r * r
                }
                pts += Point(sse / (FIT_NOISE * FIT_NOISE) + pT + ((ln(amp) - ln(shape.height)) / shape.heightSd).pow(2), amp, t)
            }
        }
        if (pts.isEmpty()) return List(n) { Fit(0.0, shape.length) }
        val lMin = pts.minOf { it.l }
        for (p in pts) p.w = exp(-(p.l - lMin) / 2)
        val sorted = pts.sortedBy { it.size }   // stable, as the replay's sort
        val total = sorted.sumOf { it.w }
        val out = mutableListOf<Fit>()
        var acc = 0.0
        var idx = 0
        for (k in 0 until n) {
            val target = (k + 0.5) / n * total
            while (idx < sorted.size - 1 && acc + sorted[idx].w < target) {
                acc += sorted[idx].w; idx++
            }
            out += Fit(sorted[idx].amp, sorted[idx].length)
        }
        return out
    }

    /** Oref exponential insulin curve (ICfg.iobCalcForTreatment): activity in U/min and IOB of [amount] U given [minutes] ago. */
    class Curve(diaHours: Double, peakMinutes: Double) {

        val td = max(diaHours * 60, 300.0)
        private val tp = min(max(peakMinutes, 1.0), td / 2 - 1)
        private val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        private val a = 2 * tau / td
        private val s = 1 / (1 - a + (1 + a) * exp(-td / tau))

        fun activity(amount: Double, minutes: Double): Double {
            if (amount == 0.0 || minutes < 0 || minutes >= td) return 0.0
            return amount * (s / (tau * tau)) * minutes * (1 - minutes / td) * exp(-minutes / tau)
        }

        fun iob(amount: Double, minutes: Double): Double {
            if (amount == 0.0 || minutes < 0 || minutes >= td) return 0.0
            val e = exp(-minutes / tau)
            return amount * (1 - s * (1 - a) * ((minutes * minutes / (tau * td * (1 - a)) - minutes / tau - 1) * e + 1))
        }
    }

    /** JS Math.round: halves go up. */
    fun roundHalfUp(x: Double): Double = floor(x + 0.5)
}
