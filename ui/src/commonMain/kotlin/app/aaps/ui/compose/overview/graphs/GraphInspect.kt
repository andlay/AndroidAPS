package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.icons.IcHeartRate
import app.aaps.core.ui.compose.icons.IcSteps
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// =========================================================================
// Press and hold on any graph: a dashed line at that time in every graph, a dot on the reading when
// the finger is on the BG graph, and a pop-up with the values of every graph at that time, one line
// per graph, next to the graph the finger is on. Inspired by Trio and TrioFollower.
// =========================================================================

/** How far a reading or a value may be from the finger's time and still count, in ms. */
private const val INSPECT_MATCH_MS = 5 * 60_000L

/** A bolus or carb entry belongs to a reading when it is within half a reading interval of it, in ms. */
private const val INSPECT_TREATMENT_MS = 150_000L

/** How strongly the band between two fingers is filled, as alpha of the line colour. */
private const val RANGE_BAND_ALPHA = 0.12f

/** [GraphInspectState.source] while the finger is on the BG graph. */
const val INSPECT_SOURCE_BG = -1

/** How long the pop-up stays after the finger is lifted. */
private const val INSPECT_LINGER_MS = 2_000L

/** Size of the icons in the pop-up. */
private val PILL_ICON_SIZE = 14.dp

/** The COB icon is drawn smaller, see `InspectValueItem`. */
private val PILL_COB_ICON_SIZE = 12.dp

/**
 * Shared state between Vico (marker, controller, listener) and the compose side (pop-up, touch layers).
 * Vico reports where the finger is ([targetX]); the compose side snaps it to a reading and tells the
 * marker where to draw ([shownX], [dotValue], [dotColor]).
 */
@Stable
class GraphInspectState {

    /** True from the long press until the finger is lifted. */
    var fingerDown by mutableStateOf(false)

    /** A finger is on the BG graph, from the touch layer. Plain field: only read in callbacks. */
    var pointerDown = false

    /** The graph x (minutes from the start of the graph) under the finger: from Vico on BG, from the touch on the other graphs. */
    var targetX by mutableStateOf<Double?>(null)

    /** The graph the finger is on: [INSPECT_SOURCE_BG], or the index of a graph under BG. */
    var source by mutableIntStateOf(INSPECT_SOURCE_BG)

    /** Press and hold on graph [graphIndex] under BG, at graph x [x]. See `rememberForwardToBgInput`. */
    fun startHold(graphIndex: Int, x: Double?) {
        source = graphIndex
        targetX = x
        fingerDown = true
    }

    /** The finger on a graph under BG was lifted. */
    fun endHold() {
        fingerDown = false
        secondX = null
    }

    /**
     * The graph x under a second finger, put down while holding: then the pop-up shows the range
     * between the two fingers. Null with one finger.
     */
    var secondX by mutableStateOf<Double?>(null)

    /**
     * True once a second finger came down on the BG graph: from then on the touch layer follows the
     * fingers itself (see [graphInspectTouch]) and Vico's marker no longer does, until all are lifted.
     */
    var takenOver = false

    /** The graph x of the reading the line and dot are drawn at. */
    var shownX by mutableStateOf<Double?>(null)

    /** The graph x of the reading at the second finger. */
    var shownSecondX by mutableStateOf<Double?>(null)

    /** Where the inspect line is drawn in every graph: at the reading, while the finger is down. */
    val lineX: Double? get() = if (fingerDown) shownX else null

    /** Where the second line is drawn, with the band between the two, while two fingers are down. */
    val secondLineX: Double? get() = if (fingerDown && secondX != null) shownSecondX else null
    var dotValue by mutableStateOf<Double?>(null)
    var dotColor by mutableStateOf(Color.Unspecified)

