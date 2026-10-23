package app.aaps.plugins.aps.mpc

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.target
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MPC, the meal-aware controller from the closed-loop replay, as an APS plugin.
 *
 * Alpha build only, and only with the virtual pump. The alpha build runs the loop in shadow mode (see
 * `LoopPlugin.invoke`): the result is stored and shown, never enacted. The insulin it reasons about is the
 * real insulin the dosing app uploads to Nightscout, which the alpha build always takes in.
 *
 * Inputs are built the same way as in the replay, so the [MpcController] port sees what it was tuned on:
 * the smoothed 5 min BG history and the activity of past insulin on the oref curve (boluses, and temp basals
 * as net insulin against the profile basal, in 5 min parts up to now).
 */
@SingleIn(AppScope::class)
@Inject
class MpcPlugin(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    override val rh: TextResolver,
    private val config: Config,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    preferences: Preferences,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: () -> APSResult,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin -> MpcComposeContent(plugin as MpcPlugin, dateUtil) }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.mpc)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.ALPHA }
        .description(ApsStrings.description_mpc),
    ownPreferences = MpcIntKey.entries + MpcDoubleKey.entries + MpcDoubleNonKey.entries,
    aapsLogger, rh, preferences, notificationManager
), APS {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB   // stored like an SMB result; the reason says MPC
    override var lastAPSResult: APSResult? = null

    private val controller = MpcController()

    /** When the HbA1c aim last moved the low line (in memory: after a restart it moves once more straight away) */
    private var lastAimRun = 0L

    private val _details = MutableStateFlow<MpcDetails?>(null)

    /** What the last run saw and decided, for the MPC screen */
    val details: StateFlow<MpcDetails?> = _details

    /** The meal shape as set in the preferences */
    fun readableShape() = MpcShapeSettings(
        peakPosition = preferences.get(MpcDoubleKey.PeakPosition),
        halfWidth = preferences.get(MpcDoubleKey.HalfWidth),
        typicalMinutes = preferences.get(MpcDoubleKey.RiseLength),
        lengthSpreadPct = preferences.get(MpcDoubleKey.RiseLengthSpread),
        typicalGrams = preferences.get(MpcDoubleKey.TypicalMeal),
        sizeSpreadPct = preferences.get(MpcDoubleKey.MealSizeSpread),
        readinessPct = preferences.get(MpcIntKey.MealReadiness).toDouble(),
        tailHalfLifeMinutes = preferences.get(MpcDoubleKey.TailHalfLife)
    )

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator")
        lastAPSResult = null
        if (!config.ALPHA) return@withContext abort(rh.gs(ApsStrings.mpc_alpha_only))
        if (activePlugin.activePump.selectedActivePump() !is VirtualPump) return@withContext abort(rh.gs(ApsStrings.mpc_virtual_pump_only))
        val profile = profileFunction.getProfile() ?: return@withContext abort(rh.gs(CoreUiStrings.no_profile_set))
        val glucoseStatus = getGlucoseStatusData(false) ?: return@withContext abort(rh.gs(ApsStrings.mpc_no_recent_bg))
        val buckets = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return@withContext abort(rh.gs(ApsStrings.mpc_no_recent_bg))
        val now = dateUtil.now()
        val t0 = buckets.firstOrNull()?.timestamp ?: return@withContext abort(rh.gs(ApsStrings.mpc_no_recent_bg))
        if (now - t0 > 10 * MINUTE) return@withContext abort(rh.gs(ApsStrings.mpc_no_recent_bg))

        // BG history newest first, one value per 5 min, NaN where there is none
        val view = DoubleArray(VIEW_SIZE) { j ->
            val at = t0 - j * STEP_MS
            buckets.firstOrNull { abs(it.timestamp - at) <= STEP_MS / 2 }?.recalculated?.takeIf { it > 39 } ?: Double.NaN
        }
        if (!(view[0] > 39 && view[1] > 39 && view[3] > 39)) return@withContext abort(rh.gs(ApsStrings.mpc_no_recent_bg))

        val doses = insulinDoses(t0, profile)
        val pastActivity = DoubleArray(VIEW_SIZE - 1) { j -> activityAt(doses, t0 - j * STEP_MS, t0 - j * STEP_MS) }
        val futureActivity = DoubleArray(MpcController.HORIZON + 1) { j -> activityAt(doses, t0 + j * STEP_MS, t0) }
        val iob = Round.roundTo(doses.sumOf { MpcController.CURVE.iob(it.second, (t0 - it.first) / 60000.0) }, 0.001)

        val target = persistenceLayer.getTemporaryTargetActiveAt(now)?.target() ?: profile.getTargetMgdl()
        val isfProfile = profile.getIsfMgdl("MpcPlugin")
        val basal = profile.getBasal()
        val lowLine = aimLowLine(now)
        val settings = MpcController.Settings(
            lowLine = lowLine,
            highLine = preferences.get(MpcIntKey.HighLine).toDouble(),
            lowWeight = preferences.get(MpcIntKey.LowWeight).toDouble(),
            roomBoost = preferences.get(MpcIntKey.RoomBoost) / 100.0,
            trendRoomMinutes = preferences.get(MpcIntKey.TrendRoom).toDouble(),
            secondStageMinutes = preferences.get(MpcIntKey.SecondStage),
            maxSmb = preferences.get(MpcDoubleKey.MaxSmb),
            maxIob = preferences.get(MpcDoubleKey.MaxIob),
            shape = readableShape().toShape(isfProfile, profile.getIc()),
            stepDownMax = preferences.get(MpcIntKey.StepDownMax).toDouble()
        )
        val d = controller.step(t0 / STEP_MS, view, pastActivity, futureActivity, iob, basal, target, isfProfile, settings)
        val isf = isfProfile / MpcModel.aisfFactor(MpcModel.aisfStatus(view), target)
        val pm = d.weightedMealChance
        val expected = DoubleArray(d.predictedMeal.size) { k -> pm * d.predictedMeal[k] + (1 - pm) * d.predictedFalseStart[k] }
        val eventual = expected.last()
        val temp = when {
            d.rate == 0.0      -> "zero temp"
            d.rate > basal     -> "high temp ${Round.roundTo(d.rate, 0.05)} U/h"
            else               -> "normal basal"
        }
        val reason = "MPC (shadow): meal chance ${(d.mealChance * 100).roundToInt()}%, " +
            "${(d.weightedMealChance * 100).roundToInt()}% with room; ISF ${isf.roundToInt()} mg/dL/U; IOB $iob U; " +
            "SMB ${d.units} U, $temp for ${d.duration} min"
        val rt = RT(
            algorithm = algorithm,
            runningDynamicIsf = false,
            timestamp = now,
            bg = view[0],
            tick = (view[0] - view[1]).roundToInt().let { if (it >= 0) "+$it" else "$it" },
            eventualBG = eventual,
            targetBG = target,
            insulinReq = d.units,
            units = d.units,
            deliverAt = now,
            reason = StringBuilder(reason),
            duration = d.duration,
            rate = d.rate,
            predBGs = Predictions(
                mpcMeal = listOf(view[0].roundToInt()) + d.predictedMeal.map { it.roundToInt() },
                mpcFalseStart = listOf(view[0].roundToInt()) + d.predictedFalseStart.map { it.roundToInt() },
                mpcExpected = listOf(view[0].roundToInt()) + expected.map { it.roundToInt() }
            ),
            IOB = iob,
            variable_sens = isf,
            smbEnabled = view[0] >= settings.smbMinBg
        )
        lastAPSResult = apsResultProvider().with(rt).also { it.glucoseStatus = glucoseStatus }
        lastAPSRun = now
        _details.value = MpcDetails(
            time = t0, bg = view[0], units = d.units, rate = d.rate, duration = d.duration, basal = basal, iob = iob,
            isfProfile = isfProfile, isf = isf, mealChance = d.mealChance, weightedMealChance = pm, room = d.room,
            lowLine = settings.lowLine - d.stepDown, highLine = settings.highLine - d.stepDown,
            setLowLine = preferences.get(MpcIntKey.LowLine).toDouble(), targetHbA1c = preferences.get(MpcDoubleKey.TargetHbA1c), stepDown = d.stepDown,
            predictedMeal = d.predictedMeal, predictedFalseStart = d.predictedFalseStart, predictedExpected = expected,
            pastDev = d.futures.dev, pastFitted = d.futures.fitted,
            futureMeal = d.futures.meal.copyOf(MpcController.HORIZON), futureFalseStart = d.futures.non.copyOf(MpcController.HORIZON),
            impactAge = d.futures.impactAge, afterPeak = d.futures.afterPeak, options = d.options, reason = reason,
            mmol = profileFunction.getUnits() == GlucoseUnit.MMOL, aisfFactor = isfProfile / isf
        )
        aapsLogger.debug(LTag.APS, "MPC result: $reason")
        rxBus.send(EventAPSCalculationFinished())
        rxBus.send(EventOpenAPSUpdateGui())
    }

    /**
     * The low line to use. With a target HbA1c set, an outer loop moves it once an hour, as in the replay prototype:
     * the mean BG of the last 7 days is compared with the mean that HbA1c stands for (ADAG: 28.7 x HbA1c - 46.7),
     * and the line moves 0.01 mg/dL per mg/dL of difference. With more than 1 % of readings below 70 mg/dL it
     * never moves down and goes up at least 2 mg/dL. It stays within 10 below and 25 above the set low line.
     *
     * In shadow mode the BG it learns from is the BG of the app that really doses, not of MPC.
     */
    private suspend fun aimLowLine(now: Long): Double {
        val set = preferences.get(MpcIntKey.LowLine).toDouble()
        val a1c = preferences.get(MpcDoubleKey.TargetHbA1c)
        if (a1c <= 0) {
            preferences.put(MpcDoubleNonKey.AimLowLine, 0.0)
            return set
        }
        val min = set - AIM_BELOW
        val max = set + AIM_ABOVE
        val stored = preferences.get(MpcDoubleNonKey.AimLowLine).takeIf { it > 0 } ?: set
        var line = stored.coerceIn(min, max)
        if (now - lastAimRun >= 60 * MINUTE) {
            val readings = persistenceLayer.getBgReadingsDataFromTime(now - AIM_WINDOW, true).map { it.value }.filter { it > 39 }
            if (readings.size > AIM_MIN_READINGS) {
                lastAimRun = now
                val mean = readings.average()
                val lows = 100.0 * readings.count { it < 70 } / readings.size
                var step = AIM_GAIN * (mean - (28.7 * a1c - 46.7))
                if (lows > AIM_LOWS_PCT) step = min(step, -2.0)
                line = (line - step).coerceIn(min, max)
            }
        }
        if (line != stored) preferences.put(MpcDoubleNonKey.AimLowLine, line)
        return line
    }

    private fun abort(message: String) {
        aapsLogger.debug(LTag.APS, message)
        rxBus.send(EventResetOpenAPSGui(message))
    }

    /** Insulin given before [t0] within the insulin's life: (time, units). Temp basals count as net insulin against the profile basal. */
    private suspend fun insulinDoses(t0: Long, profile: Profile): List<Pair<Long, Double>> {
        val life = (MpcController.CURVE.td * 60000).toLong()
        val from = t0 - life
        val doses = persistenceLayer.getBolusesFromTime(from, true)
            .filter { it.isValid && it.type != BS.Type.PRIMING && it.timestamp <= t0 }
            .map { it.timestamp to it.amount }
            .toMutableList()
        var end = t0
        while (end - STEP_MS > from) {
            val mid = end - STEP_MS / 2
            val basal = profile.getBasal(mid)
            val rate = processedTbrEbData.getTempBasalIncludingConvertedExtended(mid)?.convertedToAbsolute(mid, profile) ?: basal
            val net = (rate - basal) * 5 / 60
            if (net != 0.0) doses += mid to net
            end -= STEP_MS
        }
        return doses
    }

    /** Activity (U/min) at [time] of the doses given up to [givenUpTo], rounded as the oref IOB code does. */
    private fun activityAt(doses: List<Pair<Long, Double>>, time: Long, givenUpTo: Long): Double =
        Round.roundTo(doses.filter { it.first <= givenUpTo && it.first <= time }.sumOf { MpcController.CURVE.activity(it.second, (time - it.first) / 60000.0) }, 0.0001)

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "mpc_settings",
        title = ApsStrings.mpc,
        items = listOf(
            PreferenceSubScreenDef(
                key = "mpc_safety",
                title = ApsStrings.mpc_settings_safety,
                items = listOf(MpcIntKey.LowLine, MpcIntKey.HighLine, MpcDoubleKey.MaxSmb, MpcDoubleKey.MaxIob)
            ),
            PreferenceSubScreenDef(
                key = "mpc_aim",
                title = ApsStrings.mpc_settings_aim,
                items = listOf(MpcDoubleKey.TargetHbA1c, MpcIntKey.StepDownMax)
            ),
            PreferenceSubScreenDef(
                key = "mpc_meal_call",
                title = ApsStrings.mpc_settings_meal_call,
                items = listOf(MpcIntKey.RoomBoost, MpcIntKey.TrendRoom, MpcIntKey.MealReadiness)
            ),
            PreferenceSubScreenDef(
                key = "mpc_meal_shape",
                title = ApsStrings.mpc_settings_meal_shape,
                items = listOf(
                    MpcDoubleKey.TypicalMeal, MpcDoubleKey.MealSizeSpread, MpcDoubleKey.RiseLength, MpcDoubleKey.RiseLengthSpread,
                    MpcDoubleKey.PeakPosition, MpcDoubleKey.HalfWidth, MpcDoubleKey.TailHalfLife
                )
            ),
            PreferenceSubScreenDef(
                key = "mpc_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(MpcIntKey.LowWeight, MpcIntKey.SecondStage)
            )
        ),
        icon = pluginDescription.icon
    )

    private companion object {

        const val MINUTE = 60_000L
        const val STEP_MS = 5 * MINUTE
        const val VIEW_SIZE = 26
        const val AIM_WINDOW = 7 * 24 * 60 * MINUTE
        const val AIM_MIN_READINGS = 144
        const val AIM_GAIN = 0.01
        const val AIM_LOWS_PCT = 1.0
        const val AIM_BELOW = 10.0
        const val AIM_ABOVE = 25.0
    }
}

