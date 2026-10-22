package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The prediction curves of a loop run with the dose it asks for added (see `RT.predBGsAfterDose`).
 *
 * determine-basal builds its curves from the insulin that is already there, so the SMB and the temp
 * basal it decides on are not in them. This adds them the way determine-basal adds all insulin:
 * each step of a curve moves by -activity * sens * 5, here with the activity of the extra insulin only.
 */
internal object PostDosePredictions {

    private const val STEP_MIN = 5
    private const val HORIZON_MIN = 240

    /**
     * The insulin the run adds compared with what the curves assume, as (minutes from now, units).
     * The curves let the running temp go on to its end and then the profile basal. The run gives the
     * SMB now, and its new temp, if it sets one, replaces the running temp from now. A duration of 0
     * cancels the temp.
     */
    fun extraInsulin(smb: Double, current: CurrentTemp, newRate: Double?, newDuration: Int?, basal: Double): List<Pair<Double, Double>> = buildList {
        if (smb > 0.0) add(0.0 to smb)
        if (newRate == null || newDuration == null) return@buildList
        val currentLeft = max(0, current.duration)
        val end = min(HORIZON_MIN, max(currentLeft, newDuration))
        for (minute in 0 until end step STEP_MIN) {
            val old = if (minute < currentLeft) current.rate else basal
            val new = if (minute < newDuration) newRate else basal
            val units = (new - old) * STEP_MIN / 60.0
            if (units != 0.0) add(minute + STEP_MIN / 2.0 to units)
        }
    }

    /**
     * [predictions] with the effect of [extra] (from [extraInsulin]) added. The ZT curve already
     * assumes a zero temp from now, so only the SMB ([smb]) moves it.
     *
     * @param sens the ISF the run used for its curves, mg/dL per U
     * @param activity insulin activity in U per minute of [units] given [minutesAgo] before
     */
    fun shift(
        predictions: Predictions,
        extra: List<Pair<Double, Double>>,
        smb: Double,
        sens: Double,
        activity: (units: Double, minutesAgo: Double) -> Double
    ): Predictions {
        val smbOnly = if (smb > 0.0) listOf(0.0 to smb) else emptyList()
        val all = drops(extra, sens, activity)
        val zt = drops(smbOnly, sens, activity)
        return Predictions(
            IOB = predictions.IOB?.let { moved(it, all) },
            ZT = predictions.ZT?.let { moved(it, zt) },
            COB = predictions.COB?.let { moved(it, all) },
            aCOB = predictions.aCOB?.let { moved(it, all) },
            UAM = predictions.UAM?.let { moved(it, all) }
        )
    }

    /** The BG change at each point of a curve: point n moves by the sum of steps 0 until n. */
    private fun drops(insulin: List<Pair<Double, Double>>, sens: Double, activity: (Double, Double) -> Double): DoubleArray {
        val result = DoubleArray(HORIZON_MIN / STEP_MIN + 1)
        var total = 0.0
        for (step in 0 until result.size - 1) {
            val now = step * STEP_MIN.toDouble()
            val act = insulin.sumOf { (at, units) -> if (now > at) activity(units, now - at) else 0.0 }
            total -= act * sens * STEP_MIN
            result[step + 1] = total
        }
        return result
    }

    private fun moved(curve: List<Int>, drops: DoubleArray): List<Int> =
        curve.mapIndexed { i, bg -> (bg + drops[min(i, drops.size - 1)]).roundToInt().coerceIn(39, 401) }

    /**
     * The curves of [rt] after its dose, or null when it has no curves or asks for nothing that
     * changes them.
     */
    fun afterDose(rt: RT, current: CurrentTemp, basal: Double, sens: Double, activity: (units: Double, minutesAgo: Double) -> Double): Predictions? {
        val predictions = rt.predBGs ?: return null
        if (!sens.isFinite() || sens <= 0.0) return null
        val smb = rt.units ?: 0.0
        val extra = extraInsulin(smb, current, rt.rate, rt.duration, basal)
        if (extra.isEmpty()) return null
        return shift(predictions, extra, smb, sens, activity)
    }
}
