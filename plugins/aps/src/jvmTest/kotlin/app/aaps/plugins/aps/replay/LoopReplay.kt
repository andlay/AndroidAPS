package app.aaps.plugins.aps.replay

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TT
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.plus
import app.aaps.core.objects.extensions.round
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.plugins.aps.openAPSSMB.extensions.asRounded
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Settings for one replay. Scales are applied to the candidate profile from the input; [percentage]
 * works like a profile switch (basal x p, ISF and I:C / p, DynISF TDD x p).
 */
internal data class Scenario(
    val name: String,
    /**
     * Use the settings the phone logged at each loop run instead of the fixed candidate profile. The
     * scales and [percentage] then apply on top of them, so profile switches (gym, illness) stay.
     */
    val asRun: Boolean = false,
    val percentage: Double = 100.0,
    val basalScale: Double = 1.0,
    val isfScale: Double = 1.0,
    val crScale: Double = 1.0,
    val dynIsf: Boolean = true,
    val dynFactor: Double = 100.0,
    /** No SMB below this BG (mg/dL), like the SMB minimum BG setting. 0 = off. */
    val smbMinBg: Double = 144.0,
    /** How the body reacts to insulin that differs from what was really given (mg/dL per U). */
    val trueIsf: Double = 36.0,
    val maxIob: Double = 10.0,
    val uam: Boolean = false,
    val smbInterval: Int = 10,
    /** Drop an SMB (or temp change) when the real pump dropped the phone's request at that moment. */
    val blockSmb: Boolean = true,
    val blockTemps: Boolean = false,
    val from: Long = 0L,
    val to: Long = Long.MAX_VALUE,
    val trace: Boolean = false,
    /** Run the AutoISF plugin instead of SMB (DynISF settings are then ignored). */
    val autoIsf: Boolean = false,
    /** AutoISF preference overrides by key name, on top of the quick guide's starting values. */
    val aisf: Map<String, String> = emptyMap(),
    /** Multiplies all AutoISF glucose weights (acce, pp, bg range, dura). */
    val aisfScale: Double = 1.0
) {

    companion object {

        /** `name key=value key=value ...` */
        fun parse(line: String): Scenario {
            val parts = line.trim().split(Regex("\\s+"))
            var s = Scenario(parts[0])
            for (kv in parts.drop(1)) {
                val (k, v) = kv.split('=', limit = 2)
                s = when (k) {
                    "asRun"   -> s.copy(asRun = v == "1" || v == "true")
                    "pct"     -> s.copy(percentage = v.toDouble())
                    "basal"   -> s.copy(basalScale = v.toDouble())
                    "isf"     -> s.copy(isfScale = v.toDouble())
                    "cr"      -> s.copy(crScale = v.toDouble())
                    "dyn"     -> s.copy(dynIsf = v == "1" || v == "true")
                    "factor"  -> s.copy(dynFactor = v.toDouble())
                    "smbMin"  -> s.copy(smbMinBg = v.toDouble())
                    "trueIsf" -> s.copy(trueIsf = v.toDouble())
                    "maxIob"  -> s.copy(maxIob = v.toDouble())
                    "uam"     -> s.copy(uam = v == "1" || v == "true")
                    "smbInt"  -> s.copy(smbInterval = v.toInt())
                    "blockSmb" -> s.copy(blockSmb = v == "1" || v == "true")
                    "blockTemps" -> s.copy(blockTemps = v == "1" || v == "true")
                    "from"    -> s.copy(from = v.toLong())
                    "to"      -> s.copy(to = v.toLong())
                    "trace"   -> s.copy(trace = v == "1" || v == "true")
                    "aisf"    -> s.copy(autoIsf = v == "1" || v == "true")
                    "aisfScale" -> s.copy(aisfScale = v.toDouble())
                    else      -> if (k.startsWith("aisf.")) s.copy(aisf = s.aisf + (k.removePrefix("aisf.") to v)) else error("unknown key $k")
                }
            }
            return s
        }
    }
}

internal class ReplayResult(val scenario: Scenario, val metrics: LinkedHashMap<String, Double>, val trace: List<String>)

/**
 * Runs past data through the real [DetermineBasalSMB], five minutes at a time, in a closed loop.
 *
 * The BG the replayed loop sees is the true (smoothed) BG plus the effect of the insulin it gave
 * differently from what was really given:
 * `G(k) = G(k-1) + [truth(k) - truth(k-1)] - trueIsf * sum(activitySim - activityReal)`.
 * So meals, exercise and everything else stay as they really happened, and only the insulin changes.
 * What the loop reads also keeps the real sensor noise and filter lag (the "view" offsets).
 *
 * The inputs to determine_basal are built like `OpenAPSSMBPlugin.invoke` builds them: the IOB array
 * like `IobCobCalculatorPlugin.calculateIobArrayForSMB` (running temp cut at now, zero temp branch),
 * deltas with the real [DeltaCalculator], deviation slopes like the autosens worker, DynISF TDD
 * like `OpenAPSSMBPlugin.calculateRawDynIsf`. COB and temp targets are taken from the log as they
 * really were; they do not react to the replayed BG.
 */
