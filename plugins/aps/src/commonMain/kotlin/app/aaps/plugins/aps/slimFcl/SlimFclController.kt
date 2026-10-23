package app.aaps.plugins.aps.slimFcl

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The Slim FCL controller, ported 1:1 from the closed-loop replay (`replay.js`, strategy `mpc`).
 *
 * Every 5 minutes:
 * 1. DEV = CGM change + the effect of the loop's own insulin. A run of 15 min mean DEV above 1 is a carb
 *    impact; a clear dip inside the run starts a new one.
 * 2. The current impact is fitted with a beta pulse. Earlier impacts that still run keep their tails.
 * 3. Two futures for the next 3 h: meal (the pulse, or an exponential tail after the peak) and false start
 *    (DEV fades to zero).
 * 4. Meal chance from a small logistic, moved toward 1 when BG has room above the low line.
 * 5. Every SMB 0..cap by 0.1 U with a 30 min temp (normal, zero, high) is scored over the futures, with a
 *    second dose later chosen per future. The lowest expected cost wins.
 *
 * The controller keeps state between steps (the impact being fitted and the tails of earlier ones), so
 * it must see each 5 min step once and in order. [step] makes that safe: a second call for the same step
 * reuses the futures of the first.
 */
class SlimFclController {

    class Settings(
        /** mg/dL: cost [lowWeight] x (line - BG)^2 below it */
        val lowLine: Double = 115.0,
        /** mg/dL: cost (BG - line)^2 above it */
        val highLine: Double = 145.0,
        val lowWeight: Double = 1000.0,
        /** The meal chance moves this share of the way to 1 for each mmol/L of BG above the low line. */
        val roomBoost: Double = 0.35,
        /** When BG is falling, the room is measured from the BG this many minutes ahead. 0 = off. */
        val trendRoomMinutes: Double = 15.0,
        /** A second dose this many minutes later may be planned per future. 0 = off. */
        val secondStageMinutes: Int = 20,
        /** Largest SMB, U */
        val maxSmb: Double = 3.0,
        val maxIob: Double = 5.0,
        /** U/h of the high temp option */
        val highTempRate: Double = 3.3,
        /** No SMB below this BG, mg/dL */
        val smbMinBg: Double = 77.0
    )

    class Futures(val meal: DoubleArray, val non: DoubleArray, val mealChance: Double)

    class Decision(
        /** U/h: 0 (zero temp), the profile basal (normal) or [Settings.highTempRate] */
        val rate: Double,
        val duration: Int,
        /** SMB in U, rounded to 0.05 */
        val units: Double,
        /** Meal chance before and after the room rule */
        val mealChance: Double,
        val weightedMealChance: Double,
        /** Predicted BG (mg/dL) every 5 min for the chosen dose, in the meal and in the false-start future */
        val predictedMeal: DoubleArray,
        val predictedFalseStart: DoubleArray,
        val futures: Futures
    )

    private class Current(val start: Long, val fit: SlimFclModel.Fit, val p: Double)
    private class Frozen(val start: Long, val amp: Double, val length: Double, val p: Double)

    private var cur: Current? = null
    private var frozen = mutableListOf<Frozen>()
    private var lastStep: Long? = null
    private var lastFutures: Futures? = null

    fun reset() {
        cur = null; frozen = mutableListOf(); lastStep = null; lastFutures = null
    }

