package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.overview.graph.GraphConfig.Companion.MAX_GRAPH_HEIGHT_DP
import kotlinx.coroutines.flow.StateFlow

/**
 * Series types available for all graphs (BG primary and secondary).
 * Each type maps to a data flow in OverviewDataCache.
 * IOB implicitly includes bolus markers, COB implicitly includes carbs markers.
 *
 * Not all types are allowed on all graphs — the UI enforces allowed sets per graph type.
 * BG graph: only BASAL, ACTIVITY.
 * Secondary graphs: all types.
 */
enum class SeriesType {

    IOB,
    ABS_IOB,
    COB,
    BGI,
    DEVIATIONS,
    SENSITIVITY,
    VAR_SENSITIVITY,
    DEV_SLOPE,
    HEART_RATE,
    STEPS,
    ACTIVITY,
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
 * Secondary graph entry: trace list + per-graph height (dp).
 * Traces with the same scale share an axis, so a series can be shown with any number of
 * calculations over it, or only with those. At most two axes (left and right) per graph.
 * Height is per-graph user-adjustable.
 */
data class SecondaryGraph(
    val traces: List<GraphTrace>,
    val height: Int = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
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
 * Fixed graphs (not removable):
 * - BG graph: blood glucose with optional activity overlay (toggled via [bgOverlays]).
 * - IOB graph: IOB line with bolus markers + flipped basal overlay, with optional activity
 *   overlay (toggled via [iobOverlays]).
 *
 * @param bgOverlays       Overlay toggles for the BG graph (currently only [SeriesType.ACTIVITY]).
 * @param iobOverlays      Overlay toggles for the fixed IOB graph (currently only [SeriesType.ACTIVITY]).
 * @param secondaryGraphs  Ordered list of user-configurable secondary graph configurations.
 *   IOB as it is cannot appear here (it has a dedicated fixed slot). The traces of a graph keep
 *   their selection order: the first trace's axis group is the left axis, the next group the
 *   right axis (see [SecondaryGraph]).
 */
data class GraphConfig(
    val bgOverlays: List<SeriesType> = listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS),
    val iobOverlays: List<SeriesType> = listOf(SeriesType.ACTIVITY),
    val bgHeight: Int = DEFAULT_GRAPH_HEIGHT_DP,
    val iobHeight: Int = DEFAULT_GRAPH_HEIGHT_DP,
    val secondaryGraphs: List<SecondaryGraph> = listOf(
        SecondaryGraph.of(listOf(SeriesType.COB))
    )
) {

    companion object {

        /** Maximum number of secondary graphs allowed */
        const val MAX_SECONDARY_GRAPHS = 5

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