internal class LoopReplay(private val input: ReplayInput, diaHours: Double, peakMinutes: Int) {

    private val determineBasal = DetermineBasalSMB(ReplayProfileUtil, NoFabricPrivacy)
    private val deltaCalculator = DeltaCalculator(SilentLogger)
    private val iCfg = ICfg("replay", peakMinutes, diaHours, 1.0)
    private val diaMs = (diaHours * 3600_000).toLong()
    private val diaMinutes = (diaHours * 60).toInt()
    private val scratch = BS(timestamp = 0L, utcOffset = 0L, amount = 1.0, type = BS.Type.NORMAL, iCfg = iCfg)

    private val base = input.midnights.first()
    private val dataStart = input.bgTime.first() - 6 * 3600_000L
    private val size = minuteOf(input.bgTime.last()) + 24 * 60 + diaMinutes + 2

    /** Activity per minute of 1 U given at minute 0, at the middle of each minute. */
    private val kernel = DoubleArray(diaMinutes) { j ->
        scratch.timestamp = 0L
        scratch.amount = 1.0
        iCfg.iobCalcForTreatment(scratch, ((j + 0.5) * 60_000).toLong()).activityContrib
    }

    private val observedDelivery = DoubleArray(size)
    private val observedActivity = DoubleArray(size)

    /** Scheduled basal per minute as the pump ran it. Looked up many times per loop run, so worked out once. */
    private val observedSchedule = DoubleArray(size)
    private val observedBasalAt: (Long) -> Double = { t -> observedSchedule[minuteOf(t).coerceIn(0, size - 1)] }
    private val maxCandidateBasal = input.candidateBasal.maxOf { it.second }
    private val candidateSens = input.setting("cand_sens")!!.toDouble()
    private val candidateCr = input.setting("cand_cr")!!.toDouble()

    init {
        for (m in observedSchedule.indices) observedSchedule[m] = input.observedBasalAt(base + m * 60_000L + 30_000L)
        var ti = 0
        for (m in minuteOf(dataStart) until minuteOf(input.bgTime.last()) + 1) {
            val t = base + m * 60_000L + 30_000L
            while (ti < input.temps.size && input.temps[ti].end <= t) ti++
            val temp = input.temps.getOrNull(ti)?.takeIf { it.ts <= t && t < it.end }
            observedDelivery[m] += (temp?.rate ?: observedSchedule[m]) / 60.0
        }
        for (b in input.boluses) if (b.ts >= dataStart) observedDelivery[minuteOf(b.ts)] += b.units
        for (m in observedDelivery.indices) if (observedDelivery[m] != 0.0) spread(observedActivity, m, observedDelivery[m])
    }

    private fun minuteOf(t: Long): Int = ((t - base) / 60_000L).toInt()

    private fun spread(activity: DoubleArray, minute: Int, units: Double) {
        val end = min(kernel.size, activity.size - minute)
        for (j in 0 until end) activity[minute + j] += units * kernel[j]
    }

    // ---------------------------------------------------------------- IOB, like the app

    private class Temp(val start: Long, val rate: Double, var end: Long, val plannedEnd: Long = end)

    private fun addTempIob(total: IobTotal, temp: Temp, now: Long, time: Long, basalAt: (Long) -> Double, ratio: Double) {
        val end = min(temp.end, now)
        val realDuration = ((min(time, end) - temp.start) / 60.0 / 1000).roundToInt()
        if (realDuration <= 0) return
        val intervals = ceil(realDuration / 5.0).toInt()
        val spacing = realDuration / intervals.toDouble()
        val diaAgo = time - diaMs
        for (j in 0 until intervals) {
            val calcDate = (temp.start + j * spacing * 60 * 1000 + 0.5 * spacing * 60 * 1000).toLong()
            if (calcDate > diaAgo && calcDate <= time) {
                val size = (temp.rate - basalAt(calcDate) * ratio) * spacing / 60.0
                if (size == 0.0) continue
                scratch.timestamp = calcDate
                scratch.amount = size
                val iob = iCfg.iobCalcForTreatment(scratch, time)
                total.basaliob += iob.iobContrib
                total.activity += iob.activityContrib
                total.netbasalinsulin += size
                if (size > 0) total.hightempinsulin += size
                total.netInsulin += size
            }
        }
    }