    val controller: CartesianMarkerController = object : CartesianMarkerController {
        override val acceptsLongPress: Boolean = true

        // Only a long press starts it; after that it follows the finger until it is lifted or a zoom
        // starts. With a second finger the touch layer has taken over and Vico is left out.
        override fun shouldAcceptInteraction(interaction: Interaction, targets: List<CartesianMarker.Target>): Boolean =
            when {
                takenOver -> false
                else      -> acceptsWithOneFinger(interaction)
            }

        private fun acceptsWithOneFinger(interaction: Interaction): Boolean =
            when (interaction) {
                is Interaction.LongPress                                          -> true
                is Interaction.Move, is Interaction.Release, is Interaction.Zoom -> fingerDown
                else                                                              -> false
            }

        override fun shouldShowMarker(interaction: Interaction, targets: List<CartesianMarker.Target>): Boolean {
            val show = interaction is Interaction.LongPress || interaction is Interaction.Move
            if (interaction is Interaction.LongPress) source = INSPECT_SOURCE_BG
            fingerDown = show
            return show
        }
    }

    val visibilityListener: CartesianMarkerVisibilityListener = object : CartesianMarkerVisibilityListener {
        override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
            targetX = targets.firstOrNull()?.x
        }

        override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
            targetX = targets.firstOrNull()?.x
        }

        // Vico also hides the marker when the finger leaves the plotted area (no reading there), with
        // the finger still down. Keep inspecting then: the pop-up stays, and the line follows again on return.
        override fun onHidden(marker: CartesianMarker) {
            if (!pointerDown) fingerDown = false
        }
    }
}

/**
 * Draws a dot on the chosen reading (the line through all graphs is [InspectLine]).
 * Vico places the marker at its own nearest point (which may be a basal or prediction point), so the
 * position is moved to the reading the pop-up shows.
 */
class GraphInspectMarker(
    private val state: GraphInspectState
) : CartesianMarker {

    private val dotCenter = ShapeComponent(fill = Fill(Color.White), shape = CircleShape)

    override fun drawOverLayers(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>) {
        val target = targets.firstOrNull() ?: return
        val x = state.shownX ?: return
        with(context) {
            val canvasX = target.canvasX + ((x - target.x) / ranges.xStep).toFloat() * layerDimensions.xSpacing
            if (canvasX < layerBounds.left || canvasX > layerBounds.right) return
            val value = state.dotValue ?: return
            val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
            val canvasY = layerBounds.bottom - ((value - yRange.minY) / yRange.length).toFloat() * layerBounds.height
            val outer = 7.dp.pixels
            val inner = 3.dp.pixels
            ShapeComponent(fill = Fill(state.dotColor), shape = CircleShape)
                .draw(context, canvasX - outer, canvasY - outer, canvasX + outer, canvasY + outer)
            dotCenter.draw(context, canvasX - inner, canvasY - inner, canvasX + inner, canvasY + inner)
        }
    }
}

/**
 * The vertical line at the inspected reading, drawn in every graph so they all point at the same time.
 * Drawn like [NowLine] (dashed, same thickness), and moved with the finger through [GraphInspectState.lineX].
 */
class InspectLine(
    private val state: GraphInspectState,
    private val color: Color,
    private val strokeWidthPx: Float = 2f,
    private val dashLengthPx: Float = 6f,
    private val gapLengthPx: Float = 4f
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val x = state.lineX ?: return
        val second = state.secondLineX
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return
            // Same x to canvas transform as NowLine
            fun canvasX(graphX: Double) = layerBounds.left +
                layerDimensions.startPadding +
                layerDimensions.xSpacing * ((graphX - ranges.minX) / xStep).toFloat() -
                scroll
            with(mutableDrawScope) {
                if (second != null) {
                    // The range between two fingers: a light band, cut to the plot
                    val left = minOf(canvasX(x), canvasX(second)).coerceIn(layerBounds.left, layerBounds.right)
                    val right = maxOf(canvasX(x), canvasX(second)).coerceIn(layerBounds.left, layerBounds.right)
                    if (right > left)
                        drawRect(this@InspectLine.color.copy(alpha = RANGE_BAND_ALPHA), Offset(left, layerBounds.top), Size(right - left, layerBounds.height))
                }
                for (lineX in listOfNotNull(x, second)) {
                    val cx = canvasX(lineX)
                    if (cx < layerBounds.left || cx > layerBounds.right) continue
                    drawLine(
                        color = this@InspectLine.color,
                        start = Offset(cx, layerBounds.top),
                        end = Offset(cx, layerBounds.bottom),
                        strokeWidth = strokeWidthPx,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLengthPx, gapLengthPx), 0f)
                    )
                }
            }
        }
    }
}

