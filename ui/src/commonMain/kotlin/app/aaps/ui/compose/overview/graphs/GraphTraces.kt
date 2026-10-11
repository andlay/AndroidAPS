package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.PlotStyle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import kotlin.math.exp

// Traces on the secondary graphs: the calculations (EWMA, rolling mean, rolling sum), the shapes the
// plot styles need (bars, a line that breaks at gaps) and the look of each trace. Display only:
// nothing here is used by the loop or the calculation workflow.

/**
 * Series whose look (style and colour) the user can change. The others have their own drawing:
 * IOB and COB carry treatment markers, DEV is coloured per point, DEV slope has two lines.
 */
internal val STYLABLE_SERIES = setOf(
    SeriesType.ABS_IOB, SeriesType.BGI, SeriesType.SENSITIVITY, SeriesType.VAR_SENSITIVITY,
    SeriesType.HEART_RATE, SeriesType.STEPS, SeriesType.ACTIVITY, SeriesType.BASAL
)

/** True when the user can change how this trace looks. Every derived trace can. */
internal val GraphTrace.isStylable: Boolean get() = isDerived || type in STYLABLE_SERIES

/**
 * Traces with the same key share one axis: a series with its EWMAs and rolling means (same unit and
 * scale), and BGI with DEV (both a BG change per 5 minutes). Each rolling sum has its own scale.
 */
internal fun GraphTrace.axisKey(): String {
    val unit = if (type == SeriesType.BGI || type == SeriesType.DEVIATIONS) "BGI_DEV" else type.name
    return if (transform.keepsScale) unit else "$unit:sum:$windowMinutes"
}

/** Style used when the user has not picked one. */
internal fun defaultStyle(trace: GraphTrace): PlotStyle =
    if (trace.isDerived) PlotStyle.LINE
    else when (trace.type) {
        SeriesType.ABS_IOB, SeriesType.ACTIVITY, SeriesType.BASAL -> PlotStyle.AREA
        SeriesType.BGI                          -> PlotStyle.POINTS
        SeriesType.STEPS                        -> PlotStyle.BARS
        else                                    -> PlotStyle.LINE
    }

/** Style the trace is drawn with. Series that cannot be styled always use their default. */
internal fun GraphTrace.effectiveStyle(): PlotStyle = style?.takeIf { isStylable } ?: defaultStyle(this)

/** How one trace is drawn. */
internal data class TraceLook(val style: PlotStyle, val color: Color, val square: Boolean)

/** The look of [trace]: its own style and colour where set, else the series defaults. */
internal fun lookOf(trace: GraphTrace, colors: SeriesColors): TraceLook =
    TraceLook(
        style = trace.effectiveStyle(),
        color = trace.color?.takeIf { trace.isStylable }?.let { Color(it) } ?: colors.colorFor(trace.type),
        // Absolute IOB and basal are step series; a calculation over them is not
        square = (trace.type == SeriesType.ABS_IOB || trace.type == SeriesType.BASAL) && !trace.isDerived && trace.effectiveStyle() != PlotStyle.POINTS
    )

/** Colour as stored in [GraphTrace.color]. */
internal fun Color.toStoredColor(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Colours offered in the edit sheet. Bright enough for the dark graph background. */
internal val TRACE_COLOR_CHOICES = listOf(
    Color(0xFFFFFF66), Color(0xFF66FFB8), Color(0xFF00EEEE), Color(0xFF1E88E5), Color(0xFFB388FF),
    Color(0xFFFF66CC), Color(0xFFFF5252), Color(0xFFFB8C00), Color(0xFFD3F166), Color(0xFFBDBDBD)
)

/** The same fill as the DEV bars. */
private const val BAR_FILL_ALPHA = 0.5f
private const val AREA_FILL_ALPHA = 0.3f

/** The Vico line for [look]. */
internal fun createTraceLine(look: TraceLook): LineCartesianLayer.Line {
    val color = look.color
    val lineFill = LineCartesianLayer.LineFill.single(Fill(color))
    return when (look.style) {
        PlotStyle.LINE, PlotStyle.AREA -> {
            val areaFill = if (look.style == PlotStyle.AREA)
                LineCartesianLayer.AreaFill.single(Fill(Brush.verticalGradient(listOf(color.copy(alpha = AREA_FILL_ALPHA), Color.Transparent))))
            else null
            // Vico's own interpolator unless the series is a step series
            if (look.square) LineCartesianLayer.Line(fill = lineFill, areaFill = areaFill, interpolator = Square)
            else LineCartesianLayer.Line(fill = lineFill, areaFill = areaFill)
        }

        // Drawn like the DEV bars: no outline, a half see-through fill, square steps
        PlotStyle.BARS                 -> LineCartesianLayer.Line(
            fill = lineFill,
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 0.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = BAR_FILL_ALPHA))),
            interpolator = Square
        )

        PlotStyle.POINTS               -> LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = CircleShape), size = 4.dp)
            )
        )
    }
}

