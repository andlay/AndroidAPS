package app.aaps.plugins.aps.mpc

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fixed parts of the MPC model: the values fitted from the user's own data and the maths that
 * does not depend on state. Ported 1:1 from the closed-loop replay (`replay.js`, see `loop/MODEL.md`).
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

    /** AutoISF weights calibrated to the logged AutoISF factors. */
    const val AISF_ACCEL = 0.12392826946765509
    const val AISF_BRAKE = 0.05399820232306546
    const val AISF_LOW = 0.3149671703880714
    const val AISF_HIGH = 0.12827858014591703
    const val AISF_PP = 3.923030121186201e-9
    const val AISF_DURA = 0.1941507231700888
    const val AISF_MAX = 1.6
    const val AISF_MIN = 0.7

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

    /* ---- AutoISF factor, ported from OpenAPSAutoISFPlugin and GlucoseStatusCalculatorAutoIsf (as in the replay) ---- */

    class AisfStatus(
        val bg: Double, val dura: Double, val avg: Double, val corr: Double,
        val a0: Double, val a1: Double, val a2: Double, val acce: Double, val delta: Double, val short: Double
    )

    /** [v]: smoothed BG, newest first, 5 min apart, NaN where missing. Only the first 11 values are used. */
    fun aisfStatus(view: DoubleArray): AisfStatus {
        val v = view.copyOf(min(view.size, 11))
        val now = v[0]
        var s = now
        var old = now
        var dur = 0.0
        var cnt = 1
        for (i in 1 until v.size) {
            if (!(v[i] > 39)) continue
            cnt++
            if (i * 5 - dur > 13) break
            if (v[i] > old * 0.95 && v[i] < old * 1.05) {
                s += v[i]; old = s / cnt; dur = i * 5.0
            } else break
        }
        var best = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
        var sx = 0.0
        var sx2 = 0.0
        var sx3 = 0.0
        var sx4 = 0.0
        var sy = 0.0
        var sxy = 0.0
        var sx2y = 0.0
        var m = 0
        var tl = 0.0
        for (i in v.indices) {
            if (!(v[i] > 39)) continue
            val ti = -i.toDouble()
            if (i * 5 > 47) break
            if (ti < tl - 11.0 / 5) break
            tl = ti; m++
            val bg = v[i] / 50
            sx += ti; sx2 += ti * ti; sx3 += ti.pow(3); sx4 += ti.pow(4); sy += bg; sxy += ti * bg; sx2y += ti * ti * bg
            if (m > 3) {
                val h = sx4 * (sx2 * m - sx * sx) - sx3 * (sx3 * m - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
                if (h == 0.0) continue
                val a = (sx2y * (sx2 * m - sx * sx) - sxy * (sx3 * m - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)) / h
                val b = (sx4 * (sxy * m - sy * sx) - sx3 * (sx2y * m - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)) / h
                val c = (sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)) / h
                var ss = 0.0
                var sr = 0.0
                val ym = sy / m
                for (j in 0..i) if (v[j] > 39) {
                    ss += (v[j] / 50 - ym).pow(2); sr += (v[j] / 50 - (a * j * j - b * j + c)).pow(2)
                }
                val r2 = if (ss != 0.0) 1 - sr / ss else 0.64
                if (r2 >= best[0]) best = doubleArrayOf(r2, c * 50, b * 50, a * 50, 2 * a * 50)
            }
        }
        return AisfStatus(
            bg = now, dura = dur, avg = old, corr = best[0], a0 = best[1], a1 = best[2], a2 = best[3], acce = best[4],
            delta = v[0] - v[1], short = ((v[0] - v[1]) + (v[0] - v[2]) / 2 + (v[0] - v[3]) / 3) / 3
        )
    }

    private val PX = doubleArrayOf(50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 150.0, 180.0, 200.0)
    private val PY = doubleArrayOf(-0.5, -0.5, -0.3, -0.2, 0.0, 0.0, 0.5, 0.7, 0.7)

    private fun interp(x: Double, lo: Double, hi: Double): Double {
        val v: Double = if (x < PX[0]) PY[0] + (PY[1] - PY[0]) / (PX[1] - PX[0]) * (x - PX[0])
        else if (x > PX[8]) PY[7] + (PY[8] - PY[7]) / (PX[8] - PX[7]) * (x - PX[7])
        else {
            var k = 0
            while (PX[k + 1] < x) k++
            PY[k] + (PY[k + 1] - PY[k]) * (x - PX[k]) / (PX[k + 1] - PX[k])
        }
        return v * (if (x > 100) hi else lo)
    }

    /** AutoISF factor (ISF is divided by it) for this BG shape and [target] (mg/dL). */
    fun aisfFactor(g: AisfStatus, target: Double): Double {
        var aw = 1.0
        var acce = 1.0
        val bg = g.bg
        val off = target + 10 - bg
        if (g.a2 != 0.0 && g.corr >= 0.9) {
            val mmd = -g.a1 / 2 / g.a2 * 5
            val mmv = g.a0 - mmd * mmd / 25 * g.a2
            if (mmd > 0 && g.acce > 0 && mmd <= 30 && mmv < target) aw = -AISF_BRAKE
        }
        if (g.corr >= 0.9) {
            val share = 10 * (g.corr - 0.9)
            var cap = 1.0
            if (aw == 1.0 && bg < target) {
                if (g.acce > 0) {
                    if (g.acce > 1) cap = 0.5
                    aw = AISF_BRAKE
                } else if (g.acce < 0) aw = AISF_ACCEL
            } else if (aw == 1.0) {
                if (g.acce < 0) aw = AISF_BRAKE else if (g.acce > 0) aw = AISF_ACCEL
            }
            acce = 1 + g.acce * cap * aw * share
        }
        val bgf = 1 + interp(100 - off, AISF_LOW, AISF_HIGH)
        val lim = { f: Double -> min(max(f, AISF_MIN), AISF_MAX) }
        if (bgf < 1) {
            var lift = min(bgf, acce)
            if (acce > 1) lift = bgf * acce
            return lim(lift)
        }
        var pp = 1.0
        if (!(off > 0) && !(g.short < 0)) pp = 1 + max(0.0, g.delta * AISF_PP)
        var dura = 1.0
        if (g.dura >= 10 && g.avg > target) dura += g.dura / 60 * AISF_DURA / target * (g.avg - target)
        var lift = maxOf(dura, bgf, acce, pp)
        if (acce < 1) lift *= acce
        return lim(lift)
    }
}