/** The [InspectLine] for one graph, or null when [state] is null. Colored like the "now" line, a bit lighter. */
@Composable
fun rememberInspectLine(state: GraphInspectState?): InspectLine? {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    return remember(state, color) { state?.let { InspectLine(it, color) } }
}

/**
 * While inspecting, eats finger moves before the graph sees them, so the graph does not scroll and
 * only the marker follows the finger. Vico still gets the moves for the marker: its interaction
 * handler does not skip consumed events, the scrolling does.
 * Also tracks whether a finger is down, so only lifting it ends inspecting (see onHidden).
 *
 * A second finger put down while inspecting is not a zoom: the touch layer takes over from Vico and
 * follows both fingers itself, turning their places into times with [geometry] (the x layout all
 * graphs share). The first finger keeps [GraphInspectState.targetX], the second sets
 * [GraphInspectState.secondX]. Lifting the second finger goes back to one finger.
 */
fun Modifier.graphInspectTouch(state: GraphInspectState, geometry: GraphGeometryHolder): Modifier = pointerInput(state, geometry) {
    awaitPointerEventScope {
        var firstId: PointerId? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            state.pointerDown = pressed.isNotEmpty()
            if (!state.pointerDown) {
                state.fingerDown = false
                state.secondX = null
                state.takenOver = false
                firstId = null
                continue
            }
            if (!state.fingerDown) {
                firstId = null
                continue
            }
            val first = pressed.firstOrNull { it.id == firstId } ?: pressed.first()
            firstId = first.id
            val second = pressed.firstOrNull { it.id != first.id }
            if (second != null) state.takenOver = true
            if (state.takenOver) {
                geometry.xAt(first.position.x)?.let { state.targetX = it }
                state.secondX = second?.let { geometry.xAt(it.position.x) }
                event.changes.forEach { it.consume() }
            } else if (event.type == PointerEventType.Move) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Where the graphs are, for placing the pop-up. Each graph reports its layout here
 * ([Modifier.inspectGraph]), and the box around all graphs reports [box]. Plain fields, not state:
 * they are only read while the pop-up is placed.
 */
class InspectLayout {

    var box: LayoutCoordinates? = null
    val graphs = HashMap<Int, LayoutCoordinates>()

    /** Top and bottom of graph [source] inside [box], or null when it is not on screen. */
    fun boundsOf(source: Int): Pair<Float, Float>? {
        val box = box?.takeIf { it.isAttached } ?: return null
        val graph = graphs[source]?.takeIf { it.isAttached } ?: return null
        val top = box.localPositionOf(graph, Offset.Zero).y
        return top to top + graph.size.height
    }
}

/** Reports this graph's layout as graph [source] (see [InspectLayout]). */
fun Modifier.inspectGraph(layout: InspectLayout, source: Int): Modifier =
    onGloballyPositioned { layout.graphs[source] = it }

/**
 * Top of the pop-up inside the box of all graphs. It must not cover the graph the finger is on
 * ([touchedTop]..[touchedBottom]): above that graph when there is room, else under it.
 */
internal fun inspectPopupTop(touchedTop: Float, touchedBottom: Float, popupHeight: Float, gap: Float): Float {
    val above = touchedTop - gap - popupHeight
    return if (above >= 0f) above else touchedBottom + gap
}

/** Gap between the pop-up and the graph the finger is on. */
private val POPUP_GAP = 4.dp

/** Space kept free at the sides of the pop-up. */
private val POPUP_SIDE_MARGIN = 24.dp

/**
 * The compose side of press and hold: snaps the finger to a reading, gives haptic feedback, and shows
 * the pop-up until [INSPECT_LINGER_MS] after the finger is lifted. The finger is followed with
 * snapshotFlow, not effect keys, so a move recomposes only this, and only when the reading changes.
 *
 * Goes in the box around all graphs, after them, so it is drawn on top, with a [modifier] that makes
 * it as big as that box (matchParentSize). It puts the pop-up next to the graph the finger is on
 * ([GraphInspectState.source]), never over it.
 */
@Composable
fun GraphInspectOverlay(
    inspect: GraphInspectState,
    graphViewModel: GraphViewModel,
    graphConfig: GraphConfig,
    bgReadings: List<BgDataPoint>,
    minTimestamp: Long?,
    inspectLayout: InspectLayout,
    modifier: Modifier = Modifier
) {
    var inspectData by remember { mutableStateOf<InspectData?>(null) }
    // The reading at the second finger; null with one finger
    var secondData by remember { mutableStateOf<InspectData?>(null) }
    val treatmentGraph by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val currentBgReadings by rememberUpdatedState(bgReadings)
    val currentMinTimestamp by rememberUpdatedState(minTimestamp)
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(inspect, haptic) {
        snapshotFlow { Triple(inspect.fingerDown, inspect.targetX, inspect.secondX) }.collect { (fingerDown, x, x2) ->
            val min = currentMinTimestamp
            if (!fingerDown || x == null || min == null) return@collect
            fun at(graphX: Double) = inspectDataAt(
                timestamp = min + (graphX * 60_000).toLong(),
                bgReadings = currentBgReadings,
                boluses = treatmentGraph.boluses,
                carbs = treatmentGraph.carbs
            )
            val data = at(x)
            val second = x2?.let { at(it) }
            if (data.timestamp != inspectData?.timestamp || second?.timestamp != secondData?.timestamp)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            inspect.shownX = timestampToX(data.timestamp, min)
            inspect.shownSecondX = second?.let { timestampToX(it.timestamp, min) }
            inspect.dotValue = data.bg?.value
            inspectData = data
            secondData = second
        }
    }
    LaunchedEffect(inspect, haptic) {
        snapshotFlow { inspect.fingerDown }.collect { if (it) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    LaunchedEffect(inspect) {
        snapshotFlow { inspect.fingerDown to inspectData }.collectLatest { (fingerDown, data) ->
            if (!fingerDown && data != null) {
                delay(INSPECT_LINGER_MS)
                inspectData = null
                secondData = null
            }
        }
    }

    val data = inspectData
    val dotColor = data?.bg?.range?.inspectColor() ?: Color.Unspecified
    SideEffect { inspect.dotColor = dotColor }
    if (data == null) return

    // Only collected while the pop-up shows. The graphs already hold these flows, so the values are there at once.
    val rowTraces = remember(graphConfig) { inspectRowTraces(graphConfig) }
    val points = rowTraces.flatten().distinct().associateWith { trace -> key(trace) { rememberTracePoints(graphViewModel, trace) } }
    val second = secondData
    val range = second?.let { inspectRangeOf(data, it, treatmentGraph.boluses, treatmentGraph.carbs) }
    val rows = rowTraces.map { traces -> inspectValues(traces, points, data.timestamp) }
    val rangeRows = range?.let { r -> rowTraces.map { traces -> inspectRangeValues(traces, points, r.from, r.to) } }

    val gap = POPUP_GAP
    val sideMargin = POPUP_SIDE_MARGIN
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val margin = sideMargin.roundToPx()
                val placeable = measurable.measure(Constraints(maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
                layout(constraints.maxWidth, if (constraints.hasBoundedHeight) constraints.maxHeight else 0) {
                    val (top, bottom) = inspectLayout.boundsOf(inspect.source) ?: (0f to 0f)
                    val y = inspectPopupTop(top, bottom, placeable.height.toFloat(), gap.toPx())
                    placeable.place((constraints.maxWidth - placeable.width) / 2, y.roundToInt())
                }
            }
    ) {
        if (range != null && rangeRows != null) GraphInspectRangePopup(range = range, rows = rangeRows)
        else GraphInspectPopup(data = data, rows = rows)
    }
}

/**
 * The range between two fingers: from the earlier reading to the later one, the glucose at both
 * ends, and the insulin and carbs given from [from] up to [to].
 */
data class InspectRange(
    val from: Long,
    val to: Long,
    val bgFrom: BgDataPoint?,
    val bgTo: BgDataPoint?,
    val insulin: Double,
    val carbs: Double
)

/** The range between the readings [a] and [b], in time order whichever finger is left. */
fun inspectRangeOf(a: InspectData, b: InspectData, boluses: List<BolusGraphPoint>, carbs: List<CarbsGraphPoint>): InspectRange {
    val (first, last) = if (a.timestamp <= b.timestamp) a to b else b to a
    val inRange = { t: Long -> t >= first.timestamp && t < last.timestamp }
    return InspectRange(
        from = first.timestamp,
        to = last.timestamp,
        bgFrom = first.bg,
        bgTo = last.bg,
        insulin = boluses.filter { it.isValid && inRange(it.timestamp) }.sumOf { it.amount },
        carbs = carbs.filter { it.isValid && inRange(it.timestamp) }.sumOf { it.amount }
    )
}

/** One value of a range: a sum over it, or the change from its start to its end. */
internal data class InspectRangeValue(val trace: GraphTrace, val value: Double, val isSum: Boolean)

/**
 * Series whose points are amounts per 5 minutes, so a range adds them up. A point is the amount of
 * the 5 minutes up to it: the change from one reading to the next is the DEV and BGI points after
 * the first reading up to and with the last (so DEV + BGI add up to the BG change).
 */
private val SUMMED_UP_TO_POINT = setOf(SeriesType.DEVIATIONS, SeriesType.BGI, SeriesType.CARBS_ABSORBED)

/** Summed series whose point starts its 5 minutes: steps, and basal as insulin delivered. */
private val SUMMED_FROM_POINT = setOf(SeriesType.STEPS, SeriesType.BASAL)

/**
 * The values of [traces] over the range [from]..[to]: a sum for amounts per 5 minutes (basal as
 * units delivered), else the change, the value at [to] minus the value at [from]. A calculation over
 * a series (EWMA, rolling sum) is a level, so it gets the change. Left out when there is no data.
 */
internal fun inspectRangeValues(traces: List<GraphTrace>, points: Map<GraphTrace, List<GraphDataPoint>>, from: Long, to: Long): List<InspectRangeValue> =
    traces.mapNotNull { trace ->
        val pts = points[trace].orEmpty()
        when {
            !trace.isDerived && (trace.type in SUMMED_UP_TO_POINT || trace.type in SUMMED_FROM_POINT) -> {
                val inRange = if (trace.type in SUMMED_UP_TO_POINT) pts.filter { it.timestamp > from && it.timestamp <= to }
                else pts.filter { it.timestamp >= from && it.timestamp < to }
                if (inRange.isEmpty()) null
                else {
                    // Basal is a rate in U/h sampled every 5 minutes: units delivered
                    val perPoint = if (trace.type == SeriesType.BASAL) BASAL_SAMPLE_MS / 3_600_000.0 else 1.0
                    InspectRangeValue(trace, inRange.sumOf { it.value } * perPoint, isSum = true)
                }
            }

            else -> {
                val start = traceValueAt(pts, from, trace) ?: return@mapNotNull null
                val end = traceValueAt(pts, to, trace) ?: return@mapNotNull null
                InspectRangeValue(trace, end - start, isSum = false)
            }
        }
    }

/** The values at one reading time. Null [bg]: no reading near that time. */
data class InspectData(
    val timestamp: Long,
    val bg: BgDataPoint?,
    val boluses: List<BolusGraphPoint>,
    val carbs: List<CarbsGraphPoint>
)

/**
 * Finds what to show for [timestamp]: the nearest reading (within 5 min), and the boluses and carbs
 * given within half a reading interval of that reading's time.
 */
fun inspectDataAt(
    timestamp: Long,
    bgReadings: List<BgDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>
): InspectData {
    val bg = bgReadings.nearest(timestamp) { it.timestamp }
    val time = bg?.timestamp ?: timestamp
    return InspectData(
        timestamp = time,
        bg = bg,
        boluses = boluses.filter { it.isValid && abs(it.timestamp - time) <= INSPECT_TREATMENT_MS },
        carbs = carbs.filter { it.isValid && abs(it.timestamp - time) <= INSPECT_TREATMENT_MS }
    )
}

/**
 * The traces whose values the pop-up shows, one list per line: first the BG graph (its extra traces
 * and the activity overlay; BG itself and the treatments are added there by the pop-up), then each
 * graph under it, with the basal band of an IOB graph and its activity overlay.
 */
internal fun inspectRowTraces(config: GraphConfig): List<List<GraphTrace>> {
    val bg = buildList {
        addAll(config.bgTraces)
        if (SeriesType.ACTIVITY in config.bgOverlays) add(GraphTrace(SeriesType.ACTIVITY))
    }
    val graphs = config.secondaryGraphs.take(GraphConfig.MAX_SECONDARY_GRAPHS).map { graph ->
        buildList {
            addAll(graph.traces)
            if (rightAxisKey(graph.traces) == BASAL_AXIS_KEY) add(GraphTrace(SeriesType.BASAL))
            val first = axisGroups(graph.traces).first.firstOrNull()
            if (graph.activityOverlay && first?.type == SeriesType.IOB && !first.isDerived) add(GraphTrace(SeriesType.ACTIVITY))
        }.distinct()
    }
    return listOf(bg) + graphs
}

/** One value in the pop-up. */
internal data class InspectValue(val trace: GraphTrace, val value: Double)

/**
 * The values of [traces] at [time]. Left out: a trace with no data near that time, COB at 0 g, and
 * carbs absorbed at 0 g.
 */
internal fun inspectValues(traces: List<GraphTrace>, points: Map<GraphTrace, List<GraphDataPoint>>, time: Long): List<InspectValue> =
    traces.mapNotNull { trace ->
        val value = traceValueAt(points[trace].orEmpty(), time, trace) ?: return@mapNotNull null
        when {
            trace.isDerived                                         -> InspectValue(trace, value)
            trace.type == SeriesType.COB && shownCob(value) == null -> null
            trace.type == SeriesType.CARBS_ABSORBED && value <= 0.0 -> null
            else                                                    -> InspectValue(trace, value)
        }
    }

/** Series stored only where the value changes: the value at a time is the last change before it. */
private val STEP_SERIES = setOf(SeriesType.BASAL, SeriesType.ABS_IOB)

/**
 * The value of [trace] at [time]. A step series has the value of its last change before [time]; a
 * series whose points start a period (steps, heart rate) the value of the period [time] is in;
 * any other series its nearest point. Points more than 5 minutes away do not count.
 */
internal fun traceValueAt(points: List<GraphDataPoint>, time: Long, trace: GraphTrace): Double? = when {
    trace.isDerived                -> points.nearest(time) { it.timestamp }?.value
    trace.type in STEP_SERIES       -> points.filter { it.timestamp <= time }.maxByOrNull { it.timestamp }?.value
    trace.type in PERIOD_START_SERIES ->
        points.filter { it.timestamp <= time && time - it.timestamp <= INSPECT_MATCH_MS }.maxByOrNull { it.timestamp }?.value

    else                           -> points.nearest(time) { it.timestamp }?.value
}

/** COB in whole grams for the pop-up, or null to leave it out: no carbs on board is not worth a place. */
internal fun shownCob(cob: Double?): Int? = cob?.roundToInt()?.takeIf { it > 0 }

private fun <T> List<T>.nearest(timestamp: Long, time: (T) -> Long): T? =
    minByOrNull { abs(time(it) - timestamp) }?.takeIf { abs(time(it) - timestamp) <= INSPECT_MATCH_MS }

/** BG range to the BG color of the graph. */
@Composable
internal fun BgRange.inspectColor(): Color = when (this) {
    BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
    BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
    BgRange.LOW      -> AapsTheme.generalColors.bgLow
}

/**
 * The pop-up with the values at the inspected time, one line per graph. The first line is the BG
 * graph: time, glucose, its extra traces, and any bolus, SMB or carbs at that reading. A graph with
 * nothing at that time gets no line.
 */
@Composable
internal fun GraphInspectPopup(
    data: InspectData,
    rows: List<List<InspectValue>>,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    val profileUtil = LocalProfileUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val colors = rememberSeriesColors()
    Surface(
        shape = RoundedCornerShape(AapsSpacing.large),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small)
        ) {
            PopupLine {
                PillText(dateUtil.timeString(data.timestamp), MaterialTheme.colorScheme.onSurfaceVariant)
                data.bg?.let { bg ->
                    val text = if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(bg.value) else decimalFormatter.to1Decimal(bg.value)
                    PillText(text, bg.range.inspectColor())
                }
                rows.firstOrNull()?.forEach { InspectValueItem(it, colors) }
                // Treatments use the triangles of the IOB and COB graphs: SMB points up, bolus and carbs point down
                data.boluses.forEach { bolus ->
                    PillTriangleItem(pointsUp = bolus.bolusType == BolusType.SMB, text = bolus.label, color = ElementType.INSULIN.color())
                }
                data.carbs.forEach { carbs -> PillTriangleItem(pointsUp = false, text = carbs.label, color = ElementType.CARBS.color()) }
            }
            rows.drop(1).filter { it.isNotEmpty() }.forEach { values ->
                PopupLine { values.forEach { InspectValueItem(it, colors) } }
            }
        }
    }
}

/**
 * The pop-up for the range between two fingers, one line per graph like [GraphInspectPopup]: the
 * times, the BG change, the insulin and carbs given, then per trace its sum or change.
 */
@Composable
internal fun GraphInspectRangePopup(
    range: InspectRange,
    rows: List<List<InspectRangeValue>>,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    val profileUtil = LocalProfileUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val colors = rememberSeriesColors()
    Surface(
        shape = RoundedCornerShape(AapsSpacing.large),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small)
        ) {
            PopupLine {
                PillText(
                    stringResource(UiStrings.graph_inspect_time_range, dateUtil.timeString(range.from), dateUtil.timeString(range.to)),
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
                val from = range.bgFrom
                val to = range.bgTo
                if (from != null && to != null) {
                    val change = to.value - from.value
                    val text = if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(change) else decimalFormatter.to1Decimal(change)
                    PillText(signedText(change, text), to.range.inspectColor())
                }
                rows.firstOrNull()?.forEach { InspectRangeValueItem(it, colors) }
                if (range.insulin > 0)
                    PillItem(
                        ElementType.INSULIN.icon(),
                        stringResource(UiStrings.graph_inspect_sum, stringResource(InterfacesStrings.format_insulin_units, range.insulin)),
                        ElementType.INSULIN.color()
                    )
                if (range.carbs > 0)
                    PillItem(
                        ElementType.CARBS.icon(),
                        stringResource(UiStrings.graph_inspect_sum, stringResource(InterfacesStrings.format_carbs, range.carbs.roundToInt())),
                        ElementType.CARBS.color()
                    )
            }
            rows.drop(1).filter { it.isNotEmpty() }.forEach { values ->
                PopupLine { values.forEach { InspectRangeValueItem(it, colors) } }
            }
        }
    }
}

@Composable
private fun PopupLine(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)
    ) { content() }
}

