package app.aaps.plugins.assistant

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.ai.AiTopic
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.floor
import kotlin.math.round

/**
 * Collects the data a question needs, as JSON. Everything is read from what the loop already stored:
 * the saved loop runs (inputs and result), the autosens records, BG, treatments, the profile and the
 * settings. Read-only.
 *
 * The algorithm works in mg/dL. Values copied from a loop run stay in mg/dL and are marked so; values
 * this class formats itself are in the user's units.
 */
@Inject
class AiContextBuilder(
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil
) {

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    private companion object {

        /** naive_eventualBG as the loop writes it in its debug lines ("naive_eventualBG 72," or "naive_eventualBG: 72"). */
        val NAIVE = Regex("naive_eventualBG:? (-?[0-9.]+)")
    }

    suspend fun build(topic: AiTopic, timestamp: Long): String {
        val now = dateUtil.now()
        // A time in the future (a tap on the prediction or projected lines) has no loop run and no
        // history of its own. It is explained from the newest loop run, which drew those lines, and
        // the history is read around now.
        val future = timestamp > now
        val history = if (future) now else timestamp
        val run = if (future) persistenceLayer.getApsResults(now - 30 * 60_000L, now + 60_000L).maxByOrNull { it.date }
        else persistenceLayer.getApsResultCloseTo(timestamp)
        val root = buildJsonObject {
            putJsonObject("request") {
                put("topic", topic.name)
                put("time", dateUtil.dateAndTimeString(timestamp))
                put("minutesBeforeNow", (now - timestamp) / 60_000)
                put("userUnits", profileUtil.units.asText)
                if (future) {
                    put("isFuture", true)
                    put(
                        "note",
                        "This time is in the future, so no loop run or reading exists for it. It lies on the prediction " +
                            "and projected lines of the newest loop run (loopRun). Explain what that run predicts for this " +
                            "time and why (see predictionAtRequestedTime), not a loop decision at this time."
                    )
                }
            }
            put("settings", settings())
            put("profile", profile(timestamp))
            put("loopRun", run?.let { loopRun(it) } ?: JsonPrimitive(if (future) "no loop run in the last 30 minutes" else "no loop run within 5 minutes before this time"))
            if (future) run?.let { predictionAt(it, timestamp)?.let { at -> put("predictionAtRequestedTime", at) } }
            put("recentLoopRuns", recentRuns(history))
            put("bgReadings", bgReadings(history))
            put("treatments", treatments(history))
            put("autosens", autosens(history, topic))
            // A free question can be about any time today: one compact line per loop run and treatment
            if (topic == AiTopic.GENERAL) {
                put("last24h", last24h(history))
                put("autosens24h", autosens24h(history))
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Each prediction line of [run] at time [t]: the point (t - run time) / 5 min along the line, in
     * mg/dL and in the user's units. Null when the run has no lines.
     */
    private fun predictionAt(run: APSResult, t: Long): JsonObject? {
        val pred = (run.rawData() as? RT)?.predBGs ?: return null
        val minutes = ((t - run.date) / 60_000).toInt()
        val index = (minutes + 2) / 5
        return buildJsonObject {
            put("loopRunTime", dateUtil.timeString(run.date))
            put("minutesAfterLoopRun", minutes)
            listOf("IOB" to pred.IOB, "COB" to pred.COB, "aCOB" to pred.aCOB, "UAM" to pred.UAM, "ZT" to pred.ZT).forEach { (name, line) ->
                if (line.isNullOrEmpty()) return@forEach
                val value = line.getOrNull(index)
                putJsonObject(name) {
                    if (value != null) {
                        put("mgdl", value)
                        put("userUnits", u(value.toDouble()))
                    } else put("note", "this line ends ${(line.size - 1) * 5} minutes after the run, before this time")
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    /** Every loop setting by its code name, so the guide's names match. */
    private fun settings(): JsonObject = buildJsonObject {
        put("activeAlgorithm", activePlugin.activeAPS?.let { it::class.simpleName } ?: "none")
        val prefixes = listOf("Aps", "Autosens", "Absorption", "Safety")
        putJsonObject("boolean") { BooleanKey.entries.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach { put(it.name, preferences.get(it)) } }
        putJsonObject("int") { IntKey.entries.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach { put(it.name, preferences.get(it)) } }
        putJsonObject("double") { DoubleKey.entries.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach { put(it.name, preferences.get(it)) } }
        putJsonObject("bgInUserUnits") { UnitDoubleKey.entries.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach { put(it.name, preferences.get(it)) } }
    }

    // ---------------------------------------------------------------- profile

    private suspend fun profile(t: Long): JsonElement {
        val p = profileFunction.getProfile(t) ?: return JsonPrimitive("no profile")
        return buildJsonObject {
            put("percentage", p.percentage)
            put("basalUph", p.getBasal(t))
            put("carbRatio", p.getIc(t))
            put("isfProfile", u(p.getProfileIsfMgdl()))
            put("targetLow", u(p.getTargetLowMgdl(t)))
            put("note", "isf and targets in user units; the ISF the loop really used is loopRun.inputs.profile.sens (mg/dL)")
        }
    }

    // ---------------------------------------------------------------- loop run

    private suspend fun loopRun(r: APSResult): JsonObject = buildJsonObject {
        put("time", dateUtil.timeString(r.date))
        put("note", "values from the algorithm are in mg/dL (18 mg/dL = 1 mmol/L)")
        val rt = r.rawData() as? RT
        if (rt != null) {
            // The prediction arrays are long; a summary carries what the explanation needs
            val full = json.encodeToJsonElement(RT.serializer(), rt) as JsonObject
            put("result", JsonObject(full.filterKeys { it != "predBGs" }))
            rt.predBGs?.let { pred ->
                putJsonObject("predictionSummaryMgdl") {
                    listOf("IOB" to pred.IOB, "COB" to pred.COB, "aCOB" to pred.aCOB, "UAM" to pred.UAM, "ZT" to pred.ZT).forEach { (name, line) ->
                        if (!line.isNullOrEmpty()) put(name, summary(line))
                    }
                }
            }
        } else {
            put("reason", r.reason)
            put("rate", r.rate)
            put("duration", r.duration)
            put("smb", r.smb)
        }
        putJsonObject("inputs") {
            when (val gs = r.glucoseStatus) {
                is GlucoseStatusAutoIsf -> put("glucoseStatus", json.encodeToJsonElement(GlucoseStatusAutoIsf.serializer(), gs))
                is GlucoseStatusSMB     -> put("glucoseStatus", json.encodeToJsonElement(GlucoseStatusSMB.serializer(), gs))
                else                    -> Unit
            }
            r.iob?.let { put("iob", json.encodeToJsonElement(IobTotal.serializer(), it)) }
            r.currentTemp?.let { put("currentTemp", json.encodeToJsonElement(CurrentTemp.serializer(), it)) }
            r.mealData?.let { put("mealData", json.encodeToJsonElement(MealData.serializer(), it)) }
            r.autosensResult?.let { put("autosens", json.encodeToJsonElement(AutosensResult.serializer(), it)) }
            r.oapsProfile?.let { put("profile", json.encodeToJsonElement(OapsProfile.serializer(), it)) }
        }
        smbMath(r)?.let { put("smbMath", it) }
        // Why a value was limited: max basal, max IOB, SMB minimum BG and so on
        r.inputConstraints?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("inputLimits", it) }
        r.rateConstraint?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("rateLimits", it) }
        r.smbConstraint?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("smbLimits", it) }
    }

    /**
     * The SMB arithmetic of `DetermineBasalSMB` with this run's numbers, so the model can show why an
     * SMB had its size, why a high temp had its rate and why an SMB zero temp was set. Recomputed from
     * the saved run (mg/dL): the loop's own log lines stay the authority.
     */
    private suspend fun smbMath(r: APSResult): JsonObject? {
        val rt = r.rawData() as? RT ?: return null
        val p = r.oapsProfile ?: return null
        val basal = p.current_basal
        val insulinReq = rt.insulinReq ?: return null
        val sens = rt.variable_sens ?: p.sens
        val target = rt.targetBG ?: p.target_bg
        val iob = r.iob?.iob ?: 0.0
        val mealInsulinReq = (r.mealData?.mealCOB ?: 0.0) / p.carb_ratio
        val uamLimit = iob > mealInsulinReq && iob > 0
        val maxBolus = round(basal * (if (uamLimit) p.maxUAMSMBBasalMinutes else p.maxSMBBasalMinutes) / 60.0 * 10) / 10
        val step = p.bolus_increment
        return buildJsonObject {
            put("note", "recomputed from this run with the formulas in the GUIDE; mg/dL; the log lines are the authority")
            put("basalUph", basal)
            put("insulinReqU", insulinReq)
            put("bolusStepU", step)
            put("maxBolusU", maxBolus)
            put("maxBolusFrom", if (uamLimit) "ApsUamMaxMinutesOfBasalToLimitSmb (IOB > COB / CR)" else "ApsMaxMinutesOfBasalToLimitSmb")
            if (insulinReq > 0) {
                put("halfInsulinReqU", round(insulinReq / 2 * 1000) / 1000)
                // Same arithmetic as the loop, so a value on a step boundary rounds the same way
                val roundTo = 1 / step
                put("smbSizeU", round(floor(minOf(insulinReq / 2, maxBolus) * roundTo) / roundTo * 1000) / 1000)
                put("highTempRateUph", round((basal + 2 * insulinReq) * 100) / 100)
            }
            val naive = rt.consoleError.orEmpty().firstNotNullOfOrNull { NAIVE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            val minIob = minIobPredBg(rt, r.date)
            naive?.let { put("naiveEventualBgMgdl", it) }
            minIob?.let { put("minIOBPredBgMgdl", it) }
            put("targetMgdl", target)
            put("isfMgdl", sens)
            if (naive != null && minIob != null && sens > 0 && basal > 0) {
                val worstCase = (target - (naive + minIob) / 2.0) / sens
                put("worstCaseInsulinReqU", round(worstCase * 100) / 100)
                put("smbZeroTempMinutesWanted", round(60 * worstCase / basal))
            }
        }
    }

    /** Lowest point of the IOB line after the insulin peak, as the loop counts it for minIOBPredBG. */
    private suspend fun minIobPredBg(rt: RT, runTime: Long): Double? {
        val line = rt.predBGs?.IOB ?: return null
        val peakMinutes = (profileFunction.getProfile(runTime)?.iCfg?.insulinPeakTime ?: (75 * 60_000L)) / 60_000.0
        val from = (peakMinutes / 5).toInt() + 1
        return line.drop(from).minOrNull()?.toDouble()
    }

    /** Value now, at 30/60/120 min, lowest, last and length (5 min steps). */
    private fun summary(line: List<Int>): JsonObject = buildJsonObject {
        put("now", line[0])
        line.getOrNull(6)?.let { put("in30min", it) }
        line.getOrNull(12)?.let { put("in60min", it) }
        line.getOrNull(24)?.let { put("in120min", it) }
        put("lowest", line.min())
        put("last", line.last())
        put("minutes", (line.size - 1) * 5)
    }

    private suspend fun recentRuns(t: Long): JsonArray = buildJsonArray {
        persistenceLayer.getApsResults(t - 45 * 60_000L, t + 60_000L).sortedBy { it.date }.takeLast(9).forEach { r ->
            val rt = r.rawData() as? RT
            add(buildJsonObject {
                put("time", dateUtil.timeString(r.date))
                rt?.bg?.let { put("bgMgdl", it) }
                rt?.eventualBG?.let { put("eventualBgMgdl", it) }
                put("rate", r.rate)
                put("duration", r.duration)
                put("smb", r.smb)
                put("reason", r.reason.take(220))
            })
        }
    }

    /** "time bg eventualBG rate/duration smb" per loop run, plus every bolus and carb entry, for 24 h. */
    private suspend fun last24h(t: Long): JsonObject = buildJsonObject {
        val from = t - 24 * 3_600_000L
        put("note", "loop run lines: time | BG | eventual BG (user units) | temp rate U/h / minutes | SMB U")
        put("loopRuns", buildJsonArray {
            persistenceLayer.getApsResults(from, t + 60_000L).sortedBy { it.date }.forEach { r ->
                val rt = r.rawData() as? RT
                val bg = rt?.bg?.let { u(it).toString() } ?: "-"
                val eventual = rt?.eventualBG?.let { u(it).toString() } ?: "-"
                add(JsonPrimitive("${dateUtil.timeString(r.date)} | $bg | $eventual | ${r.rate}/${r.duration} | ${r.smb}"))
            }
        })
        put("boluses", buildJsonArray {
            persistenceLayer.getBolusesFromTimeToTime(from, t, true).forEach {
                add(JsonPrimitive("${dateUtil.timeString(it.timestamp)} ${it.amount} U ${if (it.type == BS.Type.SMB) "SMB" else it.type.name}"))
            }
        })
        put("carbs", buildJsonArray {
            persistenceLayer.getCarbsFromTimeToTimeExpanded(from, t, true).forEach {
                add(JsonPrimitive("${dateUtil.timeString(it.timestamp)} ${it.amount} g"))
            }
        })
    }

    // ---------------------------------------------------------------- BG, treatments, autosens

    private suspend fun bgReadings(t: Long): JsonArray = buildJsonArray {
        persistenceLayer.getBgReadingsDataFromTimeToTime(t - 90 * 60_000L, t + 30 * 60_000L, true).forEach {
            add(buildJsonObject { put("time", dateUtil.timeString(it.timestamp)); put("bg", u(it.value)) })
        }
    }

    private suspend fun treatments(t: Long): JsonObject = buildJsonObject {
        put("boluses", buildJsonArray {
            persistenceLayer.getBolusesFromTimeToTime(t - 4 * 3_600_000L, t + 30 * 60_000L, true).forEach {
                add(buildJsonObject {
                    put("time", dateUtil.timeString(it.timestamp))
                    put("units", it.amount)
                    put("type", if (it.type == BS.Type.SMB) "SMB (loop)" else it.type.name)
                })
            }
        })
        put("carbs", buildJsonArray {
            persistenceLayer.getCarbsFromTimeToTimeExpanded(t - 6 * 3_600_000L, t + 30 * 60_000L, true).forEach {
                add(buildJsonObject { put("time", dateUtil.timeString(it.timestamp)); put("grams", it.amount) })
            }
        })
    }

    /**
     * The 5-minute autosens records around the time, with what decides their group (and so the colour
     * of the DEV bar): COB, IOB, the basal rate and the meal counter.
     */
    private suspend fun autosens(t: Long, topic: AiTopic): JsonArray = buildJsonArray {
        val before = if (topic == AiTopic.SENSITIVITY || topic == AiTopic.COB || topic == AiTopic.DEVIATIONS) 60 else 30
        val ads = iobCobCalculator.ads
        var time = t - before * 60_000L
        while (time <= t + 10 * 60_000L) {
            ads.getAutosensDataAtTime(time)?.let { a ->
                val profile = profileFunction.getProfile(a.time)
                val basal = profile?.getBasal(a.time)
                val iob = profile?.let { iobCobCalculator.calculateFromTreatmentsAndTemps(a.time, it).iob }
                add(buildJsonObject {
                    put("time", dateUtil.timeString(a.time))
                    put("bgMgdl", round1(a.bg))
                    put("deltaMgdl", round1(a.delta))
                    put("bgiMgdl", round1(a.bgi))
                    put("deviationMgdl", round1(a.deviation))
                    put("group", a.type)
                    put("graphColour", colourOf(a.type, a.pastSensitivity))
                    put("pastSensitivity", a.pastSensitivity)
                    put("cob", round1(a.cob))
                    put("absorbing", a.absorbing)
                    put("mealCarbs", round1(a.mealCarbs))
                    put("mealStartCounter", a.mealStartCounter)
                    put("uamFlag", a.uam)
                    iob?.let { put("iobU", round(it * 100) / 100) }
                    basal?.let { put("basalUph", it); put("twiceBasal", round(2 * it * 100) / 100) }
                    put("absorbedThis5minGrams", round1(a.this5MinAbsorption))
                    put("usedMinCarbsImpact", round1(a.usedMinCarbsImpact))
                    put("failOverToMinAbsorptionRate", a.failOverToMinAbsorptionRate)
                    put("sensRatio", round1(a.autosensResult.ratio * 100) / 100)
                })
            }
            time += 5 * 60_000L
        }
    }

    /**
     * One short line per 5-minute autosens record for 24 hours, so a question about any time today
     * can be answered: "time bg dev bgi group colour iob/basal cob" (BG values in user units).
     */
    private suspend fun autosens24h(t: Long): JsonObject = buildJsonObject {
        put("format", "time | bg | deviation | bgi | group | DEV colour | iob U / basal U/h | cob g  (bg, deviation, bgi in user units)")
        put("records", buildJsonArray {
            val ads = iobCobCalculator.ads
            var time = t - 24 * 3_600_000L
            while (time <= t) {
                ads.getAutosensDataAtTime(time)?.let { a ->
                    val profile = profileFunction.getProfile(a.time)
                    val iob = profile?.let { round(iobCobCalculator.calculateFromTreatmentsAndTemps(a.time, it).iob * 100) / 100 }
                    val basal = profile?.getBasal(a.time)
                    add(JsonPrimitive("${dateUtil.timeString(a.time)} | ${u(a.bg)} | ${u(a.deviation)} | ${u(a.bgi)} | ${a.type} | ${colourOf(a.type, a.pastSensitivity)} | ${iob ?: "?"} / ${basal ?: "?"} | ${round1(a.cob)}"))
                }
                time += 5 * 60_000L
            }
        })
    }

    /** The DEV bar colour, the same rule as the graph (`PrepareGraphDataRunner`). */
    private fun colourOf(type: String, pastSensitivity: String): String = when {
        type == "uam"           -> "yellow"
        type == "csf"           -> "grey"
        pastSensitivity == "C"  -> "grey"
        pastSensitivity == "+"  -> "green"
        pastSensitivity == "-"  -> "red"
        else                    -> "black"
    }

    private fun u(mgdl: Double): Double = round1(profileUtil.fromMgdlToUnits(mgdl))
    private fun round1(v: Double): Double = round(v * 10) / 10
}
