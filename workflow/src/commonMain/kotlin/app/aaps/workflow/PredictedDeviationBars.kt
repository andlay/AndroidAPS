package app.aaps.workflow

import app.aaps.core.interfaces.overview.graph.GraphDataPoint

private const val STEP_MS = 5 * 60 * 1000L

/**
 * Where the expected deviation bars start: at the BG reading the loop run used, when it is the
 * reading just before the run. Otherwise (no reading known, as on a client) at the run itself.
 */
internal fun predictedDeviationsStart(runTime: Long, bgTime: Long?): Long =
    if (bgTime != null && bgTime <= runTime && runTime - bgTime < 2 * STEP_MS) bgTime else runTime

/**
 * One bar per step of [steps] (mg/dL per 5 minutes): step i from [start] + 5 * i minutes, with one
 * more point at the end so the last bar has its width too. [toUnits] converts to the user's units.
 */
internal fun predictedDeviationBars(steps: List<Double>?, start: Long, toUnits: (Double) -> Double): List<GraphDataPoint> {
    if (steps.isNullOrEmpty()) return emptyList()
    val bars = steps.mapIndexed { i, mgdl -> GraphDataPoint(start + i * STEP_MS, toUnits(mgdl)) }
    return bars + GraphDataPoint(start + steps.size * STEP_MS, bars.last().value)
}
