package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.aaps.core.interfaces.overview.graph.AxisRange
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

// Extra traces on the BG graph. BG itself is drawn by BgGraphCompose; calculations over BG share its
// axis, and the first group of other traces goes on the right axis (see GraphConfig.bgTraces).

/** One drawn piece of a trace: a line broken at a gap is several runs. */
internal class TraceRun(val look: TraceLook, val points: List<Pair<Double, Double>>)

/** The extra traces of the BG graph, shaped and ready to draw. */
internal class BgExtraTraces(
    /** Calculations over BG, on the BG axis. */
    val left: List<TraceRun>,
    /** Traces of the right axis. */
    val right: List<TraceRun>,
    /** First trace of the right axis, for its colour and its typing rules; null when there is no right axis. */
    val rightFirst: GraphTrace?
) {

    val hasRight: Boolean get() = rightFirst != null

    companion object {

        val NONE = BgExtraTraces(emptyList(), emptyList(), null)
    }
}

/** Lines of the BG calculations layer, stored with the model so lines and series always match. */
internal val BG_LEFT_TRACE_LINES_KEY = ExtraStore.Key<List<LineCartesianLayer.Line>>()

/** Lines of the right axis layer of the BG graph. */
internal val BG_RIGHT_TRACE_LINES_KEY = ExtraStore.Key<List<LineCartesianLayer.Line>>()

/** For a layer with nothing to show: Vico needs one series, drawn invisible. */
internal val INVISIBLE_LINE = LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)), areaFill = null)

/**
 * Line provider that takes its lines from the model's extras under [key], one per series. The lines
 * come with the data in one transaction, so the layer and the chart never have to be made again
 * when a trace gets more or fewer runs.
 */
internal fun extrasLineProvider(key: ExtraStore.Key<List<LineCartesianLayer.Line>>) =
    LineCartesianLayer.LineProvider { _, seriesIndex, extraStore ->
        extraStore.getOrNull(key)?.getOrNull(seriesIndex) ?: INVISIBLE_LINE
    }

/**
 * The extra traces of the BG graph for the time axis that starts at [minTimestamp] and ends at
 * [maxX] (minutes). Points come from the shared trace cache, like on the other graphs.
 */
@Composable
internal fun rememberBgExtraTraces(viewModel: GraphViewModel, traces: List<GraphTrace>, minTimestamp: Long, maxX: Double): BgExtraTraces {
    if (traces.isEmpty()) return BgExtraTraces.NONE
    val (left, right) = remember(traces) { axisGroups(listOf(GraphTrace(SeriesType.BG)) + traces) }
    // BG itself is drawn by the BG graph, only the calculations over it are traces here
    val leftTraces = remember(left) { left.filter { it.isDerived } }
    val leftPoints = leftTraces.map { trace -> key(trace) { rememberTracePoints(viewModel, trace) } }
    val rightPoints = right.map { trace -> key(trace) { if (trace.isDerived) rememberTracePoints(viewModel, trace) else rememberSourcePoints(viewModel, trace.type) } }
    val colors = rememberSeriesColors()
    return remember(leftTraces, leftPoints, right, rightPoints, minTimestamp, maxX, colors) {
        fun shape(traces: List<GraphTrace>, points: List<List<GraphDataPoint>>): List<TraceRun> =
            traces.zip(points).flatMap { (trace, pts) ->
                val look = lookOf(trace, colors)
                shapeForStyle(processPoints(pts, minTimestamp, 0.0, maxX), look.style).map { TraceRun(look, it) }
            }
        BgExtraTraces(shape(leftTraces, leftPoints), shape(right, rightPoints), right.firstOrNull())
    }
}

/**
 * Range of the right axis of the BG graph: from the points in view ([visibleMinX]..[visibleMaxX]),
 * with the same zero rules as the other graphs, and [typed] ends put in.
 */
internal fun bgRightAxisScale(runs: List<TraceRun>, first: GraphTrace, visibleMinX: Double?, visibleMaxX: Double?, typed: AxisRange): NiceScale? {
    val ys = windowedY(runs.flatMap { it.points }, visibleMinX, visibleMaxX)
    if (ys.isEmpty() && typed.isAuto) return null
    val dataMin = ys.minOrNull() ?: 0.0
    val dataMax = ys.maxOrNull() ?: 1.0
    val auto = when {
        !first.transform.keepsScale                                   -> niceScale(minOf(0.0, dataMin), dataMax, SECONDARY_GRAPH_TICK_COUNT)
        first.type == SeriesType.COB || first.type in ZERO_FLOOR_SERIES_TYPES -> zeroFloorNiceRange(dataMin, dataMax.coerceAtLeast(dataMin + 0.1), SECONDARY_GRAPH_TICK_COUNT)
        else                                                          -> niceScale(dataMin, dataMax, SECONDARY_GRAPH_TICK_COUNT)
    }
    if (typed.isAuto) return auto
    val (low, high) = typed.applyTo(auto.min, auto.max) ?: return auto
    return NiceScale(low, high, niceScale(low, high, SECONDARY_GRAPH_TICK_COUNT).step)
}