// =========================================================================
// Calculations
// =========================================================================

private const val MS_PER_MINUTE = 60_000.0

/**
 * [transform] over these points, one result at each point's time. It works on the real timestamps,
 * so a gap in the data is handled correctly:
 * - rolling mean and sum use the points in (t - window, t];
 * - EWMA moves towards each point by 1 - e^(-dt / window), so after a long gap it starts again from
 *   the new value instead of carrying an old one.
 */
internal fun List<GraphDataPoint>.transformed(transform: TraceTransform, windowMinutes: Int): List<GraphDataPoint> {
    if (transform == TraceTransform.NONE || isEmpty()) return this
    val points = sortedBy { it.timestamp }
    val windowMs = windowMinutes * MS_PER_MINUTE
    return when (transform) {
        TraceTransform.NONE                                   -> points

        TraceTransform.EWMA                                   -> {
            var value = points.first().value
            var lastTime = points.first().timestamp
            points.map { p ->
                val weight = 1.0 - exp(-(p.timestamp - lastTime) / windowMs)
                value += weight * (p.value - value)
                lastTime = p.timestamp
                GraphDataPoint(p.timestamp, value)
            }
        }

        TraceTransform.ROLLING_MEAN, TraceTransform.ROLLING_SUM -> {
            var start = 0
            var sum = 0.0
            points.mapIndexed { index, p ->
                sum += p.value
                while (points[start].timestamp <= p.timestamp - windowMs) {
                    sum -= points[start].value
                    start++
                }
                val count = index - start + 1
                GraphDataPoint(p.timestamp, if (transform == TraceTransform.ROLLING_SUM) sum else sum / count)
            }
        }
    }
}

/** Basal is sampled this often before it is drawn or calculated over. */
internal const val BASAL_SAMPLE_MS = 5 * 60_000L

/**
 * A step series (a point only where the value changes) as regular samples every [stepMs], from its
 * first point to its last, each holding the value in effect then. EWMA and rolling values need
 * regular samples to weigh each value by the time it lasted.
 */
internal fun sampleSteps(points: List<GraphDataPoint>, stepMs: Long): List<GraphDataPoint> {
    if (points.isEmpty()) return points
    val sorted = points.sortedBy { it.timestamp }
    val out = ArrayList<GraphDataPoint>()
    var index = 0
    var time = sorted.first().timestamp
    while (time <= sorted.last().timestamp) {
        while (index + 1 < sorted.size && sorted[index + 1].timestamp <= time) index++
        out.add(GraphDataPoint(time, sorted[index].value))
        time += stepMs
    }
    return out
}

// =========================================================================
// Shapes for the plot styles (x in minutes, as the graphs use)
// =========================================================================

/** Usual time between points: the median gap, or 5 minutes when there are too few points. */
internal fun typicalGap(points: List<Pair<Double, Double>>): Double {
    val gaps = points.zipWithNext { a, b -> b.first - a.first }.filter { it > 0.0 }.sorted()
    return if (gaps.isEmpty()) DEFAULT_GAP_MINUTES else gaps[gaps.size / 2]
}

private const val DEFAULT_GAP_MINUTES = 5.0

/** A line or area is broken where two points are further apart than this many usual gaps... */
private const val LINE_GAP_FACTOR = 3.0

/** ...and at least this many minutes, so a line never jumps across a time with no data. */
private const val MIN_LINE_GAP_MINUTES = 15.0

/** The points as runs without a gap, so a line is not drawn across a time with no data. */
internal fun splitAtGaps(points: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
    if (points.isEmpty()) return emptyList()
    val maxGap = maxOf(MIN_LINE_GAP_MINUTES, LINE_GAP_FACTOR * typicalGap(points))
    val runs = mutableListOf<MutableList<Pair<Double, Double>>>(mutableListOf(points.first()))
    for ((previous, point) in points.zipWithNext()) {
        if (point.first - previous.first > maxGap) runs.add(mutableListOf())
        runs.last().add(point)
    }
    return runs
}

/**
 * The points as bars for a [Square] line with an area fill, the same shape as the DEV bars: each
 * value is held from its point to the next one, so bars next to each other touch. Where the next
 * point is further away than the usual gap (a gap in the data), the bar ends after the usual gap
 * and the line drops to zero, so a bar is never stretched over a time with no data. The last bar
 * also ends after the usual gap.
 */
