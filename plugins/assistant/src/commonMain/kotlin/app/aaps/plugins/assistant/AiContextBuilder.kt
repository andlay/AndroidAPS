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

    suspend fun build(topic: AiTopic, timestamp: Long): String {
        val now = dateUtil.now()
        val run = persistenceLayer.getApsResultCloseTo(timestamp)
        val root = buildJsonObject {
            putJsonObject("request") {
                put("topic", topic.name)
                put("time", dateUtil.dateAndTimeString(timestamp))
                put("minutesBeforeNow", (now - timestamp) / 60_000)
                put("userUnits", profileUtil.units.asText)
            }
            put("settings", settings())
            put("profile", profile(timestamp))
            put("loopRun", run?.let { loopRun(it) } ?: JsonPrimitive("no loop run within 5 minutes before this time"))
            put("recentLoopRuns", recentRuns(timestamp))
            put("bgReadings", bgReadings(timestamp))
            put("treatments", treatments(timestamp))
            put("autosens", autosens(timestamp, topic))
            // A free question can be about any time today: one compact line per loop run and treatment
            if (topic == AiTopic.GENERAL) put("last24h", last24h(timestamp))
        }
        return json.encodeToString(JsonObject.serializer(), root)
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

    private fun loopRun(r: APSResult): JsonObject = buildJsonObject {
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
        // Why a value was limited: max basal, max IOB, SMB minimum BG and so on
        r.inputConstraints?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("inputLimits", it) }
        r.rateConstraint?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("rateLimits", it) }
        r.smbConstraint?.getReasons()?.takeIf { it.isNotBlank() }?.let { put("smbLimits", it) }
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

    /** The 5-minute autosens records around the time: deviation, BGI, type, COB. */
    private fun autosens(t: Long, topic: AiTopic): JsonArray = buildJsonArray {
        val before = if (topic == AiTopic.SENSITIVITY || topic == AiTopic.COB) 60 else 30
        val ads = iobCobCalculator.ads
        var time = t - before * 60_000L
        while (time <= t + 10 * 60_000L) {
            ads.getAutosensDataAtTime(time)?.let { a ->
                add(buildJsonObject {
                    put("time", dateUtil.timeString(a.time))
                    put("bgMgdl", round1(a.bg))
                    put("deltaMgdl", round1(a.delta))
                    put("bgiMgdl", round1(a.bgi))
                    put("deviationMgdl", round1(a.deviation))
                    put("type", a.type)
                    put("pastSensitivity", a.pastSensitivity)
                    put("cob", round1(a.cob))
                    put("absorbedThis5minGrams", round1(a.this5MinAbsorption))
                    put("usedMinCarbsImpact", round1(a.usedMinCarbsImpact))
                    put("failOverToMinAbsorptionRate", a.failOverToMinAbsorptionRate)
                    put("uam", a.uam)
                    put("sensRatio", round1(a.autosensResult.ratio * 100) / 100)
                })
            }
            time += 5 * 60_000L
        }
    }

    private fun u(mgdl: Double): Double = round1(profileUtil.fromMgdlToUnits(mgdl))
    private fun round1(v: Double): Double = round(v * 10) / 10
}
