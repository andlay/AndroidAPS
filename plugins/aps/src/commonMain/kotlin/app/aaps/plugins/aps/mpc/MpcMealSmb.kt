package app.aaps.plugins.aps.mpc

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.MpcRunValues
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * MPC meal SMBs for the OpenAPS SMB plugin: vanilla determine-basal sets the temp basal with its own SMBs off,
 * and this sizes the SMB when a meal is seen (see [MpcController]).
 *
 * Inputs are built as in the replay, so the controller sees what it was tuned on: the smoothed 5 min BG history
 * and the activity of past insulin on the oref curve (boluses, and temp basals as net insulin against the profile
 * basal, in 5 min parts up to now).
 */
class MpcMealSmb(
    private val preferences: Preferences,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer
) {

    private val controller = MpcController()
    private val _details = MutableStateFlow<MpcDetails?>(null)

    /** What the last run saw and decided, for the MPC cards on the plugin screen */
    val details: StateFlow<MpcDetails?> = _details

    /** The meal shape: the two learned settings, the rest fixed at the values fitted to the user's data */
    fun readableShape() = MpcShapeSettings(
        peakPosition = FIXED_SHAPE.peakPosition,
        halfWidth = FIXED_SHAPE.halfWidth,
        typicalMinutes = preferences.get(MpcDoubleKey.RiseLength),
        lengthSpreadPct = FIXED_SHAPE.lengthSpreadPct,
        typicalGrams = preferences.get(MpcDoubleKey.TypicalMeal),
        sizeSpreadPct = FIXED_SHAPE.sizeSpreadPct,
        readinessPct = FIXED_SHAPE.readinessPct,
        tailHalfLifeMinutes = FIXED_SHAPE.tailHalfLifeMinutes
    )

    class Result(
        /** SMB in U, rounded down to the pump's bolus step */
        val units: Double,
        /** One line for the loop reason */
        val reason: String,
        val predictedMeal: List<Int>,
        val predictedFalseStart: List<Int>,
        val predictedExpected: List<Int>,
        /** What the run saw, kept with the loop result for the graphs */
        val values: MpcRunValues
    )

    /**
     * The meal SMB for now. Null when there is no recent BG to work with.
     * [targetMgdl]: the target in use (profile or temp target). [tempRate]: vanilla's temp for the next 30 min, U/h.
     * [smbAllowed]: vanilla allows an SMB now (settings, constraints, SMB minimum BG, SMB interval) - the even target
     * rule is applied here. [maxIob] U, [maxSmb] U (UAM max minutes of basal), [bolusStep] U.
     */
    suspend fun decide(
        now: Long, profile: Profile, units: GlucoseUnit, targetMgdl: Double, tempRate: Double, smbAllowed: Boolean,
        maxIob: Double, maxSmb: Double, bolusStep: Double
    ): Result? {
        val buckets = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        val t0 = buckets.firstOrNull()?.timestamp ?: return null
        if (now - t0 > 10 * MINUTE) return null
        // BG history newest first, one value per 5 min, NaN where there is none
        val view = DoubleArray(VIEW_SIZE) { j ->
            val at = t0 - j * STEP_MS
            buckets.firstOrNull { abs(it.timestamp - at) <= STEP_MS / 2 }?.recalculated?.takeIf { it > 39 } ?: Double.NaN
        }
        if (!(view[0] > 39 && view[1] > 39 && view[3] > 39)) return null

        val even = evenTarget(targetMgdl, units)
        val evenRule = preferences.get(MpcBooleanKey.EvenTargetSmb)
        val allowed = smbAllowed && (!evenRule || even)
        val doses = insulinDoses(t0, profile)
        val pastActivity = DoubleArray(VIEW_SIZE - 1) { j -> activityAt(doses, t0 - j * STEP_MS, t0 - j * STEP_MS) }
        val futureActivity = DoubleArray(MpcController.HORIZON + 1) { j -> activityAt(doses, t0 + j * STEP_MS, t0) }
        val iob = Round.roundTo(doses.sumOf { MpcController.CURVE.iob(it.second, (t0 - it.first) / 60000.0) }, 0.001)
        val isf = profile.getIsfMgdl("MpcMealSmb")
        val basal = profile.getBasal()
        val shape = readableShape()
        val settings = MpcController.MealSmbSettings(
            target = targetMgdl, maxSmb = maxSmb, maxIob = maxIob, shape = shape.toShape(isf, profile.getIc())
        )
        val d = controller.mealSmb(t0 / STEP_MS, view, pastActivity, futureActivity, iob, basal, tempRate, isf, allowed, settings)
        val smb = if (bolusStep > 0) floor(d.units / bolusStep + 1e-9) * bolusStep else d.units
        val reason = when {
            !d.mealSeen                -> "MPC: no meal seen"
            evenRule && !even          -> "MPC: meal chance ${(d.weightedMealChance * 100).roundToInt()}%, SMB off (odd target)"
            !smbAllowed                -> "MPC: meal chance ${(d.weightedMealChance * 100).roundToInt()}%, SMB not allowed now"
            smb > 0                    -> "MPC meal SMB ${Round.roundTo(smb, 0.01)} U: meal chance ${(d.weightedMealChance * 100).roundToInt()}%"
            else                       -> "MPC: meal chance ${(d.weightedMealChance * 100).roundToInt()}%, no SMB yet"
        }
        val bg0 = view[0].roundToInt()
        // carb effect still to come (mg/dL) in grams: a gram raises BG by ISF / IC
        val gramsPerMgdl = profile.getIc() / isf
        val carbsIfMeal = d.futures.meal.sum() * gramsPerMgdl
        val carbsIfNoMeal = d.futures.non.sum() * gramsPerMgdl
        _details.value = MpcDetails(
            time = t0, bg = view[0], units = smb, tempRate = tempRate, basal = basal, iob = iob, isf = isf,
            mealSeen = d.mealSeen, smbAllowed = allowed, evenTarget = even, evenRule = evenRule,
            mealChance = d.mealChance, weightedMealChance = d.weightedMealChance, room = d.room,
            target = targetMgdl, highLine = targetMgdl + settings.highAbove,
            predictedMeal = d.predictedMeal, predictedFalseStart = d.predictedFalseStart, predictedExpected = d.predictedExpected,
            pastDev = d.futures.dev, pastFitted = d.futures.fitted,
            futureMeal = d.futures.meal.copyOf(MpcController.HORIZON), futureFalseStart = d.futures.non.copyOf(MpcController.HORIZON),
            impactAge = d.futures.impactAge, afterPeak = d.futures.afterPeak, options = d.options, reason = reason,
            mmol = units == GlucoseUnit.MMOL
        )
        return Result(
            units = smb,
            reason = reason,
            predictedMeal = listOf(bg0) + d.predictedMeal.map { it.roundToInt() },
            predictedFalseStart = listOf(bg0) + d.predictedFalseStart.map { it.roundToInt() },
            predictedExpected = listOf(bg0) + d.predictedExpected.map { it.roundToInt() },
            values = MpcRunValues(
                mealChance = d.mealChance,
                weightedMealChance = d.weightedMealChance,
                carbsIfMeal = Round.roundTo(carbsIfMeal, 0.1),
                carbsIfNoMeal = Round.roundTo(carbsIfNoMeal, 0.1),
                carbsExpected = Round.roundTo(d.weightedMealChance * carbsIfMeal + (1 - d.weightedMealChance) * carbsIfNoMeal, 0.1),
                impactAgeMinutes = if (d.futures.impactAge < 0) -1 else d.futures.impactAge * 5
            )
        )
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

    companion object {

        private const val MINUTE = 60_000L
        private const val STEP_MS = 5 * MINUTE
        private const val VIEW_SIZE = 26

        /** The meal shape values that are not settings: fitted to the user's data, and not worth tuning (see the parameter audit) */
        val FIXED_SHAPE = MpcShapeSettings.of(MpcModel.Shape.FITTED, isf = 46.8, ic = 13.92)

        /**
         * The AutoISF "even target allows SMB, odd target stops it" rule, in the user's units: mmol/L by the first
         * decimal (5.0, 5.4, 6.0 even; 5.5 odd), mg/dL by the whole number.
         */
        fun evenTarget(targetMgdl: Double, units: GlucoseUnit): Boolean =
            if (units == GlucoseUnit.MMOL) (targetMgdl / 18.0 * 10).roundToInt() % 2 == 0
            else targetMgdl.roundToInt() % 2 == 0
    }
}

/** What one MPC meal SMB run saw and decided, for the MPC cards. BG in mg/dL; carb effect in mg/dL per 5 min. */
class MpcDetails(
    val time: Long,
    val bg: Double,
    val units: Double,
    /** Vanilla's temp the SMB was planned around, U/h */
    val tempRate: Double,
    val basal: Double,
    val iob: Double,
    val isf: Double,
    val mealSeen: Boolean,
    val smbAllowed: Boolean,
    val evenTarget: Boolean,
    val evenRule: Boolean,
    val mealChance: Double,
    val weightedMealChance: Double,
    val room: Double,
    val target: Double,
    val highLine: Double,
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