/** One value: the series icon, the trace name where the icon alone does not say it, and the value with its unit. */
@Composable
private fun InspectValueItem(item: InspectValue, colors: SeriesColors) {
    TraceValueItem(item.trace, inspectValueText(item.trace, item.value), colors)
}

/** One value of a range: "Σ 12 g" for a sum, "+1.2" or "-0.4" for a change. */
@Composable
private fun InspectRangeValueItem(item: InspectRangeValue, colors: SeriesColors) {
    val trace = item.trace
    val text = if (item.isSum) {
        // Basal adds up to units of insulin, not a rate
        val amount = if (trace.type == SeriesType.BASAL && !trace.isDerived) stringResource(InterfacesStrings.format_insulin_units, item.value)
        else inspectValueText(trace, item.value)
        stringResource(UiStrings.graph_inspect_sum, amount)
    } else signedText(item.value, inspectValueText(trace, item.value))
    TraceValueItem(trace, text, colors)
}

/** [text] of [value] with a plus in front when it went up; a fall already has its minus. */
@Composable
private fun signedText(value: Double, text: String): String =
    if (value > 0) stringResource(UiStrings.graph_inspect_rise, text) else text

@Composable
private fun TraceValueItem(trace: GraphTrace, value: String, colors: SeriesColors) {
    val color = lookOf(trace, colors).color
    val icon = inspectIcon(trace.type)
    val text = if (trace.isDerived || icon == null || trace.type in NAMED_IN_POPUP) stringResource(InterfacesStrings.confirmation_line, traceLabel(trace), value) else value
    if (icon != null) {
        // The carbs icon fills more of its box than the others, so it is drawn smaller to look the same size
        PillItem(icon, text, color, iconSize = if (trace.type == SeriesType.COB) PILL_COB_ICON_SIZE else PILL_ICON_SIZE)
    } else PillText(text, color)
}