    private fun iobArray(
        now: Long, boluses: List<Pair<Long, Double>>, temps: List<Temp>, basalAt: (Long) -> Double, ratio: Double, ticks: Int = 48
    ): Array<IobTotal> {
        var first = boluses.binarySearchBy(now - diaMs - 60_000L) { it.first }.let { if (it < 0) -it - 1 else it }
        first = max(0, first)
        val near = temps.filter { min(it.end, now) > now - diaMs - 60_000L && it.start < now }
        val zeroTemp = Temp(now + 60_000L, 0.0, now + 60_000L + 240 * 60_000L)
        return Array(ticks) { i ->
            val time = now + i * 5 * 60_000L
            val bolusIob = IobTotal(time)
            for (b in first until boluses.size) {
                val (ts, units) = boluses[b]
                if (ts >= time || ts >= now) break
                if (ts < time - diaMs) continue
                scratch.timestamp = ts
                scratch.amount = units
                val iob = iCfg.iobCalcForTreatment(scratch, time)
                bolusIob.iob += iob.iobContrib
                bolusIob.activity += iob.activityContrib
                if (units > 0 && ts > bolusIob.lastBolusTime) bolusIob.lastBolusTime = ts
            }
            bolusIob.round()
            val basalIob = IobTotal(time)
            for (t in near) addTempIob(basalIob, t, now, time, basalAt, ratio)
            basalIob.round()
            val withZeroTemp = basalIob.copy()
            if (zeroTemp.start < time) {
                val zt = IobTotal(time)
                addTempIob(zt, zeroTemp, Long.MAX_VALUE, time, basalAt, ratio)
                withZeroTemp.plus(zt)
            }
            basalIob.iobWithZeroTemp = IobTotal.combine(bolusIob, withZeroTemp).round()
            IobTotal.combine(bolusIob, basalIob).round()
        }
    }

    // ---------------------------------------------------------------- helpers shared by check and replay

    private fun glucoseStatus(k: Int, bgAt: (Int) -> Double): GlucoseStatusSMB {
        val now = input.bgTime[k]
        val data = ArrayList<InMemoryGlucoseValue>()
        for (j in 0 until 10) {
            val i = k - j
            if (i < 0 || input.bgTime[i] < now - 45 * 60_000L) break
            data.add(InMemoryGlucoseValue(timestamp = input.bgTime[i], value = max(39.0, bgAt(i) + input.views[k][j])))
        }
        val d = deltaCalculator.calculateDeltas(data)
        return GlucoseStatusSMB(
            glucose = data[0].recalculated, noise = 0.0, delta = d.delta, shortAvgDelta = d.shortAvgDelta,
            longAvgDelta = d.longAvgDelta, date = now
        ).asRounded()
    }

    /** Bucketed BG as the AutoISF glucose status reads it, newest first, last 3 hours. */
    private fun bucketed(k: Int, bgAt: (Int) -> Double): MutableList<InMemoryGlucoseValue> {
        val now = input.bgTime[k]
        val out = ArrayList<InMemoryGlucoseValue>()
        var i = k
        while (i >= 0 && input.bgTime[i] >= now - 3 * 3600_000L) {
            val j = k - i
            val offset = if (j < 10) input.views[k][j] else input.views[i][0]
            out.add(InMemoryGlucoseValue(timestamp = input.bgTime[i], value = max(39.0, bgAt(i) + offset)))
            i--
        }
        return out
    }

    /** Deviation slopes like the autosens worker: from the last hour of 5 minute deviations. */
    private fun slopes(k: Int, avgDeviation: DoubleArray): Pair<Double, Double> {
        var fromMax = 0.0
        var fromMin = 999.0
        if (k < 16 || avgDeviation[k].isNaN()) return fromMax to fromMin
        val now = input.bgTime[k]
        val hourAgo = now + 10_000L - 3600_000L
        val start = input.bgTime.binarySearch(hourAgo).let { if (it >= 0) it else -it - 2 }
        if (start < 0 || hourAgo - input.bgTime[start] > 10 * 60_000L) return fromMax to fromMin
        var maxDev = 0.0
        var minDev = 999.0
        for (past in 1 until 12) {
            val i = start + past
            if (i >= k || avgDeviation[i].isNaN()) continue
            val slope = (avgDeviation[i] - avgDeviation[k]) / (input.bgTime[i] - now) * 1000 * 60 * 5
            if (avgDeviation[i] > maxDev) {
                fromMax = min(0.0, slope); maxDev = avgDeviation[i]
            }
            if (avgDeviation[i] < minDev) {
                fromMin = max(0.0, slope); minDev = avgDeviation[i]
            }
        }
        return fromMax to fromMin
    }

    private fun avgDeviation(k: Int, bgAt: (Int) -> Double, activity: Double, sens: Double): Double {
        if (k < 3) return Double.NaN
        val now = input.bgTime[k]
        if (now - input.bgTime[k - 3] > 17 * 60_000L) return Double.NaN
        val bg = bgAt(k) + input.views[k][0]
        val bg3 = bgAt(k - 3) + input.views[k][3]
        val bgi = -activity * sens * 5
        return (((bg - bg3) / 3 - bgi) * 1000).roundToInt() / 1000.0
    }

