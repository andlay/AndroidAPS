package app.aaps.plugins.aps.replay

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSAutoISF.DetermineBasalAutoISF
import app.aaps.plugins.aps.openAPSAutoISF.GlucoseStatusCalculatorAutoIsf
import app.aaps.plugins.aps.openAPSAutoISF.OpenAPSAutoISFPlugin
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.min

private object Unanswered

/** Default value for a return type, so a stand-in can answer calls nobody cares about. */
private fun defaultFor(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    Integer.TYPE           -> 0
    java.lang.Long.TYPE    -> 0L
    java.lang.Double.TYPE  -> 0.0
    java.lang.Float.TYPE   -> 0f
    String::class.java     -> ""
    List::class.java       -> emptyList<Any>()
    else                   -> null
}

/**
 * A stand-in for an interface: [answer] handles the calls that matter, everything else gets a neutral
 * default. Suspend functions work too: a value returned straight away is a finished call.
 */
private inline fun <reified T> standIn(noinline answer: (Method, Array<Any?>) -> Any? = { _, _ -> Unanswered }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        val a = args ?: emptyArray()
        when (method.name) {
            "toString" -> "standIn(${T::class.simpleName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals"   -> proxy === a.getOrNull(0)
            else       -> answer(method, a).let { if (it === Unanswered) defaultFor(method.returnType) else it }
        }
    } as T

/**
 * Runs the app's real AutoISF: [OpenAPSAutoISFPlugin.autoISF], `loop_smb`, `determine_varSMBratio`,
 * [GlucoseStatusCalculatorAutoIsf] (parabola fit, dura_ISF) and [DetermineBasalAutoISF]. The plugin's
 * collaborators are stand-ins that answer only what AutoISF asks: preferences, the time, a temp target
 * and the bucketed BG. Nothing in the shipping code is changed for this.
 */
internal class AutoIsfRunner(settings: Map<String, String>) {

    /** Set before each loop run. */
    var now = 0L
    var bucketed: MutableList<InMemoryGlucoseValue> = mutableListOf()
    var tempTarget: TT? = null

    private val values = GUIDE_START + settings

    private val preferences: Preferences = standIn { m, a ->
        val key = a.getOrNull(0)
        if (m.name == "get" && key is Enum<*>) {
            val default = key.javaClass.getMethod("getDefaultValue").invoke(key)
            values[key.name]?.let { v ->
                when (default) {
                    is Boolean -> v.toBoolean()
                    is Int     -> v.toDouble().toInt()
                    is Double  -> v.toDouble()
                    else       -> v
                }
            } ?: default
        } else Unanswered
    }
    private val dateUtil: DateUtil = standIn { m, _ -> if (m.name == "now") now else Unanswered }
    private val autosensData: AutosensDataStore = standIn { m, _ -> if (m.name == "getBucketedDataTableCopy") bucketed.toMutableList() else Unanswered }
    private val iobCobCalculator: IobCobCalculator = standIn { m, _ -> if (m.name == "getAds") autosensData else Unanswered }
    private val persistence: PersistenceLayer = standIn { m, _ -> if (m.name == "getTemporaryTargetActiveAt") tempTarget else Unanswered }
    private val hardLimits: HardLimits = standIn { m, a -> if (m.name == "verifyHardLimits") a[0] else if (m.name == "checkHardLimits") true else Unanswered }
    private val constraints: ConstraintsChecker = standIn { m, _ ->
        // AutoISF guide: autosens off, it reads ISF from much older data than AutoISF does
        if (Constraint::class.java.isAssignableFrom(m.returnType)) ConstraintObject(false, SilentLogger) else Unanswered
    }

