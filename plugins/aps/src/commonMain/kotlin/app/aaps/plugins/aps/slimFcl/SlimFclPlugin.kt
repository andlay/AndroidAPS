package app.aaps.plugins.aps.slimFcl

import app.aaps.core.data.model.BS
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
import app.aaps.plugins.aps.compose.OpenAPSComposeContent
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Slim FCL, the meal-aware controller from the closed-loop replay, as an APS plugin.
 *
 * Alpha build only, and only with the virtual pump. The alpha build runs the loop in shadow mode (see
 * `LoopPlugin.invoke`): the result is stored and shown, never enacted. The insulin it reasons about is the
 * real insulin the dosing app uploads to Nightscout, which the alpha build always takes in.
 *
 * Inputs are built the same way as in the replay, so the [SlimFclController] port sees what it was tuned on:
 * the smoothed 5 min BG history and the activity of past insulin on the oref curve (boluses, and temp basals
 * as net insulin against the profile basal, in 5 min parts up to now).
 */
@SingleIn(AppScope::class)
@Inject
class SlimFclPlugin(
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
        .composeContent { plugin ->
            OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.slim_fcl)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.ALPHA }
        .description(ApsStrings.description_slim_fcl),
    ownPreferences = SlimFclIntKey.entries + SlimFclDoubleKey.entries,
    aapsLogger, rh, preferences, notificationManager
), APS {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB   // stored like an SMB result; the reason says Slim FCL
    override var lastAPSResult: APSResult? = null

    private val controller = SlimFclController()

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator")
        lastAPSResult = null
        if (!config.ALPHA) return@withContext abort(rh.gs(ApsStrings.slim_fcl_alpha_only))
        if (activePlugin.activePump.selectedActivePump() !is VirtualPump) return@withContext abort(rh.gs(ApsStrings.slim_fcl_virtual_pump_only))
        val profile = profileFunction.getProfile() ?: return@withContext abort(rh.gs(CoreUiStrings.no_profile_set))
        val glucoseStatus = getGlucoseStatusData(false) ?: return@withContext abort(rh.gs(ApsStrings.slim_fcl_no_recent_bg))
        val buckets = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return@withContext abort(rh.gs(ApsStrings.slim_fcl_no_recent_bg))
        val now = dateUtil.now()
        val t0 = buckets.firstOrNull()?.timestamp ?: return@withContext abort(rh.gs(ApsStrings.slim_fcl_no_recent_bg))
        if (now - t0 > 10 * MINUTE) return@withContext abort(rh.gs(ApsStrings.slim_fcl_no_recent_bg))

        // BG history newest first, one value per 5 min, NaN where there is none
        val view = DoubleArray(VIEW_SIZE) { j ->
            val at = t0 - j * STEP_MS
            buckets.firstOrNull { abs(it.timestamp - at) <= STEP_MS / 2 }?.recalculated?.takeIf { it > 39 } ?: Double.NaN
        }
        if (!(view[0] > 39 && view[1] > 39 && view[3] > 39)) return@withContext abort(rh.gs(ApsStrings.slim_fcl_no_recent_bg))

        val doses = insulinDoses(t0, profile)
        val pastActivity = DoubleArray(VIEW_SIZE - 1) { j -> activityAt(doses, t0 - j * STEP_MS, t0 - j * STEP_MS) }
        val futureActivity = DoubleArray(SlimFclController.HORIZON + 1) { j -> activityAt(doses, t0 + j * STEP_MS, t0) }
        val iob = Round.roundTo(doses.sumOf { SlimFclController.CURVE.iob(it.second, (t0 - it.first) / 60000.0) }, 0.001)

        val target = persistenceLayer.getTemporaryTargetActiveAt(now)?.target() ?: profile.getTargetMgdl()
        val isfProfile = profile.getIsfMgdl("SlimFclPlugin")
        val basal = profile.getBasal()
        val settings = SlimFclController.Settings(
            lowLine = preferences.get(SlimFclIntKey.LowLine).toDouble(),
            highLine = preferences.get(SlimFclIntKey.HighLine).toDouble(),
            lowWeight = preferences.get(SlimFclIntKey.LowWeight).toDouble(),
            roomBoost = preferences.get(SlimFclIntKey.RoomBoost) / 100.0,
            trendRoomMinutes = preferences.get(SlimFclIntKey.TrendRoom).toDouble(),
            secondStageMinutes = preferences.get(SlimFclIntKey.SecondStage),
            maxSmb = preferences.get(SlimFclDoubleKey.MaxSmb),
            maxIob = preferences.get(SlimFclDoubleKey.MaxIob)
        )
        val d = controller.step(t0 / STEP_MS, view, pastActivity, futureActivity, iob, basal, target, isfProfile, settings)
        val isf = isfProfile / SlimFclModel.aisfFactor(SlimFclModel.aisfStatus(view), target)
        val eventual = d.weightedMealChance * d.predictedMeal.last() + (1 - d.weightedMealChance) * d.predictedFalseStart.last()
        val temp = when {
            d.rate == 0.0      -> "zero temp"
            d.rate > basal     -> "high temp ${Round.roundTo(d.rate, 0.05)} U/h"
            else               -> "normal basal"
        }
        val reason = "Slim FCL (shadow): meal chance ${(d.mealChance * 100).roundToInt()}%, " +
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
                IOB = d.predictedFalseStart.map { it.roundToInt() },
                UAM = d.predictedMeal.map { it.roundToInt() }
            ),
            IOB = iob,
            variable_sens = isf,
            smbEnabled = view[0] >= settings.smbMinBg
        )
        lastAPSResult = apsResultProvider().with(rt).also { it.glucoseStatus = glucoseStatus }
        lastAPSRun = now
        aapsLogger.debug(LTag.APS, "Slim FCL result: $reason")
        rxBus.send(EventAPSCalculationFinished())
        rxBus.send(EventOpenAPSUpdateGui())
    }

    private fun abort(message: String) {
        aapsLogger.debug(LTag.APS, message)
        rxBus.send(EventResetOpenAPSGui(message))
    }

    /** Insulin given before [t0] within the insulin's life: (time, units). Temp basals count as net insulin against the profile basal. */
    private suspend fun insulinDoses(t0: Long, profile: Profile): List<Pair<Long, Double>> {
        val life = (SlimFclController.CURVE.td * 60000).toLong()
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
        Round.roundTo(doses.filter { it.first <= givenUpTo && it.first <= time }.sumOf { SlimFclController.CURVE.activity(it.second, (time - it.first) / 60000.0) }, 0.0001)

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "slim_fcl_settings",
        title = ApsStrings.slim_fcl,
        items = listOf(
            SlimFclIntKey.LowLine,
            SlimFclIntKey.HighLine,
            SlimFclIntKey.LowWeight,
            SlimFclIntKey.RoomBoost,
            SlimFclIntKey.TrendRoom,
            SlimFclIntKey.SecondStage,
            SlimFclDoubleKey.MaxSmb,
            SlimFclDoubleKey.MaxIob
        ),
        icon = pluginDescription.icon
    )

    private companion object {

        const val MINUTE = 60_000L
        const val STEP_MS = 5 * MINUTE
        const val VIEW_SIZE = 26
    }
}
