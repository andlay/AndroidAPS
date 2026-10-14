package app.aaps.workflow

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.overview.graph.GraphDataPoint

/**
 * One autosens step for [devCarbsRuns].
 *
 * @param graphTime   where the step is drawn: the same time as its DEV bar
 * @param readingTime time of the BG reading of the step. Two steps with the same reading count once.
 * @param carbs       the deviation of the step in grams of carbs: deviation × IC / ISF, as autosens uses it for COB
 * @param filled      true when there was no reading and AAPS filled the gap with a straight line
 */
internal data class DevCarbsStep(val graphTime: Long, val readingTime: Long, val carbs: Double, val filled: Boolean = false)

/** A run ends when the total has come back from its peak (or low) by more than this many grams... */
private const val DEV_CARBS_TURN = 3.0

/** ...at this many readings in a row, so one odd reading does not end it. */
private const val DEV_CARBS_TURN_READINGS = 2

/** Up to this many missing readings in a row are bridged by the filled line. More ends the run. */
private const val DEV_CARBS_MAX_FILLED = 2

/** More time than this between two steps ends the run too. */
private val DEV_CARBS_MAX_GAP = T.mins(15).msecs()

/** Where the total starts: one 5 minute step before the first step. */
private val DEV_CARBS_STEP = T.mins(5).msecs()

/**
 * Running total of the deviations in grams of carbs, for the DEV carbs graph, as runs to draw.
 *
 * A positive run is the carbs that would explain a rise the insulin does not explain. A negative run is
 * the carbs that are "missing" for a drop. Only the deviation is used, never COB, so carbs entered by the
 * user do not change the result.
 *
 * Every deviation is added, also a small one. A short move the other way (a CGM reading that jumps down
 * and back up) is taken off the total and then added back, so it does not end the run. A run ends at its
 * peak (or low) when the total has come back from there by more than 3 g, at 2 readings in a row. The
 * next run starts from zero at that peak and already has the move since then. So the newest few points
 * can move to a new run when the next reading confirms the turn.
 *
 * Where readings are missing, AAPS fills the gap with a straight line. One or two missing readings are
 * kept. A longer gap ends the run and its filled points are not counted.
 *
 * Each run starts with a point at 0.
 */
internal fun devCarbsRuns(steps: List<DevCarbsStep>): List<List<GraphDataPoint>> =
    devCarbsSegments(steps).flatMap { devCarbsTurns(it) }

/** The steps without the long gaps, as parts that have readings all the way. */
private fun devCarbsSegments(steps: List<DevCarbsStep>): List<List<DevCarbsStep>> {
    val unique = steps.distinctBy { it.readingTime }
    val segments = mutableListOf(mutableListOf<DevCarbsStep>())
    fun add(step: DevCarbsStep) {
        val last = segments.last().lastOrNull()
        if (last != null && step.readingTime - last.readingTime > DEV_CARBS_MAX_GAP) segments.add(mutableListOf())
        segments.last().add(step)
    }
    var i = 0
    while (i < unique.size) {
        if (!unique[i].filled) {
            add(unique[i++])
            continue
        }
        var end = i
        while (end < unique.size && unique[end].filled) end++
        if (end - i > DEV_CARBS_MAX_FILLED) {
            segments.add(mutableListOf())
        } else {
            for (k in i until end) add(unique[k])
        }
        i = end
    }
    return segments.filter { it.isNotEmpty() }
}

/** The runs of one part with readings all the way: a new run at every confirmed turn. */
private fun devCarbsTurns(steps: List<DevCarbsStep>): List<List<GraphDataPoint>> {
    // Index 0 is the start, one step before the first step, at a total of 0
    val times = LongArray(steps.size + 1)
    val totals = DoubleArray(steps.size + 1)
    times[0] = steps[0].graphTime - DEV_CARBS_STEP
    for ((i, step) in steps.withIndex()) {
        times[i + 1] = step.graphTime
        totals[i + 1] = totals[i] + step.carbs
    }
    fun run(from: Int, to: Int) = (from..to).map { GraphDataPoint(times[it], totals[it] - totals[from]) }

    val runs = mutableListOf<List<GraphDataPoint>>()
    var sign = 0 // direction of the run in progress, 0 = none yet
    var start = 0
    var extreme = 0
    var low = 0
    var high = 0
    var up = 0
    var down = 0
    var back = 0
    for (k in 1 until totals.size) {
        if (sign == 0) {
            // No direction yet: the first move of more than the turn from the lowest or highest total starts it
            if (totals[k] < totals[low]) low = k
            if (totals[k] > totals[high]) high = k
            up = if (totals[k] - totals[low] > DEV_CARBS_TURN) up + 1 else 0
            down = if (totals[high] - totals[k] > DEV_CARBS_TURN) down + 1 else 0
            if (up >= DEV_CARBS_TURN_READINGS || down >= DEV_CARBS_TURN_READINGS) {
                sign = if (up >= DEV_CARBS_TURN_READINGS) 1 else -1
                start = if (sign > 0) low else high
                extreme = (start + 1..k).maxBy { totals[it] * sign }
            }
            continue
        }
        val move = (totals[k] - totals[extreme]) * sign
        when {
            move >= 0.0           -> {
                extreme = k
                back = 0
            }

            -move > DEV_CARBS_TURN -> if (++back >= DEV_CARBS_TURN_READINGS) {
                runs.add(run(start, extreme))
                sign = -sign
                start = extreme
                extreme = (start + 1..k).maxBy { totals[it] * sign }
                back = 0
            }

            else                  -> back = 0
        }
    }
    // The run in progress goes on to the newest step, with any move back that is not a turn yet
    if (sign != 0) runs.add(run(start, totals.size - 1))
    return runs
}