    val glucoseStatus = GlucoseStatusCalculatorAutoIsf(SilentLogger, iobCobCalculator, dateUtil, standIn<DecimalFormatter>(), DeltaCalculator(SilentLogger))
    private val determineBasal = DetermineBasalAutoISF(ReplayProfileUtil)
    private val plugin = OpenAPSAutoISFPlugin(
        aapsLogger = SilentLogger, rxBus = standIn<RxBus>(), constraintsChecker = constraints, rh = standIn<TextResolver>(),
        profileFunction = standIn<ProfileFunction>(), profileUtil = ReplayProfileUtil, config = standIn<Config>(), activePlugin = standIn<ActivePlugin>(),
        iobCobCalculator = iobCobCalculator, hardLimits = hardLimits, preferences = preferences, dateUtil = dateUtil,
        processedTbrEbData = standIn<ProcessedTbrEbData>(), persistenceLayer = persistence, glucoseStatusProvider = standIn<GlucoseStatusProvider>(),
        bgQualityCheck = standIn<BgQualityCheck>(), notificationManager = standIn<NotificationManager>(), determineBasalAutoISF = determineBasal,
        profiler = standIn<Profiler>(), glucoseStatusCalculatorAutoIsf = glucoseStatus, apsResultProvider = { error("not used in the replay") },
        ch = standIn<ConcentrationHelper>()
    )

    private fun d(key: String) = values[key]!!.toDouble()
    private fun b(key: String) = values[key]?.toBoolean() ?: false

    /** Profile ISF after AutoISF, like `OpenAPSAutoISFPlugin.invoke` gets it. */
    fun variableSens(profileSens: Double, profileTarget: Double): Double {
        val profile: Profile = standIn { m, _ ->
            when (m.name) {
                "getProfileIsfMgdl" -> profileSens
                "getTargetMgdl"     -> profileTarget
                else                -> Unanswered
            }
        }
        return runBlocking { plugin.autoISF(profile) }
    }

    /** The second half of `OpenAPSAutoISFPlugin.invoke`: iobTH, SMB power level, SMB ratio, then determine_basal. */
    fun determine(
        gs: GlucoseStatusAutoIsf, currentTemp: CurrentTemp, iob: Array<IobTotal>, p: OapsProfileAutoIsf, autosens: AutosensResult, meal: MealData,
        microBolusAllowed: Boolean, currentTime: Long, profilePercentage: Int
    ): RT {
        val targetBg = (p.min_bg + p.max_bg) / 2
        var exerciseRatio = 1.0
        if (p.high_temptarget_raises_sensitivity && p.temptargetSet && targetBg > NORMAL_TARGET ||
            p.low_temptarget_lowers_sensitivity && p.temptargetSet && targetBg < NORMAL_TARGET
        ) {
            val c = (p.half_basal_exercise_target - NORMAL_TARGET).toDouble()
            if (c * (c + targetBg - NORMAL_TARGET) > 0.0) exerciseRatio = plugin.round(min(c / (c + targetBg - NORMAL_TARGET), p.autosens_max), 2)
        }
        val iobThPercent = p.iob_threshold_percent
        val useIobTh = iobThPercent != 100
        val reduction = if (useIobTh) profilePercentage / 100.0 * exerciseRatio else 1.0
        val tolerance = 130.0
        val iobThVirtual = iobThPercent * tolerance / 10000.0 * p.max_iob * reduction
        val loopWantedSmb = plugin.loop_smb(microBolusAllowed, p, iob[0].iob, useIobTh, iobThVirtual / tolerance * 100.0)
        val smbRatio = plugin.determine_varSMBratio(gs.glucose.toInt(), targetBg, loopWantedSmb)
        return determineBasal.determine_basal(
            glucose_status = gs, currenttemp = currentTemp, iob_data_array = iob, profile = p, autosens_data = autosens, meal_data = meal,
            microBolusAllowed = microBolusAllowed, currentTime = currentTime, flatBGsDetected = false, autoIsfMode = true,
            loop_wanted_smb = loopWantedSmb, profile_percentage = profilePercentage, smb_ratio = smbRatio,
            smb_max_range_extension = p.smb_max_range_extension, iob_threshold_percent = iobThPercent,
            auto_isf_consoleError = mutableListOf(), auto_isf_consoleLog = mutableListOf()
        )
    }

