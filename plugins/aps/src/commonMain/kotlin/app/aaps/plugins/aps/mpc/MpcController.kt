package app.aaps.plugins.aps.mpc

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * MPC meal SMBs: an extension of the vanilla OpenAPS SMB algorithm, ported 1:1 from the closed-loop replay
 * (`replay.js`, the design chosen in the loop study).
 *
 * Vanilla determine-basal sets the temp basal as always, with its own SMBs off. Every 5 minutes this controller:
 * 1. DEV = CGM change + the effect of the loop's own insulin. A run of 15 min mean DEV above 1 is a carb
 *    impact; a clear dip inside the run starts a new one.
 * 2. The current impact is fitted with a beta pulse. Earlier impacts that still run keep their tails.
 * 3. Futures for the next 3 h: [SCENARIOS] meal sizes that fit the DEV so far (or an exponential tail after
 *    the peak), and a false start (DEV fades to zero).
 * 4. Meal chance from a small logistic, moved toward 1 when BG has room above the target.
 * 5. Only when a meal is seen (chance at least 10 %, or BG at 10 mmol/L or more, in the last 30 min): every SMB
 *    0..cap by 0.1 U is scored over the futures with vanilla's temp, and a second dose 20 min later chosen per
 *    future. The lowest expected cost wins. The second stage is how the plan can wait for the meal to show itself.
 *
 * Cost per 5 min step: [MealSmbSettings.lowWeight] x (target - BG)^2 below the target, (BG - high line)^2 above it.
 *
 * The controller keeps state between steps (the impact being fitted, the tails of earlier ones, when a meal
 * was last seen), so it must see each 5 min step once and in order. A second call for the same step reuses the
 * futures of the first.
 */
class MpcController {

    class MealSmbSettings(
        /** mg/dL: the profile target. Cost [lowWeight] x (target - BG)^2 below it, and the room rule is measured from it. */
        val target: Double,
        /** mg/dL above the target where the cost of being high starts */
        val highAbove: Double = HIGH_ABOVE_TARGET,
        val lowWeight: Double = 1000.0,
        /** The meal chance moves this share of the way to 1 for each mmol/L of BG above the target. */
        val roomBoost: Double = 0.35,
        /** A second dose this many minutes later is planned per future. */
        val secondStageMinutes: Int = 20,
        /** Largest SMB, U (AAPS: UAM max minutes of basal) */
        val maxSmb: Double,
        /** U (AAPS max IOB) */
        val maxIob: Double,
        /** Shape of a carb impact and the meal call */
        val shape: MpcModel.Shape = MpcModel.Shape.FITTED
    )

    /**
     * The carb-impact futures (mg/dL per 5 min, step 1 ahead first) and what they came from.
     * [scenarios]: the meal sizes that fit so far, equal weight; null after the peak or with no impact.
     * [dev]: DEV seen, newest first. [fitted]: the model over the same steps (current fit plus kept tails).
     * [impactAge]: steps since the current impact started, or -1 when none runs.
     */
    class Futures(
        val meal: DoubleArray, val non: DoubleArray, val mealChance: Double,
        val dev: DoubleArray = DoubleArray(0), val fitted: DoubleArray = DoubleArray(0),
        val impactAge: Int = -1, val afterPeak: Boolean = false, val scenarios: List<DoubleArray>? = null
    )

    /** One SMB size and its expected cost */
    class Option(val units: Double, val cost: Double)

    class MealSmb(
        /** SMB in U, rounded to 0.05; 0 when no meal is seen */
        val units: Double,
        /** True when a meal was seen in the last 30 min, so the SMB was chosen by the plan */
        val mealSeen: Boolean,
        /** Meal chance before and after the room rule */
        val mealChance: Double,
        val weightedMealChance: Double,
        /** Predicted BG (mg/dL) every 5 min for the chosen SMB with vanilla's temp: meal, false start, and their mix */
        val predictedMeal: DoubleArray,
        val predictedFalseStart: DoubleArray,
        val predictedExpected: DoubleArray,
        val futures: Futures,
        /** BG above the target the room rule used, mg/dL */
        val room: Double,
        /** The best SMB sizes, lowest cost first (empty when no meal is seen) */
        val options: List<Option>
    )

