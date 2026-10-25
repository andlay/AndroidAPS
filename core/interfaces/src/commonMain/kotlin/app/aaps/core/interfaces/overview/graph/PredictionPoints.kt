package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.aps.Predictions

private const val STEP_MS = 5 * 60 * 1000L

/**
 * The points of the curves in [predictions] of a loop run made at [start], in the user's units.
 * Point i is 5 * i minutes after the run; point 0 is the BG of the run itself and is left out, as are
 * values below 40 mg/dL.
 *
 * @param toUnits converts mg/dL to the user's units
 * @param lowMark low mark in the user's units, for [BgRange]
 * @param highMark high mark in the user's units, for [BgRange]
 */
fun predictionPoints(
    predictions: Predictions,
    start: Long,
    afterDose: Boolean,
    lowMark: Double,
    highMark: Double,
    toUnits: (Double) -> Double
): List<BgDataPoint> = buildList {
    fun curve(values: List<Int>?, type: BgType) {
        values ?: return
        for (i in 1 until values.size) {
            if (values[i] < 40) continue
            val value = toUnits(values[i].toDouble())
            val range = when {
                value > highMark -> BgRange.HIGH
                value < lowMark  -> BgRange.LOW
                else             -> BgRange.IN_RANGE
            }
            add(BgDataPoint(timestamp = start + i * STEP_MS, value = value, range = range, type = type, afterDose = afterDose))
        }
    }
    curve(predictions.IOB, BgType.IOB_PREDICTION)
    curve(predictions.aCOB, BgType.A_COB_PREDICTION)
    curve(predictions.COB, BgType.COB_PREDICTION)
    curve(predictions.UAM, BgType.UAM_PREDICTION)
    curve(predictions.ZT, BgType.ZT_PREDICTION)
    curve(predictions.mpcMeal, BgType.MPC_MEAL_PREDICTION)
    curve(predictions.mpcFalseStart, BgType.MPC_FALSE_START_PREDICTION)
    curve(predictions.mpcExpected, BgType.MPC_EXPECTED_PREDICTION)
}
