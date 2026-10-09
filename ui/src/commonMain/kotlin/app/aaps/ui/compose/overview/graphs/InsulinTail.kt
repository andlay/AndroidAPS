package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BgiGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.Inject
import kotlin.math.abs
import kotlin.math.max

/**
 * Projected insulin activity and BGI after the calculated data, on to the end of insulin action
 * (now + DIA). Display only.
 *
 * @param from time of the last calculated point the tail continues from. The tail is only added to
 *   data that still ends there, so a tail made for older data is never drawn on newer data.
 */
class InsulinTail(
    val from: Long,
    val activity: List<GraphDataPoint>,
    val bgi: List<GraphDataPoint>,
    val maxActivity: Double
) {

    val end: Long get() = activity.last().timestamp
}

/** Time of the last activity point, past or projected. */
internal val ActivityGraphData.lastTimestamp: Long?
    get() = (activityPrediction.lastOrNull() ?: activity.lastOrNull())?.timestamp

/** Time of the last BGI point, past or projected. */
internal val BgiGraphData.lastTimestamp: Long?
    get() = (bgiPrediction.lastOrNull() ?: bgi.lastOrNull())?.timestamp

/** [data] with the tail added, when the tail was made for data that ends where [data] ends. */
internal fun ActivityGraphData.withTail(tail: InsulinTail?): ActivityGraphData {
    if (tail == null || tail.from != lastTimestamp) return this
    // The projected line starts at the last past point, so the two lines meet
    val start = if (activityPrediction.isEmpty()) listOfNotNull(activity.lastOrNull()) else activityPrediction
    return copy(activityPrediction = start + tail.activity, maxActivity = max(maxActivity, tail.maxActivity))
}

/** [data] with the tail added, when the tail was made for data that ends where [data] ends. */
internal fun BgiGraphData.withTail(tail: InsulinTail?): BgiGraphData {
    if (tail == null || tail.bgi.isEmpty() || tail.from != lastTimestamp) return this
    return copy(bgiPrediction = bgiPrediction + tail.bgi)
}

/**
 * Works out the [InsulinTail].
 *
 * This is on purpose not part of the calculation. The loop waits for the calculation to finish (the
 * SMB algorithm asks for its meal data only after it is done), so every step added there could
 * delay a loop run. The graph runs this on a low priority background thread while it is shown, and nothing
 * else waits for it.
 */
@Inject
class InsulinTailCalculator(
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil
) {

    /** The tail after [activity] and [bgi], or null when they do not reach now (a past day has no future to project). */
    suspend fun calculate(activity: ActivityGraphData, bgi: BgiGraphData): InsulinTail? {
        val from = activity.lastTimestamp ?: return null
        val now = dateUtil.now()
        if (from < now - STEP_MS) return null
        val end = now + (profileFunction.getProfile()?.iCfg?.insulinEndTime ?: DEFAULT_INSULIN_TAIL_MS)
        val units = profileUtil.units
        val isf = bgi.lastIsfMgdl?.takeIf { bgi.lastTimestamp == from }
        val activityPoints = ArrayList<GraphDataPoint>()
        val bgiPoints = ArrayList<GraphDataPoint>()
        var maxActivity = 0.0
        var time = from + STEP_MS
        while (time <= end) {
            val profile = profileFunction.getProfile(time) ?: break
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile)
            // Per 5 minutes, the same as the calculated activity
            val activityPer5Min = iob.activity * 5.0
            activityPoints.add(GraphDataPoint(time, activityPer5Min))
            maxActivity = max(maxActivity, abs(activityPer5Min))
            isf?.let { bgiPoints.add(GraphDataPoint(time, profileUtil.fromMgdlToUnits(iob.activity * it * 5.0, units))) }
            time += STEP_MS
        }
        if (activityPoints.isEmpty()) return null
        return InsulinTail(from = from, activity = activityPoints, bgi = bgiPoints, maxActivity = maxActivity)
    }

    private companion object {

        const val STEP_MS = 5 * 60 * 1000L

        /** How far the tail is drawn when the profile has no insulin set (the minimum DIA). */
        const val DEFAULT_INSULIN_TAIL_MS = 5L * 60 * 60 * 1000
    }
}