    private class Current(val start: Long, val fit: MpcModel.Fit, val p: Double)
    private class Frozen(val start: Long, val amp: Double, val length: Double, val p: Double)

    private var cur: Current? = null
    private var frozen = mutableListOf<Frozen>()
    private var lastStep: Long? = null
    private var lastFutures: Futures? = null
    private var lastMealSeen = Long.MIN_VALUE / 2

    fun reset() {
        cur = null; frozen = mutableListOf(); lastStep = null; lastFutures = null; lastMealSeen = Long.MIN_VALUE / 2
    }

    /**
     * The carb-impact futures for [step] (5 min steps since epoch).
     * [view]: smoothed BG newest first, 5 min apart, NaN where missing (26 values = 2 h 5 min).
     * [pastActivity]: the loop's insulin activity (U/min) at now, now - 5 min, ... (at least view.size - 1 values).
     * [isf]: profile ISF, mg/dL per U.
     */
    fun futures(step: Long, view: DoubleArray, pastActivity: DoubleArray, isf: Double, shape: MpcModel.Shape = MpcModel.Shape.FITTED): Futures {
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
        val frozenAt = { st: Long -> frozen.fold(0.0) { s, f -> s + f.p * MpcModel.pulse((st - f.start) + 0.5, f.amp, f.length, shape) } }
        val meal = DoubleArray(48) { j -> frozenAt(step + 1 + j) }
        val non = meal.copyOf()
        var p = 0.0
        var scenarios: List<DoubleArray>? = null
        var age = -1
        var past = false
        val fitted = DoubleArray(lb) { j -> frozenAt(step - j) }
        if (startStep != null) {
            val kk = (step - startStep).toInt()
            val ys = DoubleArray(kk + 1) { q ->
                val st = startStep + q
                val d = dev[(step - st).toInt()]
                (if (d.isFinite()) d else 0.0) - frozenAt(st)
            }
            val fit = MpcModel.fitPulse(ys, kk, shape)
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
                val mealJ = if (afterPeak) max(0.0, ym[kk]) * exp(-(j + 1) * 5 / shape.tailTau)
                else MpcModel.pulse(kk + 1 + j + 0.5, fit.amp, fit.length, shape)
                meal[j] += mealJ
                non[j] += if (afterPeak) mealJ else if (j < dn) d0 * (1 - (j + 1).toDouble() / dn) else 0.0
            }
            if (!afterPeak) scenarios = MpcModel.scenarios(ys, kk, shape, SCENARIOS).map { sc ->
                DoubleArray(48) { j -> frozenAt(step + 1 + j) + MpcModel.pulse(kk + 1 + j + 0.5, sc.amp, sc.length, shape) }
            }
            val chance = MpcModel.mealChance(ys[ys.size - 1], area, kk, shape)
            p = if (afterPeak) 1.0 else chance
            cur = Current(startStep, fit, chance)
            age = kk; past = afterPeak
            for (j in 0..kk) fitted[j] += MpcModel.pulse((kk - j) + 0.5, fit.amp, fit.length, shape)
        }
        return Futures(meal, non, p, dev, fitted, age, past, scenarios).also { lastStep = step; lastFutures = it }
    }

    /**
     * The meal SMB for this step.
     * [view], [pastActivity] as in [futures]. [futureActivity]: activity (U/min) of the insulin already given, at
     * now + 5 min x j, j = 0..[HORIZON]. [iob] U, [basal] profile basal U/h, [tempRate] U/h: vanilla's temp for the
     * next 30 min. [isf] profile ISF, mg/dL per U. [smbAllowed]: vanilla allows an SMB now (settings, constraints,
     * SMB interval, even target).
     */
    fun mealSmb(
        step: Long, view: DoubleArray, pastActivity: DoubleArray, futureActivity: DoubleArray,
        iob: Double, basal: Double, tempRate: Double, isf: Double, smbAllowed: Boolean, settings: MealSmbSettings
    ): MealSmb {
        val fut = futures(step, view, pastActivity, isf, settings.shape)
        val bg0 = view[0]
        if (fut.mealChance >= MEAL_SEEN_CHANCE || bg0 >= MEAL_SEEN_BG) lastMealSeen = max(lastMealSeen, step)
        val mealSeen = step - lastMealSeen < MEAL_SEEN_STEPS
        val lowLine = settings.target
        val highLine = settings.target + settings.highAbove
        val room = bg0 - lowLine
        val pm = fut.mealChance + (1 - fut.mealChance) * min(1.0, max(0.0, settings.roomBoost * room / 18))
        val list = fut.scenarios?.let { sc -> sc.map { it to pm * (1.0 / sc.size) } + listOf(fut.non to 1 - pm) }   // as the replay: Pm x (1 / n)
            ?: listOf(fut.meal to pm, fut.non to 1 - pm)
        val act = { j: Int -> futureActivity.getOrNull(j) ?: 0.0 }
        val tempU = tempRate - basal
        val rc = settings.secondStageMinutes / 5
        val maxU = if (smbAllowed && mealSeen) max(0.0, min(settings.maxSmb, settings.maxIob - iob)) else 0.0
        var bestCost = 0.0
        var bestU = 0.0
        var found = false
        val options = mutableListOf<Option>()
        var u = 0.0
        while (u <= maxU + 1e-9) {
            var cost = 0.0
            for ((f, wgt) in list) {
                if (wgt <= 0) continue
                var bestC = Double.POSITIVE_INFINITY
                val u1Max = (if (rc > 0) max(0.0, min(settings.maxSmb, maxU - u)) else 0.0) + 1e-9
                var u1 = 0.0
                while (u1 <= u1Max) {                    // the second stage, per future
                    var bgp = bg0
                    var c = 0.0
                    for (j in 1..HORIZON) {
                        val a = act(j) + u * UNIT_ACT[j] + tempU * ZT[j] + (if (rc > 0 && j > rc) u1 * UNIT_ACT[j - rc] else 0.0)
                        bgp += (f.getOrNull(j - 1) ?: 0.0) - isf * 5 * a
                        if (bgp < lowLine) c += settings.lowWeight * ((lowLine - bgp) * (lowLine - bgp))
                        if (bgp > highLine) c += (bgp - highLine) * (bgp - highLine)
                    }
                    if (c < bestC) bestC = c
                    u1 += 0.25
                }
                cost += wgt * bestC
            }
            options += Option(MpcModel.roundHalfUp(u * 20) / 20, cost)
            if (!found || cost < bestCost) {
                found = true; bestCost = cost; bestU = u
            }
            u += 0.1
        }
        val path = { f: DoubleArray ->
            var bgp = bg0
            DoubleArray(HORIZON) { k ->
                val j = k + 1
                bgp += f[j - 1] - isf * 5 * (act(j) + bestU * UNIT_ACT[j] + tempU * ZT[j]); bgp
            }
        }
        val pMeal = path(fut.meal)
        val pNon = path(fut.non)
        return MealSmb(
            units = if (mealSeen) MpcModel.roundHalfUp(bestU * 20) / 20 else 0.0,
            mealSeen = mealSeen,
            mealChance = fut.mealChance,
            weightedMealChance = pm,
            predictedMeal = pMeal,
            predictedFalseStart = pNon,
            predictedExpected = DoubleArray(HORIZON) { k -> pm * pMeal[k] + (1 - pm) * pNon[k] },
            futures = fut,
            room = room,
            options = if (mealSeen) options.sortedBy { it.cost }.take(5) else emptyList()
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

        /** Meal sizes the plan is scored over */
        const val SCENARIOS = 3

        /** A meal is seen at this chance, or at this BG (mg/dL), and stays seen for 30 min */
        const val MEAL_SEEN_CHANCE = 0.1
        const val MEAL_SEEN_BG = 180.0
        private const val MEAL_SEEN_STEPS = 6

        /** The cost of being high starts this far above the target, mg/dL (2.5 mmol/L) */
        const val HIGH_ABOVE_TARGET = 45.0

        /** The replay's insulin model (Fiasp as set in the profile): DIA 5 h, peak 55 min. */
        val CURVE = MpcModel.Curve(5.0, 55.0)

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