/** Series that share their icon with another series, so the pop-up also names them. */
private val NAMED_IN_POPUP = setOf(SeriesType.ABS_IOB, SeriesType.VAR_SENSITIVITY, SeriesType.CARBS_ABSORBED)

/** Icon of a series in the pop-up; null for series that are named instead (BGI, DEV, activity). */
@Composable
private fun inspectIcon(type: SeriesType): ImageVector? = when (type) {
    SeriesType.IOB, SeriesType.ABS_IOB                 -> ElementType.INSULIN.icon()
    SeriesType.COB, SeriesType.CARBS_ABSORBED          -> ElementType.COB.icon()
    SeriesType.BASAL                                   -> ElementType.TEMP_BASAL.icon()
    SeriesType.SENSITIVITY, SeriesType.VAR_SENSITIVITY -> ElementType.SENSITIVITY.icon()
    SeriesType.HEART_RATE                              -> IcHeartRate
    SeriesType.STEPS                                   -> IcSteps
    else                                               -> null
}

/** A trace value with its unit, as the graph's axis would show it. */
@Composable
private fun inspectValueText(trace: GraphTrace, value: Double): String {
    val decimalFormatter = LocalDecimalFormatter.current
    val isMmol = LocalProfileUtil.current.units == GlucoseUnit.MMOL
    if (trace.transform == TraceTransform.ROLLING_SUM) {
        return if (trace.type == SeriesType.STEPS) decimalFormatter.to0Decimal(value) else decimalFormatter.to1Decimal(value)
    }
    return when (trace.type) {
        SeriesType.IOB, SeriesType.ABS_IOB -> stringResource(InterfacesStrings.format_insulin_units, value)
        SeriesType.COB                     -> stringResource(InterfacesStrings.format_carbs, value.roundToInt())
        SeriesType.CARBS_ABSORBED          -> stringResource(UiStrings.graph_inspect_grams, decimalFormatter.to1Decimal(value))
        SeriesType.BASAL                   -> stringResource(CoreUiStrings.pump_base_basal_rate, value)
        SeriesType.SENSITIVITY             -> stringResource(CoreUiStrings.units_format_percent, value.roundToInt())
        SeriesType.HEART_RATE              -> stringResource(UiStrings.graph_inspect_heart_rate, value.roundToInt())
        SeriesType.STEPS                   -> decimalFormatter.to0Decimal(value)
        SeriesType.ACTIVITY                -> decimalFormatter.to3Decimal(value)

        SeriesType.BG, SeriesType.BGI, SeriesType.DEVIATIONS, SeriesType.DEV_SLOPE, SeriesType.VAR_SENSITIVITY, SeriesType.PREDICTIONS ->
            if (isMmol) decimalFormatter.to1Decimal(value) else decimalFormatter.to0Decimal(value)
    }
}