    /**
     * The two carb-impact futures for [step] (5 min steps since epoch).
     * [view]: smoothed BG newest first, 5 min apart, NaN where missing (26 values = 2 h 5 min).
     * [pastActivity]: the loop's insulin activity (U/min) at now, now - 5 min, ... (at least view.size - 1 values).
     * [isf]: profile ISF, mg/dL per U.
     */
    fun futures(step: Long, view: DoubleArray, pastActivity: DoubleArray, isf: Double): Futures {
        lastFutures?.let { if (step == lastStep) return it }
        if (lastStep?.let { step < it } == true) reset()   // time went back (data reload): start fresh
        val lb = view.size - 1
        val dev = DoubleArray(lb) { j -> view[j] - view[j + 1] + (pastActivity.getOrNull(j) ?: 0.0) * isf * 5 }
        val s3 = DoubleArray(lb) { j ->
            var sum = 0.0
            var n = 0
            for (q in j - 1..j + 1) if (q in 0 until lb && dev[q].isFinite()) {
                sum += dev[q]; n++
            }
            sum / n   // NaN when no value, as in the replay
        }
        var r = -1
        while (r + 1 < lb - 1 && s3[r + 1] > 1) r++
        var start: Int? = null                       // impact start, steps back from now
        if (r >= 0) {
            start = r
            for (m in 1 until r) {                   // most recent dip inside the run starts a new impact
                if (!(s3[m] <= s3[m - 1] && s3[m] <= s3[m + 1])) continue
                val after = maxOfRange(s3, 0, m)
                val before = maxOfRange(s3, m + 1, r + 1)
                val small = min(after, before)
                if (s3[m] <= 0.85 * small && small - s3[m] >= max(1.5, 0.15 * small)) {
                    start = m; break
                }
            }
        }
        val startStep = start?.let { step - it }
        cur?.let { c ->
            if (startStep != c.start) {              // the impact we were fitting is over or a clearly later one began: keep its tail
                if (startStep == null || startStep > c.start + 3) frozen.add(Frozen(c.start, c.fit.amp, c.fit.length, c.p))
                cur = null
            }
        }
        // a kept tail that starts near or after the current impact start is the same impact, now fitted again
        if (startStep != null) frozen = frozen.filter { it.start < startStep - 3 }.toMutableList()
        frozen = frozen.filter { step - it.start < it.length + 1 }.toMutableList()
        val frozenAt = { st: Long -> frozen.fold(0.0) { s, f -> s + f.p * SlimFclModel.pulse((st - f.start) + 0.5, f.amp, f.length) } }
        val meal = DoubleArray(48) { j -> frozenAt(step + 1 + j) }
        val non = meal.copyOf()
        var p = 0.0
        if (startStep != null) {
            val kk = (step - startStep).toInt()
            val ys = DoubleArray(kk + 1) { q ->
                val st = startStep + q
                val d = dev[(step - st).toInt()]
                (if (d.isFinite()) d else 0.0) - frozenAt(st)
            }
            val fit = SlimFclModel.fitPulse(ys, kk)
            val area = ys.sum()
            val d0 = max(0.0, s3[0] - frozenAt(step))
            val dn = max(kk, 3)
            // past the peak: at least 15 min after it and the 15 min mean of DEV down to 70% of its highest value
            val ym = DoubleArray(ys.size) { q ->
                var sum = 0.0
                var n = 0
                for (x in q - 1..q + 1) if (x in ys.indices) {
                    sum += ys[x]; n++
                }
                sum / n
            }
            val ymMax = ym.fold(Double.NEGATIVE_INFINITY) { a, b -> max(a, b) }
            val jp = ym.indexOfFirst { it == ymMax }
            val afterPeak = kk - jp >= 3 && ym[kk] <= 0.7 * ym[jp]
            for (j in 0 until 48) {
                val mealJ = if (afterPeak) max(0.0, ym[kk]) * exp(-(j + 1) * 5 / TAIL_TAU)
                else SlimFclModel.pulse(kk + 1 + j + 0.5, fit.amp, fit.length)
                meal[j] += mealJ
                non[j] += if (afterPeak) mealJ else if (j < dn) d0 * (1 - (j + 1).toDouble() / dn) else 0.0
            }
            val chance = SlimFclModel.mealChance(ys[ys.size - 1], area, kk)
            p = if (afterPeak) 1.0 else chance
            cur = Current(startStep, fit, chance)
        }
        return Futures(meal, non, p).also { lastStep = step; lastFutures = it }
    }

    /**
     * Choose the dose for this step.
     * [view] as in [futures]. [futureActivity]: activity (U/min) of the insulin already given, at now + 5 min x j,
     * j = 0..[HORIZON]. [iob] U, [basal] profile basal U/h, [target] mg/dL, [isfProfile] mg/dL per U.
     */
    fun step(
        step: Long, view: DoubleArray, pastActivity: DoubleArray, futureActivity: DoubleArray,
        iob: Double, basal: Double, target: Double, isfProfile: Double, settings: Settings
    ): Decision {
        val fut = futures(step, view, pastActivity, isfProfile)
        val isf = isfProfile / SlimFclModel.aisfFactor(SlimFclModel.aisfStatus(view), target)
        val bg0 = view[0]
        val trend = (view[0] - view[3]) / 3
        return decide(bg0, trend, futureActivity, basal, iob, bg0 >= settings.smbMinBg, fut, isf, settings)
    }