internal fun barPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    // x has to grow strictly, so a second point at the same time is dropped
    val sorted = points.sortedBy { it.first }.fold(mutableListOf<Pair<Double, Double>>()) { acc, p ->
        if (acc.isEmpty() || p.first > acc.last().first) acc.add(p)
        acc
    }
    if (sorted.isEmpty()) return emptyList()
    val typical = typicalGap(sorted)
    val out = ArrayList<Pair<Double, Double>>(sorted.size + 1)
    for ((index, point) in sorted.withIndex()) {
        out.add(point)
        val end = point.first + typical
        val next = sorted.getOrNull(index + 1)
        if (next == null || next.first > end) out.add(end to 0.0)
    }
    return out
}

/**
 * The runs to draw for one trace in [style]: bars, runs broken at gaps for a line or area, or the
 * points as they are for dots. Each run becomes one series in the chart model.
 */
internal fun shapeForStyle(points: List<Pair<Double, Double>>, style: PlotStyle): List<List<Pair<Double, Double>>> =
    when (style) {
        PlotStyle.BARS                 -> listOf(barPoints(points)).filter { it.isNotEmpty() }
        PlotStyle.LINE, PlotStyle.AREA -> splitAtGaps(points)
        PlotStyle.POINTS               -> listOf(points).filter { it.isNotEmpty() }
    }

// =========================================================================
// Editing the trace list of a graph
// =========================================================================

/** Most traces on one graph. They share at most two axes. */
internal const val MAX_TRACES_PER_GRAPH = 6

/** Most axes on one graph: left and right. */
private const val MAX_AXES_PER_GRAPH = 2

/**
 * The traces cut down to fit one graph: at most [MAX_AXES_PER_GRAPH] axis groups and
 * [MAX_TRACES_PER_GRAPH] traces. The group of [keep] always stays; the other groups are kept from
 * the newest, so the oldest goes when a new one does not fit.
 */
internal fun List<GraphTrace>.fitToGraph(keep: GraphTrace? = lastOrNull()): List<GraphTrace> {
    val keepKey = keep?.axisKey()
    val keys = map { it.axisKey() }.distinct()
    val kept = (listOfNotNull(keepKey) + keys.reversed().filter { it != keepKey }).take(MAX_AXES_PER_GRAPH).toSet()
    val fitted = filter { it.axisKey() in kept }
    return if (fitted.size > MAX_TRACES_PER_GRAPH) fitted.drop(fitted.size - MAX_TRACES_PER_GRAPH) else fitted
}

/** Adds or removes the trace that shows [type] as it is. A trace that does not fit pushes out the oldest axis group. */
internal fun List<GraphTrace>.toggleRaw(type: SeriesType): List<GraphTrace> {
    val existing = indexOfFirst { it.type == type && !it.isDerived }
    if (existing >= 0) return filterIndexed { index, _ -> index != existing }
    val added = GraphTrace(type)
    return (this + added).fitToGraph(added)
}

/** Calculations offered, in order, for a new calculated trace. */
private val NEW_DERIVED_CHOICES = listOf(
    TraceTransform.EWMA to 30, TraceTransform.ROLLING_MEAN to 30, TraceTransform.EWMA to 60,
    TraceTransform.ROLLING_MEAN to 60, TraceTransform.EWMA to 120, TraceTransform.ROLLING_MEAN to 120
)

/**
 * Adds a calculated trace over the series of the first trace: the first EWMA or rolling mean in
 * [NEW_DERIVED_CHOICES] that is not there yet. It shares the series' axis. Null when the graph is
 * empty or full.
 */
internal fun List<GraphTrace>.withNewDerived(): List<GraphTrace>? {
    if (isEmpty() || size >= MAX_TRACES_PER_GRAPH) return null
    val type = first().type
    val candidate = NEW_DERIVED_CHOICES
        .map { (transform, window) -> GraphTrace(type, transform, window) }
        .firstOrNull { new -> none { it.sameDataAs(new) } } ?: return null
    return this + candidate
}

/**
 * [trace] in place of the one at [index], unless it would show the same data as another trace. When
 * the change makes a third axis group, the oldest other group goes.
 */
internal fun List<GraphTrace>.replaced(index: Int, trace: GraphTrace): List<GraphTrace> {
    if (index !in indices) return this
    if (withIndex().any { (i, other) -> i != index && other.sameDataAs(trace) }) return this
    return mapIndexed { i, old -> if (i == index) trace else old }.fitToGraph(trace)
}