@Composable
private fun PillText(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
}

@Composable
private fun PillItem(icon: ImageVector, text: String, color: Color, iconSize: Dp = PILL_ICON_SIZE) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(PILL_ICON_SIZE)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        }
        PillText(text, color)
    }
}

@Composable
private fun PillTriangleItem(pointsUp: Boolean, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        PillTriangle(pointsUp = pointsUp, color = color)
        PillText(text, color)
    }
}

/**
 * A treatment triangle like in the IOB and COB graphs, with the same proportions as [TriangleShape]
 * and [InvertedTriangleShape]. Those shapes only fill the top half of their box, so the triangle is
 * drawn here, centered in the icon box.
 */
@Composable
private fun PillTriangle(pointsUp: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(PILL_ICON_SIZE)) {
        // Same as the graph shapes: base 0.6 of the width, height 0.5 of the width
        val baseHalf = size.width * 0.3f
        val height = size.width * 0.5f
        val cx = size.width / 2f
        val top = (size.height - height) / 2f
        val bottom = top + height
        val path = Path().apply {
            if (pointsUp) {
                moveTo(cx, top)
                lineTo(cx + baseHalf, bottom)
                lineTo(cx - baseHalf, bottom)
            } else {
                moveTo(cx - baseHalf, top)
                lineTo(cx + baseHalf, top)
                lineTo(cx, bottom)
            }
            close()
        }
        drawPath(path, color)
    }
}