/** What one MPC run saw and decided, for the MPC screen. BG in mg/dL; carb effect in mg/dL per 5 min. */
class MpcDetails(
    val time: Long,
    val bg: Double,
    val units: Double,
    val rate: Double,
    val duration: Int,
    val basal: Double,
    val iob: Double,
    val isfProfile: Double,
    val isf: Double,
    val aisfFactor: Double,
    val mealChance: Double,
    val weightedMealChance: Double,
    val room: Double,
    /** The lines in use: after the HbA1c aim and the step-down */
    val lowLine: Double,
    val highLine: Double,
    /** The low line as set by the user */
    val setLowLine: Double,
    /** 0 = off */
    val targetHbA1c: Double,
    /** How far the step-down has lowered both lines now, mg/dL */
    val stepDown: Double,
    /** Every 5 min from 5 min ahead, 3 h */
    val predictedMeal: DoubleArray,
    val predictedFalseStart: DoubleArray,
    val predictedExpected: DoubleArray,
    /** Newest first */
    val pastDev: DoubleArray,
    val pastFitted: DoubleArray,
    /** From 5 min ahead */
    val futureMeal: DoubleArray,
    val futureFalseStart: DoubleArray,
    val impactAge: Int,
    val afterPeak: Boolean,
    val options: List<MpcController.Option>,
    val reason: String,
    val mmol: Boolean
)