    /** COB, carbs and last carb time as the phone logged them (they come from the real BG); slopes from the replay. */
    private fun mealData(now: Long, run: LoggedRun?, lastBolusTime: Long, slopes: Pair<Double, Double>): MealData {
        val meal = MealData(mealCOB = run?.cob ?: 0.0, slopeFromMaxDeviation = slopes.first, slopeFromMinDeviation = slopes.second, lastBolusTime = lastBolusTime)
        if (run?.mealCarbs != null) {
            meal.carbs = run.mealCarbs
            meal.lastCarbTime = run.lastCarbAgeMinutes?.let { run.ts - (it * 60_000).toLong() } ?: 0L
            return meal
        }
        for ((ts, grams) in input.carbs) {
            if (ts > now - 6 * 3600_000L && ts <= now && grams != 0.0) {
                meal.carbs += grams
                if (ts > meal.lastCarbTime) meal.lastCarbTime = ts
            }
        }
        return meal
    }

    private fun isTempTarget(target: Double): Boolean {
        val frac = target - floor(target)
        return !(frac < 1e-6 || abs(frac - 0.1) < 1e-3)
    }

    private fun profile(
        minBg: Double, maxBg: Double, cr: Double, sens: Double, currentBasal: Double, maxDailyBasal: Double, tempTarget: Boolean,
        adjustTargets: Boolean, maxIob: Double, variableSens: Double, tdd: Double, uam: Boolean, smbAlways: Boolean, smbInterval: Int
    ) = OapsProfile(
        dia = 0.0, min_5m_carbimpact = 0.0, max_iob = maxIob, max_daily_basal = maxDailyBasal, max_basal = 10.0,
        min_bg = minBg, max_bg = maxBg, target_bg = (minBg + maxBg) / 2, carb_ratio = cr, sens = sens,
        autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = adjustTargets, resistance_lowers_target = adjustTargets, adv_target_adjustments = false,
        exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = false,
        remainingCarbsCap = 90, enableUAM = uam, A52_risk_enable = false, SMBInterval = smbInterval, enableSMB_with_COB = true,
        enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false, enableSMB_always = smbAlways,
        enableSMB_after_carbs = true, maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30, bolus_increment = 0.05,
        carbsReqThreshold = 1, current_basal = currentBasal, temptargetSet = tempTarget, autosens_max = 1.2,
        out_units = "mmol/L", lgsThreshold = null, variable_sens = variableSens, insulinDivisor = 65, TDD = tdd
    )

