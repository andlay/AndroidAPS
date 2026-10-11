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
import kotlin.math.min

// Traces on the secondary graphs: the calculations (EWMA, rolling mean, rolling sum), the shapes the
// plot styles need (bars, a line that breaks at gaps) and the look of each trace. Display only:
// nothing here is used by the loop or the calculation workflow.

/**
 * Series whose look (style and colour) the user can change. The others have their own drawing:
 * IOB and COB carry treatment markers, DEV is coloured per point, DEV slope has two lines.
 */
internal val STYLABLE_SERIES = setOf(
    SeriesType.ABS_IOB, SeriesType.BGI, SeriesType.SENSITIVITY, SeriesType.VAR_SENSITIVITY,
    SeriesType.HEART_RATE, SeriesType.STEPS, SeriesType.ACTIVITY
)

/** True when the user can change how this trace looks. Every derived trace can. */
internal val GraphTrace.isStylable: Boolean get() = isDerived || type in STYLABLE_SERIES

/** A trace of this series that keeps its unit and scale (the series itself, EWMA or mean). */
internal fun GraphTrace.keepsScaleOf(type: SeriesType): Boolean = this.type == type && transform.keepsScale

/** Style used when the user has not picked one. */
internal fun defaultStyle(trace: GraphTrace): PlotStyle =
    if (trace.isDerived) PlotStyle.LINE
    else when (trace.type) {
        SeriesType.ABS_IOB, SeriesType.ACTIVITY -> PlotStyle.AREA
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
        // Absolute IOB is a step series; a calculation over it is not
        square = trace.type == SeriesType.ABS_IOB && !trace.isDerived && trace.effectiveStyle() != PlotStyle.POINTS
    )

/** Colour as stored in [GraphTrace.color]. */
internal fun Color.toStoredColor(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Colours offered in the edit sheet. Bright enough for the dark graph background. */
internal val TRACE_COLOR_CHOICES = listOf(
    Color(0xFFFFFF66), Color(0xFF66FFB8), Color(0xFF00EEEE), Color(0xFF1E88E5), Color(0xFFB388FF),
    Color(0xFFFF66CC), Color(0xFFFF5252), Color(0xFFFB8C00), Color(0xFFD3F166), Color(0xFFBDBDBD)
)

private const val BAR_FILL_ALPHA = 0.6f
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

        PlotStyle.BARS                 -> LineCartesianLayer.Line(
            fill = lineFill,
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
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

/** Space between two bars, as a part of the bar width. */
private const val BAR_GAP_FRACTION = 0.15

/**
 * The points as bars for a [Square] line with an area fill: a bar from zero up to each value, then
 * back to zero. Each bar is as wide as the time to the next (or previous) point, but never wider
 * than the usual gap, so a bar after a gap in the data is not stretched over the gap.
 *
 * @param startAligned true when a point is the start of the time it covers (heart rate, BGI, steps),
 *   false when it is the end (a rolling value over the time before it)
 */
internal fun barPoints(points: List<Pair<Double, Double>>, startAligned: Boolean): List<Pair<Double, Double>> {
    // x has to grow strictly, so a second point at the same time is dropped
    val sorted = points.sortedBy { it.first }.fold(mutableListOf<Pair<Double, Double>>()) { acc, p ->
        if (acc.isEmpty() || p.first > acc.last().first) acc.add(p)
        acc
    }
    if (sorted.isEmpty()) return emptyList()
    val typical = typicalGap(sorted)
    val out = ArrayList<Pair<Double, Double>>(sorted.size * 2)
    for ((index, point) in sorted.withIndex()) {
        val (x, y) = point
        if (startAligned) {
            val width = min(sorted.getOrNull(index + 1)?.let { it.first - x } ?: typical, typical)
            out.add(x to y)
            out.add(x + width * (1 - BAR_GAP_FRACTION) to 0.0)
        } else {
            val width = min(sorted.getOrNull(index - 1)?.let { x - it.first } ?: typical, typical)
            out.add(x - width * (1 - BAR_GAP_FRACTION) to y)
            out.add(x to 0.0)
        }
    }
    return out
}

/**
 * True when a point of this trace is the start of the time it covers: heart rate and BGI, and steps,
 * which the overview puts on the 5 minute grid at the start of each bucket (like the DEV bars).
 */
internal fun GraphTrace.barsStartAligned(): Boolean =
    !isDerived && (type == SeriesType.HEART_RATE || type == SeriesType.BGI || type == SeriesType.STEPS)

/**
 * The runs to draw for one trace in [style]: bars, runs broken at gaps for a line or area, or the
 * points as they are for dots. Each run becomes one series in the chart model.
 */
internal fun shapeForStyle(points: List<Pair<Double, Double>>, style: PlotStyle, startAligned: Boolean): List<List<Pair<Double, Double>>> =
    when (style) {
        PlotStyle.BARS                 -> listOf(barPoints(points, startAligned)).filter { it.isNotEmpty() }
        PlotStyle.LINE, PlotStyle.AREA -> splitAtGaps(points)
        PlotStyle.POINTS               -> listOf(points).filter { it.isNotEmpty() }
    }

// =========================================================================
// Editing the trace list of a graph
// =========================================================================

/** Most traces on one graph: one per axis. */
internal const val MAX_TRACES_PER_GRAPH = 2

/** Adds or removes the trace that shows [type] as it is. A third trace pushes out the oldest one. */
internal fun List<GraphTrace>.toggleRaw(type: SeriesType): List<GraphTrace> {
    val existing = indexOfFirst { it.type == type && !it.isDerived }
    if (existing >= 0) return filterIndexed { index, _ -> index != existing }
    val added = this + GraphTrace(type)
    return if (added.size > MAX_TRACES_PER_GRAPH) added.drop(added.size - MAX_TRACES_PER_GRAPH) else added
}

/**
 * Adds a calculated trace over the series of the first trace: an EWMA, or a rolling mean when that
 * EWMA is already there. Null when the graph is full or empty.
 */
internal fun List<GraphTrace>.withNewDerived(): List<GraphTrace>? {
    if (isEmpty() || size >= MAX_TRACES_PER_GRAPH) return null
    val type = first().type
    val candidate = listOf(TraceTransform.EWMA, TraceTransform.ROLLING_MEAN, TraceTransform.ROLLING_SUM)
        .map { GraphTrace(type, it) }
        .firstOrNull { new -> none { it.sameDataAs(new) } } ?: return null
    return this + candidate
}

/** [trace] in place of the one at [index], unless it would show the same data as another trace. */
internal fun List<GraphTrace>.replaced(index: Int, trace: GraphTrace): List<GraphTrace> {
    if (index !in indices) return this
    if (withIndex().any { (i, other) -> i != index && other.sameDataAs(trace) }) return this
    return mapIndexed { i, old -> if (i == index) trace else old }
}
