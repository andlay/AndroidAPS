package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.overview.graph.GraphConfig.Companion.MAX_GRAPH_HEIGHT_DP
import kotlinx.coroutines.flow.StateFlow

/**
 * Series types available for all graphs (BG primary and secondary).
 * Each type maps to a data flow in OverviewDataCache.
 * IOB implicitly includes bolus markers, COB implicitly includes carbs markers.
 *
 * PREDICTIONS is not a series: it is the switch for the predictions on the BG graph. All other
 * types can be traces on any graph.
 */
enum class SeriesType {

    /** Glucose readings in the user's units. Drawn by the BG graph itself; as a trace it is the source of calculations over BG. */
    BG,

    IOB,
    ABS_IOB,
    COB,
    BGI,
    DEVIATIONS,

    /** Carbs (g) per 5 minutes that a rise no logged carbs explain is worth (deviation / CSF). Display only. */
    UAM_CARBS,
    SENSITIVITY,
    VAR_SENSITIVITY,
    DEV_SLOPE,
    HEART_RATE,
    STEPS,
    ACTIVITY,

    /** Basal rate actually delivered (temp basals included), U/h. */
    BASAL,
    PREDICTIONS
}

/** How a trace is drawn. */
enum class PlotStyle {

    /** A line between the points. It breaks where the data has a gap. */
    LINE,

    /** A dot at each point, no line. */
    POINTS,

    /** A bar from zero up to each point, as wide as the time the point covers. */
    BARS,

    /** A line with a soft fill below it. */
    AREA
}

/** A calculation over the points of a series, drawn as its own trace. Display only. */
enum class TraceTransform {

    /** The series itself. */
    NONE,

    /** Exponentially weighted moving average. The window is the time constant. */
    EWMA,

    /** Average of the points in the last window. */
    ROLLING_MEAN,

    /** Sum of the points in the last window (for example steps per hour). */
    ROLLING_SUM;

    /** True when the result has the same unit and scale as the series, so both can share one axis. */
    val keepsScale: Boolean get() = this != ROLLING_SUM
}

/**
 * One trace on a secondary graph: a series, an optional calculation over it, and how it looks.
 *
 * @param windowMinutes window of [transform]. Not used for [TraceTransform.NONE].
 * @param style how it is drawn, or null for the series' own default look
 * @param color colour as ARGB, or null for the series' own colour
 */
data class GraphTrace(
    val type: SeriesType,
    val transform: TraceTransform = TraceTransform.NONE,
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    val style: PlotStyle? = null,
    val color: Long? = null
) {

    val isDerived: Boolean get() = transform != TraceTransform.NONE

    /** True when [other] is the same data, so the two would draw the same trace twice. */
    fun sameDataAs(other: GraphTrace): Boolean =
        type == other.type && transform == other.transform && (!isDerived || windowMinutes == other.windowMinutes)

    companion object {

        const val DEFAULT_WINDOW_MINUTES = 30
        const val MIN_WINDOW_MINUTES = 5
        const val MAX_WINDOW_MINUTES = 480
    }
}

/**
 * The value range of one axis. Each end is fixed by the user or found from the data in view (null).
 * Data outside a fixed range is cut off at the edge of the graph.
 */
data class AxisRange(val min: Double? = null, val max: Double? = null) {

    val isAuto: Boolean get() = min == null && max == null

    /** [autoMin]..[autoMax] with the fixed ends put in. Null when that would leave nothing to show. */
    fun applyTo(autoMin: Double, autoMax: Double): Pair<Double, Double>? {
        val low = min ?: autoMin
        val high = max ?: autoMax
        return if (high > low) low to high else null
    }

    companion object {

        val AUTO = AxisRange()
    }
}

/**
 * Secondary graph entry: trace list + per-graph height (dp).
 * Traces with the same scale share an axis, so a series can be shown with any number of
 * calculations over it, or only with those. At most two axes (left and right) per graph.
 * Height is per-graph user-adjustable.
 *
 * @param leftRange  range of the left axis, in the unit of its traces
 * @param rightRange range of the right axis. With IOB on the left and nothing on the right, the
 *   right axis is the basal rate (U/h) and only its max is used.
 * @param activityOverlay draw insulin activity over IOB, scaled to fit. Only used when the graph
 *   starts with IOB.
 */
data class SecondaryGraph(
    val traces: List<GraphTrace>,
    val height: Int = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP,
    val leftRange: AxisRange = AxisRange.AUTO,
    val rightRange: AxisRange = AxisRange.AUTO,
    val activityOverlay: Boolean = false
) {

    /** The series the traces are made from, in trace order. */
    val series: List<SeriesType> get() = traces.map { it.type }

    companion object {

        /** A graph that shows [series] as they are, with their default look. */
        fun of(series: List<SeriesType>, height: Int = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP) =
            SecondaryGraph(series.map { GraphTrace(it) }, height)
    }
}

/**
 * Configuration for the overview graphs.
 *
 * The BG graph is always there and always shows BG on its left axis. Everything else can be set:
 *
 * @param bgOverlays  switches of the BG graph: [SeriesType.ACTIVITY] and [SeriesType.PREDICTIONS]
 * @param bgTraces    more traces on the BG graph. Calculations over BG share the BG axis; the
 *   first other axis group goes on the right axis. While there is one, the BG graph's basal band
 *   is not drawn (it uses the right side).
 * @param bgLeftRange range of the BG axis, in mg/dL
 * @param bgRightRange range of the right axis of the BG graph, in the unit of its traces
 * @param secondaryGraphs the graphs under the BG graph, in order. The first one is IOB with basal
 *   unless the user changed it. The traces of a graph keep their selection order: the first
 *   trace's axis group is the left axis, the next group the right axis (see [SecondaryGraph]).
 */
data class GraphConfig(
    val bgOverlays: List<SeriesType> = listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS),
    val bgHeight: Int = DEFAULT_GRAPH_HEIGHT_DP,
    val bgTraces: List<GraphTrace> = emptyList(),
    val bgLeftRange: AxisRange = AxisRange.AUTO,
    val bgRightRange: AxisRange = AxisRange.AUTO,
    val secondaryGraphs: List<SecondaryGraph> = listOf(
        DEFAULT_IOB_GRAPH,
        SecondaryGraph.of(listOf(SeriesType.COB))
    )
) {

    companion object {

        /** Maximum number of graphs under the BG graph (the old fixed IOB graph counts as one) */
        const val MAX_SECONDARY_GRAPHS = 6

        /** The graph that was fixed under the BG graph before: IOB with bolus markers and basal, and activity over it. */
        val DEFAULT_IOB_GRAPH = SecondaryGraph(listOf(GraphTrace(SeriesType.IOB)), activityOverlay = true)

        /** Default graph height in dp (minimum value — user-adjustable up to [MAX_GRAPH_HEIGHT_DP]) */
        const val DEFAULT_GRAPH_HEIGHT_DP = 100

        /** Maximum graph height in dp (2.5x default) */
        const val MAX_GRAPH_HEIGHT_DP = 250
    }
}

/**
 * Repository for graph configuration persistence.
 * Loads/saves GraphConfig and exposes it as a reactive StateFlow.
 */
interface GraphConfigRepository {

    /** Current graph configuration, updated reactively on changes */
    val graphConfigFlow: StateFlow<GraphConfig>

    /** Update and persist graph configuration */
    fun update(config: GraphConfig)
}