    /** The AutoISF part of the profile, from the settings in use. */
    fun profile(
        minBg: Double, maxBg: Double, cr: Double, sens: Double, variableSens: Double, currentBasal: Double, maxDailyBasal: Double, tempTarget: Boolean,
        maxIob: Double, uam: Boolean, smbAlways: Boolean, smbInterval: Int, profilePercentage: Int
    ) = OapsProfileAutoIsf(
        dia = 0.0, min_5m_carbimpact = 0.0, max_iob = maxIob, max_daily_basal = maxDailyBasal, max_basal = 10.0,
        min_bg = minBg, max_bg = maxBg, target_bg = (minBg + maxBg) / 2, carb_ratio = cr, sens = sens,
        autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = b("ApsAutoIsfHighTtRaisesSens"), low_temptarget_lowers_sensitivity = b("ApsAutoIsfLowTtLowersSens"),
        sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false, exercise_mode = false,
        half_basal_exercise_target = d("ApsAutoIsfHalfBasalExerciseTarget").toInt(), maxCOB = 120, skip_neutral_temps = false,
        remainingCarbsCap = 90, enableUAM = uam, A52_risk_enable = false, SMBInterval = smbInterval, enableSMB_with_COB = true,
        enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false, enableSMB_always = smbAlways, enableSMB_after_carbs = true,
        maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30, bolus_increment = 0.05, carbsReqThreshold = 1, current_basal = currentBasal,
        temptargetSet = tempTarget, autosens_max = 1.2, out_units = "mmol/L", lgsThreshold = null, variable_sens = variableSens,
        autoISF_version = plugin.autoIsfVersion, enable_autoISF = b("ApsUseAutoIsfWeights"), autoISF_max = d("ApsAutoIsfMax"),
        autoISF_min = d("ApsAutoIsfMin"), bgAccel_ISF_weight = d("ApsAutoIsfBgAccelWeight"), bgBrake_ISF_weight = d("ApsAutoIsfBgBrakeWeight"),
        pp_ISF_weight = d("ApsAutoIsfPpWeight"), lower_ISFrange_weight = d("ApsAutoIsfLowBgWeight"), higher_ISFrange_weight = d("ApsAutoIsfHighBgWeight"),
        dura_ISF_weight = d("ApsAutoIsfDuraWeight"), smb_delivery_ratio = d("ApsAutoIsfSmbDeliveryRatio"),
        smb_delivery_ratio_min = d("ApsAutoIsfSmbDeliveryRatioMin"), smb_delivery_ratio_max = d("ApsAutoIsfSmbDeliveryRatioMax"),
        smb_delivery_ratio_bg_range = d("ApsAutoIsfSmbDeliveryRatioBgRange"), smb_max_range_extension = d("ApsAutoIsfSmbMaxRangeExtension"),
        enableSMB_EvenOn_OddOff_always = b("ApsAutoIsfSmbOnEvenTarget"), iob_threshold_percent = d("ApsAutoIsfIobThPercent").toInt(),
        profile_percentage = profilePercentage
    )

    companion object {

        private const val NORMAL_TARGET = 100

        /** "Useful initial value for adults" from the AutoISF 3.2.0 quick guide, attachment 1 (hybrid closed loop). */
        val GUIDE_START = mapOf(
            "ApsUseAutoIsfWeights" to "true", "ApsAutoIsfMin" to "0.7", "ApsAutoIsfMax" to "1.2",
            "ApsAutoIsfBgAccelWeight" to "0.02", "ApsAutoIsfBgBrakeWeight" to "0.01", "ApsAutoIsfPpWeight" to "0.005",
            "ApsAutoIsfLowBgWeight" to "0.2", "ApsAutoIsfHighBgWeight" to "0.2", "ApsAutoIsfDuraWeight" to "0.2",
            "ApsAutoIsfSmbDeliveryRatio" to "0.6", "ApsAutoIsfSmbDeliveryRatioMin" to "0.5", "ApsAutoIsfSmbDeliveryRatioMax" to "0.8",
            "ApsAutoIsfSmbDeliveryRatioBgRange" to "40", "ApsAutoIsfSmbMaxRangeExtension" to "1.5", "ApsAutoIsfIobThPercent" to "60",
            "ApsAutoIsfHalfBasalExerciseTarget" to "160", "ApsAutoIsfHighTtRaisesSens" to "false", "ApsAutoIsfLowTtLowersSens" to "false",
            "ApsAutoIsfSmbOnEvenTarget" to "false"
        )

        /** The four glucose weights, for scaling AutoISF's strength as a whole. */
        val GLUCOSE_WEIGHTS = listOf("ApsAutoIsfBgAccelWeight", "ApsAutoIsfBgBrakeWeight", "ApsAutoIsfPpWeight", "ApsAutoIsfLowBgWeight", "ApsAutoIsfHighBgWeight", "ApsAutoIsfDuraWeight")
    }
}