    private fun currentTemp(active: Temp?, now: Long): CurrentTemp =
        if (active != null && active.start <= now && active.end > now)
            CurrentTemp(
                duration = max(((active.plannedEnd - now) / 1000.0 / 60).roundToInt(), 0), rate = active.rate,
                minutesrunning = ((now - active.start) / 60.0 / 1000).roundToInt()
            )
        else CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null)

    /** TDD like `OpenAPSSMBPlugin.calculateRawDynIsf`, before the adjustment factor. */
    private fun tddFormula(now: Long, delivery: DoubleArray): Double {
        val m = minuteOf(now)
        fun sum(from: Int, to: Int): Double {
            var s = 0.0
            for (i in max(0, from) until min(to, delivery.size)) s += delivery[i]
            return s
        }

        val last4 = sum(m - 240, m)
        val last8to4 = sum(m - 480, m - 240)
        val last24 = sum(m - 1440, m)
        val today = input.midnightBefore(now)
        val days = ArrayList<Double>()
        var dayEnd = today
        for (d in 0 until 7) {
            val dayStart = input.midnightBefore(dayEnd - 1)
            if (dayStart < dataStart) break
            days.add(sum(minuteOf(dayStart), minuteOf(dayEnd)))
            dayEnd = dayStart
        }
        val tdd1D = days.firstOrNull() ?: last24
        val tdd7D = if (days.isEmpty()) last24 else days.average()
        val weighted = ((1.4 * last4) + (0.6 * last8to4)) * 3
        return weighted * 0.33 + tdd7D * 0.34 + tdd1D * 0.33
    }

    // ---------------------------------------------------------------- check against the log

    /** Rebuild the inputs of every logged SMB run from the real history and run determine_basal again. */
    fun checkAgainstLog(): List<String> {
        val rows = ArrayList<String>()
        rows.add(
            "ts\tbg_log\tbg_mine\tiob_log\tiob_mine\tev_log\tev_mine\treq_log\treq_mine\trate_log\trate_mine\tdur_log\tdur_mine" +
                "\tsmb_log\tsmb_mine\tdyn\ttdd_backsolved\ttdd_formula\tcur_rate"
        )
        val boluses = input.boluses.filter { it.ts >= dataStart }.map { it.ts to it.units }
        val temps = input.temps.map { Temp(it.ts, it.rate, it.end, it.ts + it.plannedMinutes * 60_000L) }
        val truthAt: (Int) -> Double = { input.truth[it] }
        val avgDev = DoubleArray(input.bgTime.size) { Double.NaN }
        var lastK = -1
        for (run in input.runs) {
            if (run.algorithm != "SMB" || run.basal == null) continue
            val now = run.ts
            val k = input.bgTime.binarySearch(now + 1000).let { if (it >= 0) it else -it - 2 }
            if (k < 0 || now - input.bgTime[k] > 6 * 60_000L) continue
            val sens = if (run.dynIsf) run.variableSens ?: candidateSens else run.profileSens ?: candidateSens
            // deviations for the readings since the last run
            for (i in max(lastK + 1, k - 16)..k) {
                val ti = input.bgTime[i]
                val act = iobArray(ti, boluses, temps, observedBasalAt, run.ratio, ticks = 1)[0].activity
                avgDev[i] = avgDeviation(i, truthAt, act, sens)
            }
            lastK = k
            val gs = glucoseStatus(k, truthAt)
            val iob = iobArray(now, boluses, temps, observedBasalAt, run.ratio)
            val active = temps.lastOrNull { it.start < now && it.end > now }
            val target = run.target ?: 99.0
            val tdd = if (run.dynIsf && run.variableSens != null) 1800 / (run.variableSens * ln(run.bg / 65 + 1)) else 0.0
            val rt = determineBasal.determine_basal(
                glucose_status = gs, currenttemp = currentTemp(active, now), iob_data_array = iob,
                profile = profile(
                    target, target, run.carbRatio ?: candidateCr, sens, run.basal, maxCandidateBasal, isTempTarget(target), false,
                    10.0, run.variableSens ?: 0.0, tdd, run.uam, run.smbAlways, run.smbInterval
                ),
                autosens_data = AutosensResult(ratio = run.ratio),
                meal_data = mealData(now, run, boluses.lastOrNull { it.first < now }?.first ?: 0L, slopes(k, avgDev)),
                microBolusAllowed = run.smbAllowed, currentTime = now, flatBGsDetected = false, dynIsfMode = run.dynIsf
            )
            fun f(v: Double?) = v?.let { "%.3f".format(it) } ?: "NA"
            rows.add(
                listOf(
                    now.toString(), f(run.bg), f(gs.glucose), f(run.iob), f(iob[0].iob), f(run.eventualBg), f(rt.eventualBG),
                    f(run.insulinReq), f(rt.insulinReq), f(run.rate), f(rt.rate), run.duration?.toString() ?: "NA",
                    rt.duration?.toString() ?: "NA", f(run.units), f(rt.units), if (run.dynIsf) "1" else "0", f(tdd),
                    f(tddFormula(now, observedDelivery)), f(active?.rate ?: run.basal)
                ).joinToString("\t")
            )
        }
        return rows
    }

    // ---------------------------------------------------------------- closed loop replay

    fun run(s: Scenario): ReplayResult {
        val n = input.bgTime.size
        var k0 = 0
        while (k0 < n && input.bgTime[k0] < max(s.from, input.bgTime[0] + 3 * 3600_000L)) k0++
        var k1 = n - 1
        while (k1 > k0 && input.bgTime[k1] > s.to) k1--
        val fromT = input.bgTime[k0]
        val fromMin = minuteOf(fromT)
        val pct = s.percentage / 100.0

        val delivery = observedDelivery.copyOf()
        for (m in fromMin until delivery.size) delivery[m] = 0.0
        val activity = DoubleArray(size)
        for (m in 0 until fromMin) if (delivery[m] != 0.0) spread(activity, m, delivery[m])

        val boluses = ArrayList<Pair<Long, Double>>()
        input.boluses.filter { it.ts in dataStart until fromT }.forEach { boluses.add(it.ts to it.units) }
        val temps = ArrayList<Temp>()
        input.temps.filter { it.ts < fromT && it.end > dataStart }.forEach { temps.add(Temp(it.ts, it.rate, min(it.end, fromT))) }
        var active: Temp? = null
        val manual = input.boluses.filter { !it.smb && it.ts >= fromT && it.ts <= input.bgTime[k1] }
        var manualIndex = 0

        val schedule =
            if (s.asRun && s.basalScale == 1.0 && pct == 1.0) observedSchedule
            else if (s.asRun) DoubleArray(size) { m -> Round.roundTo(observedSchedule[m] * s.basalScale * pct, 0.05) }
            else DoubleArray(size) { m -> Round.roundTo(input.candidateBasalAt(base + m * 60_000L + 30_000L) * s.basalScale * pct, 0.05) }
        val scheduledBasal: (Long) -> Double = { t -> schedule[minuteOf(t).coerceIn(0, size - 1)] }
        val candidateCrNow = candidateCr * s.crScale / pct
        val candidateSensNow = candidateSens * s.isfScale / pct

        val autoIsf = if (s.autoIsf) AutoIsfRunner(s.aisf + AutoIsfRunner.GLUCOSE_WEIGHTS.associateWith { w ->
            ((s.aisf[w] ?: AutoIsfRunner.GUIDE_START.getValue(w)).toDouble() * s.aisfScale).toString()
        }) else null
        val g = DoubleArray(n) { input.truth[it] }
        val bgAt: (Int) -> Double = { g[it] }
        val avgDev = DoubleArray(n) { Double.NaN }
        var smbUnits = 0.0
        val trace = ArrayList<String>()
        if (s.trace) trace.add("ts\ttruth\tsim\tdelivered_real\tdelivered_sim\tsmb\trate\tsens")
        var curMin = fromMin

        for (k in k0..k1) {
            val now = input.bgTime[k]
            val nowMin = minuteOf(now)
            // 1. deliver basal (temp or scheduled) and manual boluses up to now
            while (curMin < nowMin) {
                val t = base + curMin * 60_000L + 30_000L
                val temp = active?.takeIf { t >= it.start && t < it.end }
                val units = (temp?.rate ?: scheduledBasal(t)) / 60.0
                delivery[curMin] += units
                spread(activity, curMin, units)
                curMin++
            }
            if (active != null && active.end <= now) active = null
            while (manualIndex < manual.size && manual[manualIndex].ts < now) {
                val b = manual[manualIndex++]
                val units = Round.roundTo(b.units * manualScale(b, s, candidateCrNow, candidateSensNow), 0.05)
                if (units > 0) {
                    boluses.add(b.ts to units)
                    delivery[minuteOf(b.ts)] += units
                    spread(activity, minuteOf(b.ts), units)
                }
            }
            // 2. BG: the truth plus the effect of the insulin that differs from the real insulin
            if (k > k0) {
                var diff = 0.0
                for (m in minuteOf(input.bgTime[k - 1]) until nowMin) diff += activity[m] - observedActivity[m]
                g[k] = max(39.0, g[k - 1] + (input.truth[k] - input.truth[k - 1]) - s.trueIsf * diff)
            }
            // 3. inputs, as the plugin builds them
            val run = input.lastRunAtOrBefore(now + 60_000L, 15 * 60_000L)
            val loopTime = now + 30_000L
            val gs = glucoseStatus(k, bgAt)
            val ratio: Double
            val dyn: Boolean
            var sens: Double
            val cr: Double
            val minBg: Double
            val maxBg: Double
            val tempTarget: Boolean
            val smbAllowed: Boolean
            var tdd = 0.0
            var variableSens = 0.0
            val currentBasal = scheduledBasal(loopTime)
            if (s.asRun) {
                if (run == null || run.algorithm != "SMB") {
                    if (s.trace) trace.add(traceRow(now, k, g, delivery, nowMin, 0.0, active, 0.0))
                    continue
                }
                ratio = run.ratio
                dyn = run.dynIsf
                cr = (run.carbRatio ?: candidateCr) * s.crScale / pct
                minBg = run.target ?: 99.0
                maxBg = minBg
                tempTarget = isTempTarget(minBg)
                smbAllowed = run.smbAllowed
                sens = (run.profileSens ?: candidateSens) * s.isfScale / pct
                if (dyn && run.variableSens != null) {
                    tdd = 1800 / (run.variableSens * ln(run.bg / 65 + 1)) * pct / s.isfScale
                    variableSens = Round.roundTo(1800 / (tdd * ln(gs.glucose / 65 + 1)), 0.1)
                    sens = variableSens
                }
            } else {
                dyn = s.dynIsf
                ratio = if (dyn) 1.0 else run?.ratio ?: 1.0
                cr = candidateCrNow
                sens = candidateSensNow
                val logged = run?.target
                if (logged != null && isTempTarget(logged)) {
                    minBg = logged; maxBg = logged; tempTarget = true
                } else {
                    val (lo, hi) = input.candidateTargetAt(loopTime)
                    minBg = lo; maxBg = hi; tempTarget = false
                }
                smbAllowed = s.smbMinBg <= 0 || gs.glucose >= s.smbMinBg
                if (dyn) {
                    tdd = tddFormula(now, delivery) * s.dynFactor / 100.0 * pct / s.isfScale
                    variableSens = Round.roundTo(1800 / (tdd * ln(gs.glucose / 65 + 1)), 0.1)
                    sens = variableSens
                }
            }
            val uam = if (s.asRun) run?.uam ?: s.uam else s.uam
            val smbAlways = if (s.asRun) run?.smbAlways ?: true else true
            val smbInterval = if (s.asRun) run?.smbInterval ?: s.smbInterval else s.smbInterval
            val maxDailyBasal = if (s.asRun) maxCandidateBasal else maxCandidateBasal * s.basalScale * pct
            var rtAutoIsf: RT? = null
            if (autoIsf != null) {
                // AutoISF: profile ISF (no DynISF, no autosens) changed by the plugin's own factors
                val profileSens = (if (s.asRun) run?.takeIf { !it.dynIsf }?.profileSens ?: candidateSens else candidateSens) * s.isfScale / pct
                autoIsf.now = loopTime
                autoIsf.bucketed = bucketed(k, bgAt)
                autoIsf.tempTarget = if (tempTarget) TT(timestamp = loopTime - 60_000L, utcOffset = 0L, reason = TT.Reason.CUSTOM, highTarget = maxBg, lowTarget = minBg, duration = 3600_000L) else null
                val gsA = autoIsf.glucoseStatus.getGlucoseStatusData(allowOldData = false)
                if (gsA == null) {
                    if (s.trace) trace.add(traceRow(now, k, g, delivery, nowMin, 0.0, active, 0.0))
                    continue
                }
                val vSens = autoIsf.variableSens(profileSens, (minBg + maxBg) / 2)
                val iob = iobArray(loopTime, boluses, temps, scheduledBasal, 1.0)
                avgDev[k] = avgDeviation(k, bgAt, iob[0].activity, vSens)
                val p = autoIsf.profile(minBg, maxBg, cr, profileSens, vSens, currentBasal, maxDailyBasal, tempTarget, s.maxIob, uam, smbAlways, smbInterval, s.percentage.toInt())
                sens = vSens
                rtAutoIsf = autoIsf.determine(
                    gsA, currentTemp(active, loopTime), iob, p, AutosensResult(ratio = 1.0),
                    mealData(now, run, boluses.lastOrNull()?.first ?: 0L, slopes(k, avgDev)), smbAllowed, loopTime, s.percentage.toInt()
                )
            }
            val rt: RT = if (rtAutoIsf != null) rtAutoIsf else {
                val iob = iobArray(loopTime, boluses, temps, scheduledBasal, ratio)
                avgDev[k] = avgDeviation(k, bgAt, iob[0].activity, sens)
                determineBasal.determine_basal(
                    glucose_status = gs, currenttemp = currentTemp(active, loopTime), iob_data_array = iob,
                    profile = profile(
                        minBg, maxBg, cr, sens, currentBasal, maxDailyBasal,
                        tempTarget, !s.asRun, s.maxIob, variableSens, tdd,
                        uam, smbAlways, smbInterval
                    ),
                    autosens_data = AutosensResult(ratio = ratio),
                    meal_data = mealData(now, run, boluses.lastOrNull()?.first ?: 0L, slopes(k, avgDev)),
                    microBolusAllowed = smbAllowed, currentTime = loopTime, flatBGsDetected = false, dynIsfMode = dyn
                )
            }
            // 4. act like the loop and the pump: temp first, then the SMB
            val thisRun = run?.takeIf { it.ts >= now - 60_000L }
            val rate = rt.rate
            val duration = rt.duration
            if (rate != null && duration != null && !(s.blockTemps && thisRun?.tempFailed == true)) {
                active?.let { if (it.end > loopTime) it.end = loopTime }
                active = if (duration > 0) Temp(loopTime, Round.roundTo(max(0.0, rate), 0.05), loopTime + duration * 60_000L).also { temps.add(it) } else null
            }
            var smb = 0.0
            rt.units?.takeIf { !(s.blockSmb && thisRun?.smbFailed == true) }?.let { u ->
                smb = floor(u / 0.05 + 1e-6) * 0.05
                if (smb > 0) {
                    boluses.add(loopTime to smb)
                    delivery[minuteOf(loopTime)] += smb
                    spread(activity, minuteOf(loopTime), smb)
                    smbUnits += smb
                }
            }
            if (s.trace) trace.add(traceRow(now, k, g, delivery, nowMin, smb, active, sens))
        }
        return ReplayResult(s, metrics(k0, k1, g, delivery, smbUnits), trace)
    }

    private fun traceRow(now: Long, k: Int, g: DoubleArray, delivery: DoubleArray, nowMin: Int, smb: Double, active: Temp?, sens: Double): String {
        val prev = if (k > 0) minuteOf(input.bgTime[k - 1]) else nowMin
        var real = 0.0
        var sim = 0.0
        for (m in prev until nowMin) {
            real += observedDelivery[m]; sim += delivery[m]
        }
        return "%d\t%.1f\t%.1f\t%.3f\t%.3f\t%.2f\t%s\t%.1f".format(
            now, input.truth[k], g[k], real, sim, smb, active?.rate?.let { "%.2f".format(it) } ?: "NA", sens
        )
    }

    /** Meal boluses follow the I:C, other manual boluses the ISF, both relative to what was in force then. */
    private fun manualScale(b: LoggedBolus, s: Scenario, cr: Double, sens: Double): Double {
        val meal = input.carbs.any { abs(it.first - b.ts) <= 45 * 60_000L && it.second > 0 }
        if (s.asRun) return s.percentage / 100.0 / (if (meal) s.crScale else s.isfScale)
        val run = input.lastRunAtOrBefore(b.ts, 60 * 60_000L) ?: return 1.0
        return if (meal) (run.carbRatio ?: return 1.0) / cr
        else (if (run.dynIsf) run.variableSens else run.profileSens)?.let { it / sens } ?: 1.0
    }

    private fun metrics(k0: Int, k1: Int, g: DoubleArray, delivery: DoubleArray, smbUnits: Double): LinkedHashMap<String, Double> {
        val m = LinkedHashMap<String, Double>()
        fun stats(prefix: String, v: List<Double>) {
            val mean = v.average()
            val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
            m["${prefix}_mean"] = mean / 18.0182
            m["${prefix}_sd"] = sd / 18.0182
            m["${prefix}_cv"] = sd / mean * 100
            m["${prefix}_tir"] = v.count { it >= 70 && it <= 180 } * 100.0 / v.size
            m["${prefix}_tbr70"] = v.count { it < 70 } * 100.0 / v.size
            m["${prefix}_tbr54"] = v.count { it < 54 } * 100.0 / v.size
            m["${prefix}_tar180"] = v.count { it > 180 } * 100.0 / v.size
            m["${prefix}_tar250"] = v.count { it > 250 } * 100.0 / v.size
        }
        stats("sim", (k0..k1).map { g[it] })
        stats("real", (k0..k1).map { input.truth[it] })
        // Only where the real BG was 10 mmol/L or below: above that DynISF doses much harder and a fixed
        // trueIsf is least believable, so these numbers lean less on that assumption.
        val low = (k0..k1).filter { input.truth[it] <= 180.0 }
        stats("sim10", low.map { g[it] })
        stats("real10", low.map { input.truth[it] })
        m["share10"] = low.size * 100.0 / (k1 - k0 + 1)
        val days = (input.bgTime[k1] - input.bgTime[k0]) / 86_400_000.0
        var real = 0.0
        var sim = 0.0
        for (i in minuteOf(input.bgTime[k0]) until minuteOf(input.bgTime[k1])) {
            real += observedDelivery[i]; sim += delivery[i]
        }
        m["days"] = days
        m["real_insulin_per_day"] = real / days
        m["sim_insulin_per_day"] = sim / days
        m["sim_smb_per_day"] = smbUnits / days
        return m
    }
}