    private fun decide(
        bg0: Double, trend: Double, futureActivity: DoubleArray, basal: Double, iob: Double, smbAllowed: Boolean,
        fut: Futures, isf: Double, st: Settings
    ): Decision {
        val rc = st.secondStageMinutes / 5
        val maxU = if (smbAllowed) max(0.0, min(st.maxSmb, st.maxIob - iob)) else 0.0
        // more room above the low line (BG now, or trendRoomMinutes ahead when falling) = readier to call a meal
        val room = (if (st.trendRoomMinutes > 0 && trend < 0) bg0 + trend * st.trendRoomMinutes / 5 else bg0) - st.lowLine
        val pm = fut.mealChance + (1 - fut.mealChance) * min(1.0, max(0.0, st.roomBoost * room / 18))
        val list = listOf(fut.meal to pm, fut.non to 1 - pm)
        val act = { j: Int -> futureActivity.getOrNull(j) ?: 0.0 }
        var bestCost = 0.0
        var bestU = 0.0
        var bestMode = 0
        var found = false
        for (mode in intArrayOf(0, 1, -1)) {             // 0 normal basal, 1 zero temp, -1 high temp for 30 min
            val tempU = if (mode == 1) -basal else if (mode == -1) st.highTempRate - basal else 0.0
            if (mode == -1 && iob + tempU / 2 > st.maxIob) continue
            var u = 0.0
            while (u <= maxU + 1e-9) {
                var cost = 0.0
                for ((f, wgt) in list) {
                    if (wgt <= 0) continue
                    var bestC = Double.POSITIVE_INFINITY
                    val u1Max = (if (rc > 0) max(0.0, min(st.maxSmb, maxU - u)) else 0.0) + 1e-9
                    var u1 = 0.0
                    while (u1 <= u1Max) {                // the second stage, per future
                        var bgp = bg0
                        var c = 0.0
                        for (j in 1..HORIZON) {
                            val a = act(j) + u * UNIT_ACT[j] + tempU * ZT[j] + (if (rc > 0 && j > rc) u1 * UNIT_ACT[j - rc] else 0.0)
                            bgp += (f.getOrNull(j - 1) ?: 0.0) - isf * 5 * a
                            if (bgp < st.lowLine) c += st.lowWeight * ((st.lowLine - bgp) * (st.lowLine - bgp))
                            if (bgp > st.highLine) c += (bgp - st.highLine) * (bgp - st.highLine)
                        }
                        if (c < bestC) bestC = c
                        u1 += 0.25
                    }
                    cost += wgt * bestC
                }
                if (!found || cost < bestCost) {
                    found = true; bestCost = cost; bestU = u; bestMode = mode
                }
                u += 0.1
            }
        }
        val tempU = if (bestMode == 1) -basal else if (bestMode == -1) st.highTempRate - basal else 0.0
        val path = { f: DoubleArray ->
            var bgp = bg0
            DoubleArray(HORIZON) { k ->
                val j = k + 1
                bgp += f[j - 1] - isf * 5 * (act(j) + bestU * UNIT_ACT[j] + tempU * ZT[j]); bgp
            }
        }
        return Decision(
            rate = if (bestMode == 1) 0.0 else if (bestMode == -1) st.highTempRate else basal,
            duration = 30,
            units = SlimFclModel.roundHalfUp(bestU * 20) / 20,
            mealChance = fut.mealChance,
            weightedMealChance = pm,
            predictedMeal = path(fut.meal),
            predictedFalseStart = path(fut.non),
            futures = fut
        )
    }

    private fun maxOfRange(a: DoubleArray, from: Int, to: Int): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in from until to) m = max(m, a[i])
        return m
    }

    companion object {

        /** 3 h in 5 min steps */
        const val HORIZON = 36
        const val TAIL_TAU = 30.0

        /** The replay's insulin model (Fiasp as set in the profile): DIA 5 h, peak 55 min. */
        val CURVE = SlimFclModel.Curve(5.0, 55.0)

        /** Activity (U/min) of 1 U given now, j x 5 min later */
        private val UNIT_ACT = DoubleArray(HORIZON + 1) { j -> CURVE.activity(1.0, j * 5.0) }

        /** Activity of a 30 min temp of +1 U/h, given as six 5 min parts */
        private val ZT = DoubleArray(HORIZON + 1) { j ->
            var z = 0.0
            for (c in 0 until 6) {
                val age = j * 5 - c * 5 - 2.5
                if (age > 0) z += CURVE.activity(1.0 / 12, age)
            }
            z
        }
    }
}
