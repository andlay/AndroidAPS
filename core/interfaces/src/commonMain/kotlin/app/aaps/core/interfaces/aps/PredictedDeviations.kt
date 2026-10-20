package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

/**
 * The part of each 5 minute step of a prediction that does not come from insulin: the deviation the
 * loop expects, in mg/dL per 5 minutes, the same unit as the past deviations.
 *
 * Index 0 is 5 minutes after the loop run, index 1 is 10 minutes after it, and so on. So index i
 * belongs to index i + 1 of the same curve in [Predictions], which starts at the run itself.
 * A curve is null when the run did not make it (no COB curve without carbs, no UAM curve while BG
 * is not rising).
 *
 * @param IOB the current deviation, going down to zero over 60 minutes
 * @param COB the carb impact still expected, plus the falling part of the current deviation
 * @param UAM the impact of the unannounced meal still expected, plus the falling part of the current deviation
 */
@Serializable
data class PredictedDeviations(
    var IOB: List<Double>? = null,
    var COB: List<Double>? = null,
    var UAM: List<Double>? = null
)