// ---------------------------------------------------------------- fakes for the two collaborators

internal object SilentLogger : AAPSLogger {

    override fun debug(message: String) {}
    override fun debug(enable: Boolean, tag: LTag, message: String) {}
    override fun debug(tag: LTag, message: String) {}
    override fun debug(tag: LTag, accessor: () -> String) {}
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun warn(tag: LTag, message: String) {}
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun info(tag: LTag, message: String) {}
    override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun error(tag: LTag, message: String) {}
    override fun error(tag: LTag, message: String, throwable: Throwable) {}
    override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun error(message: String) {}
    override fun error(message: String, throwable: Throwable) {}
    override fun error(format: String, vararg arguments: Any?) {}
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
}

private object NoFabricPrivacy : FabricPrivacy {

    override fun setUserProperty(key: String, value: String) {}
    override fun logCustom(event: String) {}
    override fun logCustom(name: String, params: Map<String, Long>) {}
    override fun logMessage(message: String) {}
    override fun logException(throwable: Throwable) {}
    override fun fabricEnabled(): Boolean = false
    override fun logWearException(wearException: EventData.WearException) {}
}

/** Only used for the text of the reason, so plain mmol/L numbers are enough. */
internal object ReplayProfileUtil : ProfileUtil {

    override val units: GlucoseUnit = GlucoseUnit.MMOL
    override val unitLabel: String = "mmol/L"
    private fun mmol(v: Double) = "%.1f".format(v / 18.0182)
    override fun fromMgdlToStringWithUnits(valueInMgdl: Double?): String = valueInMgdl?.let { mmol(it) } ?: ""
    override fun fromMgdlToUnits(valueInMgdl: Double, targetUnits: GlucoseUnit): Double = valueInMgdl / 18.0182
    override fun fromMmolToUnits(value: Double, targetUnits: GlucoseUnit): Double = value
    override fun valueInCurrentUnitsDetect(anyBg: Double): Double = anyBg
    override fun stringInCurrentUnitsDetect(anyBg: Double): String = anyBg.toString()
    override fun fromMgdlToStringInUnits(valueInMgdl: Double?, targetUnits: GlucoseUnit): String = valueInMgdl?.let { mmol(it) } ?: ""
    override fun fromMgdlToSignedStringInUnits(valueInMgdl: Double, targetUnits: GlucoseUnit): String = mmol(valueInMgdl)
    override fun isMgdl(anyBg: Double): Boolean = anyBg >= 39
    override fun isMmol(anyBg: Double): Boolean = anyBg < 39
    override fun unitsDetect(anyBg: Double): GlucoseUnit = if (anyBg >= 39) GlucoseUnit.MGDL else GlucoseUnit.MMOL
    override fun valueInUnitsDetect(anyBg: Double, targetUnits: GlucoseUnit): Double = anyBg
    override fun stringInUnitsDetect(anyBg: Double, targetUnits: GlucoseUnit): String = anyBg.toString()
    override fun convertToMgdlDetect(anyBg: Double): Double = if (anyBg < 39) anyBg * 18.0182 else anyBg
    override fun convertToMgdl(value: Double, sourceUnits: GlucoseUnit): Double = if (sourceUnits == GlucoseUnit.MMOL) value * 18.0182 else value
    override fun convertToMmol(value: Double, sourceUnits: GlucoseUnit): Double = if (sourceUnits == GlucoseUnit.MGDL) value / 18.0182 else value
    override fun toTargetRangeString(low: Double, high: Double, sourceUnits: GlucoseUnit, targetUnits: GlucoseUnit): String = "$low-$high"
    override fun getBasalProfilesDisplayable(profiles: Array<Profile.ProfileValue>, pumpType: PumpType): String = ""
}
