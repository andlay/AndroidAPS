package app.aaps.ui.compose.overview.graphs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.AxisRange
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.rowAction
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Overview graphs section using Vico charts.
 *
 * Pattern: Observe Primary + Sync to Secondary
 * - Each graph has its OWN VicoScrollState and VicoZoomState
 * - BG graph: Interactive - user can scroll/zoom
 * - Secondary graphs: scroll/zoom disabled on the chart; a sideways drag or a pinch on them is
 *   passed on to the BG graph (see rememberForwardToBgInput)
 * - LaunchedEffect observes BG graph's state changes and syncs to secondary
 *
 * Synchronization Implementation:
 * - snapshotFlow observes scroll/zoom values from BG graph
 * - debounce(30) waits for gesture to settle
 * - zoomState.zoom() and scrollState.scroll() copy state to secondary graphs
 *
 * Secondary graphs are config-driven via [GraphConfig.secondaryGraphs].
 * Scroll/Zoom states are pre-allocated (up to [GraphConfig.MAX_SECONDARY_GRAPHS])
 * to avoid dynamic composable state issues with Vico's remember-based states.
 */
/** Fixed graph layout used in simple mode: BG (no overlays), IOB+BAS (no overlays), COB */
private val SIMPLE_MODE_CONFIG = GraphConfig(
    bgOverlays = emptyList(),
    secondaryGraphs = listOf(GraphConfig.DEFAULT_IOB_GRAPH.copy(activityOverlay = false), SecondaryGraph.of(listOf(SeriesType.COB)))
)

/**
 * How many recent readings the spoken description of the BG graph lists.
 *
 * Five is about twenty-five minutes, which is enough to hear where the glucose is heading without
 * turning the description into a list nobody will sit through.
 */
private const val RECENT_VALUES_SPOKEN = 5

/** Series not made by the graph calculation (sensor and device data, UI flags): no busy ring for these. */
private val NOT_CALCULATED_SERIES = setOf(SeriesType.HEART_RATE, SeriesType.STEPS, SeriesType.BASAL, SeriesType.PREDICTIONS)

/**
 * Moves the BG graph pop-up up over the treatment belt, so it covers less of the BG graph. The belt
 * is 48 dp high and the BG graph already starts 16 dp under it.
 */
private val INSPECT_PILL_OFFSET = (-32).dp

/** Series types available as BG graph overlays */
private val BG_OVERLAY_SERIES = listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS)

/** Series types available for user-configurable secondary graphs (the UI-only predictions overlay excluded) */
private val CONFIGURABLE_SERIES = SeriesType.entries.filter { it != SeriesType.PREDICTIONS }

@OptIn(FlowPreview::class)
@Composable
fun GraphsSection(
    graphViewModel: GraphViewModel,
    isSimpleMode: Boolean,
    modifier: Modifier = Modifier,
    fitWholeWindow: Boolean = false,
    /**
     * Insulin and carbs on board, already formatted, for the spoken description of the BG graph.
     * The overview passes them; the history browser must NOT, because these are CURRENT values and
     * announcing them beside a past day would state something untrue about insulin.
     */
    iobText: String? = null,
    cobText: String? = null
) {
    val dateUtil = LocalDateUtil.current
    val savedGraphConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    // In simple mode: fixed layout (BG, IOB+BAS, COB — no overlays, no editing)
    val graphConfig = if (isSimpleMode) SIMPLE_MODE_CONFIG else savedGraphConfig

    // Zoom.x(...) allocates a fresh (non-equal, unmemoized) lambda instance on every call.
    // rememberVicoZoomState's underlying rememberSaveable is keyed on initialZoom/minZoom/maxZoom
    // by reference, so passing a freshly-allocated Zoom on every recomposition of this composable
    // tears down and recreates the VicoZoomState — resetting the zoom level back to initialZoom.
    // Memoize these once so all zoom states below stay stable across recompositions.
    val defaultZoom = remember { Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES) }
    val bgMinZoom = remember { Zoom.x(Constants.GRAPH_TIME_RANGE_HOURS * 60.0) }
    val bgMaxZoom = remember { Zoom.x(MIN_GRAPH_ZOOM_MINUTES) }

    // Where the graphs start. The overview is a live view, so the last six hours anchored at the
    // right edge is what the user wants. The history browser shows one chosen day, and its window
    // ends at that day's midnight - the same six hours would be the late evening only, and for
    // today it would be hours that have not happened yet, which reads as an empty graph. There it
    // starts fully zoomed out, which is exactly the day, because the view model spans the axis over
    // the whole window in that mode. Both pick one of the zoom objects above rather than allocating
    // a new one - see the note above on why a fresh Zoom instance tears the state down.
    val startZoom = if (fitWholeWindow) bgMinZoom else defaultZoom

    // BG graph - primary interactive.
    // Wrapped in key(bgViewportResetTrigger) so bumping the trigger tears down and recreates both
    // states from scratch, snapping them back to their initial values (Scroll.Absolute.End /
    // startZoom). This is deliberate: VicoZoomState.zoom(Zoom) is pinch-gesture-oriented
    // (it applies a ratio anchored on the current canvas center via an async pendingScroll flow)
    // and produced a wrong end state when used to "reset to a fixed default" — recreating the
    // state objects is simpler and matches the exact positioning Vico itself already uses on first
    // composition. Only reset on real inactivity (rare, never mid-gesture), so this doesn't
    // reintroduce the "churn during an active gesture" issues found elsewhere in this file.
    // IMPORTANT: every effect below that reads bgScrollState/bgZoomState.value MUST be keyed on
    // them (not just Unit) — otherwise it keeps a stale reference to the abandoned pre-reset
    // objects forever, comparing live secondary-graph state against a frozen stale value and
    // firing wrong corrections (this caused the secondary graphs to visibly "dance"/flash on the
    // first attempt at this).
    var bgViewportResetTrigger by remember { mutableIntStateOf(0) }
    val (bgScrollState, bgZoomState) = key(bgViewportResetTrigger) {
        rememberVicoScrollState(
            scrollEnabled = true,
            initialScroll = Scroll.Absolute.End
        ) to rememberVicoZoomState(
            zoomEnabled = true,
            initialZoom = startZoom,
            minZoom = bgMinZoom,
            maxZoom = bgMaxZoom
        )
    }

    // Pre-allocate secondary graph scroll/zoom states (up to MAX_SECONDARY_GRAPHS)
    // These are always created to keep Compose's remember slots stable
    val sec0scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec0zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)
    val sec1scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec1zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)
    val sec2scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec2zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)
    val sec3scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec3zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)
    val sec4scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec4zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)
    val sec5scroll = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val sec5zoom = rememberVicoZoomState(zoomEnabled = false, initialZoom = startZoom)

    // Collect nowTimestamp ONCE so all graphs use the same value (avoids separate recompositions every 30s)
    val nowTimestamp by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()

    // Collect time range ONCE so all graphs use the exact same values in the same frame.
    // Without this, each graph independently collects derivedTimeRange via
    // collectAsStateWithLifecycle(), which can recompose in different frames —
    // causing minTimestamp divergence and scroll misalignment (pixel position
    // maps to different time when x-axis ranges differ).
    val derivedTimeRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()
    val calculationProgress by graphViewModel.calculationProgress.collectAsStateWithLifecycle()
    val calculationRunning = calculationProgress < 100
    // The busy ring sits left of the edit button, or in the corner when there is none (simple mode)
    val busyRingEnd = if (isSimpleMode) 8.dp else 36.dp


    // One x layout for all graphs, reported by the treatment belt, which is always there. A pinch on
    // another graph uses it to zoom BG around the fingers.
    val graphGeometry = remember { GraphGeometryHolder() }

    // BG's own visible window, sourced from the treatment belt's visible range (its scroll/zoom are
    // synced to BG's, see the sync LaunchedEffect below) — not from a decoration on BG's own chart,
    // since that was tried and found to break BG's pinch-zoom gesture handling (BG is the only graph
    // with live scrollEnabled/zoomEnabled = true). The belt and not a graph below, because every
    // graph below can be removed.
    var beltVisibleRange by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val onBeltVisibleRangeChanged: (Pair<Double, Double>?) -> Unit = remember { { beltVisibleRange = it } }

    // Settle further before feeding into BG specifically: unlike the secondary graphs (which are
    // non-interactive and unaffected by frequent updates), BG has live pinch-zoom. Updating its
    // axis range on every ~80ms tick during an active gesture kept changing the Y-axis label
    // width/layerDimensions mid-gesture and broke pinch-zoom. Only update once the window has
    // been stable for a bit, i.e. after the gesture ends.
    var beltVisibleRangeSettled by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { beltVisibleRange }
            .debounce(400)
            .collect { beltVisibleRangeSettled = it }
    }

    val bgVisibleTimeRange = derivedTimeRange?.first?.let { minTs ->
        beltVisibleRangeSettled?.let { (minXv, maxXv) ->
            (minTs + (minXv * 60_000).toLong()) to (minTs + (maxXv * 60_000).toLong())
        }
    }

    // Treatment belt graph - non-interactive, synced from BG
    val beltScrollState = rememberVicoScrollState(
        scrollEnabled = false,
        initialScroll = Scroll.Absolute.End
    )
    val beltZoomState = rememberVicoZoomState(
        zoomEnabled = false,
        initialZoom = startZoom
    )

    // Active graph count — rememberUpdatedState so coroutines always read the latest value
    // without writing to state during composition. Unattached states are no-ops for
    // .zoom()/.scroll(), but we skip them to avoid redundant calls.
    val activeCount by rememberUpdatedState(
        graphConfig.secondaryGraphs.size.coerceAtMost(GraphConfig.MAX_SECONDARY_GRAPHS)
    )

    // All secondary scroll/zoom states in arrays for indexed access (keyed to rebuild if state identity changes)
    val secScrollStates = remember(sec0scroll, sec1scroll, sec2scroll, sec3scroll, sec4scroll, sec5scroll) {
        arrayOf(sec0scroll, sec1scroll, sec2scroll, sec3scroll, sec4scroll, sec5scroll)
    }
    val secZoomStates = remember(sec0zoom, sec1zoom, sec2zoom, sec3zoom, sec4zoom, sec5zoom) {
        arrayOf(sec0zoom, sec1zoom, sec2zoom, sec3zoom, sec4zoom, sec5zoom)
    }

    // Observe BG graph scroll/zoom and sync to belt + active secondary graphs
    // Keys include ALL state objects — identical pattern to the original working sync
    LaunchedEffect(
        bgScrollState, bgZoomState, beltScrollState, beltZoomState,
        sec0scroll, sec0zoom, sec1scroll, sec1zoom,
        sec2scroll, sec2zoom, sec3scroll, sec3zoom,
        sec4scroll, sec4zoom, sec5scroll, sec5zoom
    ) {
        var lastZoom = Float.NaN
        // Every change, every frame: a debounce here only fires when the finger pauses, so the other
        // graphs stood still during a pan and jumped when it stopped. Zoom is only applied when it
        // changed, and only then does scroll wait for it to settle.
        // Do not report a user interaction from here: the scroll/zoom values also change without the user
        // (viewport reset on a new BG, the prediction nudge, Vico's initial scroll to the end). Real gestures
        // are reported by onScrollOrZoomGesture on the BG chart, and by the pan and pinch the other graphs
        // pass on to BG.
        snapshotFlow { bgScrollState.value to bgZoomState.value }
            .collect { (scroll, zoom) ->
                val count = activeCount
                // Sync zoom first, then scroll (order matters for proper positioning)
                if (zoom != lastZoom) {
                    lastZoom = zoom
                    beltZoomState.zoom(Zoom.fixed(zoom))
                    for (i in 0 until count) secZoomStates[i].zoom(Zoom.fixed(zoom))
                    delay(10)
                }
                beltScrollState.scroll(Scroll.Absolute.pixels(scroll))
                for (i in 0 until count) secScrollStates[i].scroll(Scroll.Absolute.pixels(scroll))
            }
    }

    // Auto-scroll when new BG value arrives
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    var lastBgTimestamp by remember { mutableLongStateOf(0L) }

    LaunchedEffect(bgInfoState.bgInfo?.timestamp) {
        val newTimestamp = bgInfoState.bgInfo?.timestamp ?: return@LaunchedEffect
        if (lastBgTimestamp != 0L && newTimestamp > lastBgTimestamp) {
            // Skip auto-reset while user is interacting with the graph
            val sinceInteraction = dateUtil.now() - graphViewModel.lastInteractionMs
            if (sinceInteraction < INTERACTION_GRACE_MS) {
                lastBgTimestamp = newTimestamp
                return@LaunchedEffect
            }
            // Reset scroll+zoom to their defaults by recreating the state objects (see
            // bgViewportResetTrigger doc above) instead of driving VicoZoomState.zoom(Zoom) to an
            // absolute target, which was found to land on the wrong zoom/scroll combination.
            bgViewportResetTrigger++
        }
        lastBgTimestamp = newTimestamp
    }

    // Where the graphs open. The axis can reach past the usual right edge, on to the end of the insulin
    // tail (now + DIA), so Scroll.Absolute.End alone would open on hours of future. Instead put the
    // usual right edge (homeTimeRange) at the right of the screen: first composition and after every
    // reset (the recreated bgScrollState defaults to Scroll.Absolute.End). After a reset with
    // predictions visible, "now + 2h" sits at the right edge instead, leaving room to see the forecast.
    // The tail is then a swipe to the left away.
    val homeTimeRange by graphViewModel.homeTimeRange.collectAsStateWithLifecycle()
    LaunchedEffect(bgViewportResetTrigger, bgScrollState) {
        val (minTimestamp, homeEnd) = snapshotFlow { derivedTimeRange?.first to homeTimeRange?.second }
            .first { it.first != null && it.second != null }
        // Wait until the chart has been measured, or the scroll is clamped to a range of nothing
        snapshotFlow { bgScrollState.maxValue }.first { it > 0f }
        val showPredictions = SeriesType.PREDICTIONS in graphConfig.bgOverlays
        if (bgViewportResetTrigger != 0 && showPredictions && predictions.isNotEmpty()) {
            val nowX = timestampToX(dateUtil.now(), minTimestamp!!)
            bgScrollState.animateScroll(Scroll.Absolute.x(nowX + 120.0, bias = 1f))
        } else {
            bgScrollState.scroll(Scroll.Absolute.x(timestampToX(homeEnd!!, minTimestamp!!), bias = 1f))
        }
    }

    // Correct secondary graph scroll drift — Vico may internally adjust scroll
    // when model producers fire. Watch for any divergence and re-sync to BG.
    // No isSyncing guard needed: primary sync only reads BG state, so writing to
    // secondary states here cannot trigger primary sync (no feedback loop).
    // Keyed on bgScrollState/bgZoomState (not Unit) — MUST restart when they're recreated by a
    // reset, otherwise this keeps comparing secondary graphs against a stale, abandoned pre-reset
    // reference forever and fires wrong corrections (the actual cause of the "dancing" regression
    // in the first attempt at this feature).
    LaunchedEffect(bgScrollState, bgZoomState) {
        snapshotFlow {
            // Only read states that are attached to a chart (belt + active graphs below BG)
            val count = activeCount
            buildList {
                add(beltScrollState.value to beltZoomState.value)
                for (i in 0 until count) {
                    add(secScrollStates[i].value to secZoomStates[i].value)
                }
            }
        }
            .debounce(100) // Let Vico settle after model update
            .collect { states ->
                val bgScroll = bgScrollState.value
                val bgZoom = bgZoomState.value
                val threshold = 1f
                val needsSync = states.any { (scroll, zoom) ->
                    abs(scroll - bgScroll) > threshold || abs(zoom - bgZoom) > 0.001f
                }
                if (needsSync) {
                    val count = activeCount
                    beltZoomState.zoom(Zoom.fixed(bgZoom))
                    for (i in 0 until count) secZoomStates[i].zoom(Zoom.fixed(bgZoom))
                    delay(10)
                    beltScrollState.scroll(Scroll.Absolute.pixels(bgScroll))
                    for (i in 0 until count) secScrollStates[i].scroll(Scroll.Absolute.pixels(bgScroll))
                }
            }
    }

    // Shared by all graphs: the BG graph sets it on press and hold, every graph draws the line from it
    val inspect = remember { GraphInspectState() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Treatment Belt Graph - running mode background + therapy events
        TreatmentBeltGraphCompose(
            viewModel = graphViewModel,
            inspect = inspect,
            onVisibleRangeChanged = onBeltVisibleRangeChanged,
            geometryHolder = graphGeometry,
            scrollState = beltScrollState,
            zoomState = beltZoomState,
            derivedTimeRange = derivedTimeRange,
            nowTimestamp = nowTimestamp,
            modifier = Modifier.fillMaxWidth()
        )
        // BG Graph - primary interactive graph
        var editingBgOverlays by remember { mutableStateOf(false) }

        // The graph is drawn, so a screen reader gets nothing from it at all - a chart contributes
        // no semantics node of its own. Describe it instead: the recent readings newest first,
        // because the current value is what matters most and a listener may stop there, plus what
        // is on board. The description goes on the graph itself rather than the Box, so the edit
        // button beside it stays a separate control.
        val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
        val profileUtil = LocalProfileUtil.current
        val decimalFormatter = LocalDecimalFormatter.current
        val recentValues = remember(bgReadings, profileUtil.units) {
            // BgDataPoint.value is ALREADY in the user's units, so it must not be converted again -
            // only formatted. The unit decides the decimals; the "detect" helpers guess that from
            // the magnitude and would read a very high mmol/l reading as mg/dl.
            newestReadings(bgReadings, RECENT_VALUES_SPOKEN)
                .joinToString(", ") {
                    if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(it.value)
                    else decimalFormatter.to1Decimal(it.value)
                }
        }
        val graphDescription = when {
            recentValues.isEmpty()                 -> null
            iobText != null && cobText != null     -> stringResource(
                UiStrings.a11y_bg_graph_summary,
                recentValues,
                stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.iob), iobText),
                stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.cob), cobText)
            )

            else                                   -> stringResource(UiStrings.a11y_bg_graph_summary_values_only, recentValues)
        }

        Box(
            modifier = Modifier
                .offset(y = (-16).dp)
                .graphInspectTouch(inspect)
        ) {
            BgGraphCompose(
                viewModel = graphViewModel,
                bgOverlays = graphConfig.bgOverlays,
                scrollState = bgScrollState,
                zoomState = bgZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleTimeRange = bgVisibleTimeRange,
                inspect = inspect,
                traces = graphConfig.bgTraces,
                leftRange = graphConfig.bgLeftRange,
                rightRange = graphConfig.bgRightRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.bgHeight.dp)
                    .onScrollOrZoomGesture(graphViewModel::onGraphInteraction)
                    .then(
                        remember(graphDescription) {
                            if (graphDescription != null) Modifier.semantics { contentDescription = graphDescription }
                            else Modifier
                        }
                    )
            )
            if (!isSimpleMode) {
                GraphEditButton(
                    onClick = { editingBgOverlays = true },
                    graphName = stringResource(CoreUiStrings.glucose),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp)
                )
            }
            // BG readings themselves are not recalculated; activity and predictions are
            GraphBusyRing(
                busy = calculationRunning && (SeriesType.ACTIVITY in graphConfig.bgOverlays || SeriesType.PREDICTIONS in graphConfig.bgOverlays),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = busyRingEnd, top = 10.dp)
            )
            // Press and hold: the pill, in its own composable so a finger move does not recompose this section
            GraphInspectOverlay(
                inspect = inspect,
                graphViewModel = graphViewModel,
                bgReadings = bgReadings,
                minTimestamp = derivedTimeRange?.first,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = INSPECT_PILL_OFFSET)
                    .padding(top = 2.dp, start = 36.dp, end = 36.dp)
            )
        }
        if (editingBgOverlays) {
            BgGraphSheet(
                graphConfig = graphConfig,
                isMmol = profileUtil.units == GlucoseUnit.MMOL,
                toShown = { profileUtil.fromMgdlToUnits(it) },
                fromShown = { profileUtil.convertToMgdl(it, profileUtil.units) },
                onConfigChange = { graphViewModel.updateGraphConfig(it(graphViewModel.graphConfigFlow.value)) },
                onDismiss = { editingBgOverlays = false }
            )
        }

        // The graphs under BG — config-driven (labels start at "Graph 2"). The first one is IOB with
        // basal unless the user changed it.
        var editingGraphIndex by remember { mutableIntStateOf(-1) }
        for (i in 0 until activeCount) {
            val secondary = graphConfig.secondaryGraphs[i]
            Box(modifier = Modifier.offset(y = (-8).dp)) {
                SecondaryGraphCompose(
                    viewModel = graphViewModel,
                    traces = secondary.traces,
                    inspect = inspect,
                    scrollState = secScrollStates[i],
                    zoomState = secZoomStates[i],
                    derivedTimeRange = derivedTimeRange,
                    nowTimestamp = nowTimestamp,
                    activityOverlay = secondary.activityOverlay,
                    leftRange = secondary.leftRange,
                    rightRange = secondary.rightRange,
                    geometryHolder = graphGeometry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(secondary.height.dp)
                        .then(rememberForwardToBgInput(graphGeometry, bgScrollState, bgZoomState, graphViewModel::onGraphInteraction))
                )
                Text(
                    text = traceListLabel(secondary.traces),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 36.dp, top = 2.dp)
                )
                if (!isSimpleMode) {
                    GraphEditButton(
                        onClick = { editingGraphIndex = i },
                        graphName = traceListLabel(secondary.traces),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp, top = 2.dp)
                    )
                }
                GraphBusyRing(
                    busy = calculationRunning && secondary.series.any { it !in NOT_CALCULATED_SERIES },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = busyRingEnd, top = 10.dp)
                )
            }
        }
        if (editingGraphIndex >= 0 && editingGraphIndex < activeCount) {
            // Read once. The lambdas below can run after the sheet has closed and the index is
            // already -1: NumberInputRow saves a focused field when it leaves the screen.
            val index = editingGraphIndex
            val editing = graphConfig.secondaryGraphs[index]
            GraphTracesBottomSheet(
                title = stringResource(CoreUiStrings.graph_number, index + 2),
                traces = editing.traces,
                availableSeries = CONFIGURABLE_SERIES,
                height = editing.height,
                onHeightChange = { h ->
                    // The current config, not the captured one: after "Remove graph" the captured
                    // config still holds the removed graph, and writing it back would restore it.
                    withSecondaryGraphHeight(graphViewModel.graphConfigFlow.value, index, editing.series, h)
                        ?.let { graphViewModel.updateGraphConfig(it) }
                },
                onTracesChange = { traces ->
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    if (traces.isEmpty()) {
                        // Auto-remove graph when all traces are removed
                        graphs.removeAt(index)
                        editingGraphIndex = -1
                    } else {
                        graphs[index] = graphs[index].withTraces(traces)
                    }
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                extraContent = activityOverlaySwitch(editing) { on ->
                    updateSecondaryGraph(graphViewModel, index, editing.traces) { it.copy(activityOverlay = on) }
                },
                axes = graphAxisEdits(editing, profileUtil.units == GlucoseUnit.MMOL) { change ->
                    updateSecondaryGraph(graphViewModel, index, editing.traces, change)
                },
                onRemoveGraph = {
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    graphs.removeAt(index)
                    editingGraphIndex = -1
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onDismiss = { editingGraphIndex = -1 }
            )
        }
        // Add graph button (hidden in simple mode)
        if (!isSimpleMode && activeCount < GraphConfig.MAX_SECONDARY_GRAPHS) {
            var showAddSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAddSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(CoreUiStrings.graph_add), style = MaterialTheme.typography.labelMedium)
            }
            if (showAddSheet) {
                var newGraphTraces by remember { mutableStateOf(emptyList<GraphTrace>()) }
                var newGraphHeight by remember { mutableIntStateOf(GraphConfig.DEFAULT_GRAPH_HEIGHT_DP) }
                GraphTracesBottomSheet(
                    title = stringResource(CoreUiStrings.graph_new),
                    traces = newGraphTraces,
                    availableSeries = CONFIGURABLE_SERIES,
                    height = newGraphHeight,
                    onHeightChange = { newGraphHeight = it },
                    onTracesChange = { newGraphTraces = it },
                    onDismiss = {
                        if (newGraphTraces.isNotEmpty()) {
                            val graphs = graphConfig.secondaryGraphs.toMutableList()
                            graphs.add(SecondaryGraph(newGraphTraces, newGraphHeight))
                            graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                        }
                        newGraphTraces = emptyList()
                        newGraphHeight = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
                        showAddSheet = false
                    }
                )
            }
        }
        // Spacer so the last graph / Add button isn't covered by QuickLaunch toolbar
        Spacer(Modifier.height(48.dp))
    }
}

/**
 * Settings sheet of the BG graph: height, the predictions and activity switches, more traces (BG
 * itself stays, locked), and the range of the BG axis and of the right axis.
 *
 * @param toShown mg/dL to the user's units, for the BG axis range
 * @param fromShown the user's units to mg/dL
 * @param onConfigChange gets a change to apply to the current config, so a field that saves after
 *   the sheet closed never writes back an old config
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BgGraphSheet(
    graphConfig: GraphConfig,
    isMmol: Boolean,
    toShown: (Double) -> Double,
    fromShown: (Double) -> Double,
    onConfigChange: ((GraphConfig) -> GraphConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val traces = remember(graphConfig.bgTraces) { listOf(GraphTrace(SeriesType.BG)) + graphConfig.bgTraces }
    val right = axisGroups(traces).second
    val axes = buildList {
        add(AxisEdit(UiStrings.graph_axis_left, graphConfig.bgLeftRange, bgAxisUnit(isMmol, toShown, fromShown), { r -> onConfigChange { it.copy(bgLeftRange = r) } }))
        right.firstOrNull()?.let { first ->
            add(AxisEdit(UiStrings.graph_axis_right, graphConfig.bgRightRange, axisUnitFor(first, isMmol), { r -> onConfigChange { it.copy(bgRightRange = r) } }))
        }
    }
    GraphTracesBottomSheet(
        title = stringResource(CoreUiStrings.graph_bg),
        traces = traces,
        availableSeries = CONFIGURABLE_SERIES,
        height = graphConfig.bgHeight,
        onHeightChange = { h -> onConfigChange { it.copy(bgHeight = h) } },
        onTracesChange = { edited ->
            val newTraces = bgTracesAfterEdit(edited)
            onConfigChange { config ->
                val rightChanged = rightAxisKey(listOf(GraphTrace(SeriesType.BG)) + config.bgTraces) != rightAxisKey(listOf(GraphTrace(SeriesType.BG)) + newTraces)
                config.copy(bgTraces = newTraces, bgRightRange = if (rightChanged) AxisRange.AUTO else config.bgRightRange)
            }
        },
        onDismiss = onDismiss,
        lockedType = SeriesType.BG,
        extraContent = {
            Text(stringResource(UiStrings.graph_bg_overlays), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (type in BG_OVERLAY_SERIES) {
                    SheetChip(
                        selected = type in graphConfig.bgOverlays,
                        label = stringResource(seriesShortNameId(type)),
                        onClick = {
                            onConfigChange { config ->
                                val current = config.bgOverlays.toMutableList()
                                if (type in current) current.remove(type) else current.add(type)
                                config.copy(bgOverlays = current)
                            }
                        }
                    )
                }
            }
        },
        axes = axes
    )
}

/** The "activity over IOB" switch, for a graph that starts with IOB; null for any other graph. */
private fun activityOverlaySwitch(graph: SecondaryGraph, onChange: (Boolean) -> Unit): (@Composable () -> Unit)? {
    val first = axisGroups(graph.traces).first.firstOrNull() ?: return null
    if (first.type != SeriesType.IOB || first.isDerived) return null
    return {
        SheetChip(
            selected = graph.activityOverlay,
            label = stringResource(UiStrings.graph_activity_overlay),
            onClick = { onChange(!graph.activityOverlay) }
        )
    }
}

/** The axes of [graph] whose range the sheet can set: the left one, and the right one when there is one. */
private fun graphAxisEdits(graph: SecondaryGraph, isMmol: Boolean, onChange: ((SecondaryGraph) -> SecondaryGraph) -> Unit): List<AxisEdit> {
    val (left, right) = axisGroups(graph.traces)
    val first = left.firstOrNull() ?: return emptyList()
    return buildList {
        add(AxisEdit(UiStrings.graph_axis_left, graph.leftRange, axisUnitFor(first, isMmol), { r -> onChange { it.copy(leftRange = r) } }))
        when (rightAxisKey(graph.traces)) {
            null            -> Unit
            BASAL_AXIS_KEY  -> add(AxisEdit(UiStrings.graph_axis_basal, graph.rightRange, BASAL_AXIS_UNIT, { r -> onChange { it.copy(rightRange = r) } }, maxOnly = true))
            else            -> add(AxisEdit(UiStrings.graph_axis_right, graph.rightRange, axisUnitFor(right.first(), isMmol), { r -> onChange { it.copy(rightRange = r) } }))
        }
    }
}

/**
 * Applies [change] to graph [index] of the current config, if it still shows [expectedTraces]. Fields
 * can save after the sheet closed or the graph was removed (see [withSecondaryGraphHeight]).
 */
private fun updateSecondaryGraph(viewModel: GraphViewModel, index: Int, expectedTraces: List<GraphTrace>, change: (SecondaryGraph) -> SecondaryGraph) {
    val config = viewModel.graphConfigFlow.value
    val graph = config.secondaryGraphs.getOrNull(index) ?: return
    if (graph.traces != expectedTraces) return
    viewModel.updateGraphConfig(config.copy(secondaryGraphs = config.secondaryGraphs.toMutableList().also { it[index] = change(graph) }))
}

/**
 * The [count] newest readings, newest first.
 *
 * Sorted here rather than trusted: the graph data comes newest first, and reading it as oldest
 * first made the screen reader speak the readings from 24 hours ago instead of the current ones.
 */
internal fun newestReadings(readings: List<BgDataPoint>, count: Int): List<BgDataPoint> =
    readings.sortedByDescending { it.timestamp }.take(count)

/**
 * [config] with the height of secondary graph [index] set to [height], or null when that graph no
 * longer shows [expectedSeries].
 *
 * The height field saves its text when the settings sheet closes, so this can run after the graph
 * was removed. Then [index] points to another graph, or to nothing, and nothing may be written.
 * Graphs have no id, so the graph is recognised by its series. The height is not compared: quick
 * taps on the +/- buttons can arrive before the screen has caught up with the previous one.
 * With two graphs showing the same series, the other one could get the height. That is harmless.
 */
internal fun withSecondaryGraphHeight(config: GraphConfig, index: Int, expectedSeries: List<SeriesType>, height: Int): GraphConfig? {
    val graph = config.secondaryGraphs.getOrNull(index) ?: return null
    if (graph.series != expectedSeries) return null
    val graphs = config.secondaryGraphs.toMutableList()
    graphs[index] = graph.copy(height = height)
    return config.copy(secondaryGraphs = graphs)
}

// =========================================================================
// Graph label generation
// =========================================================================

/** Generate a short label from the traces in a graph (e.g., "COB", "BGI / DEV", "HR / HR EWMA 30m") */
@Composable
private fun traceListLabel(traces: List<GraphTrace>): String {
    val names = traces.map { traceLabel(it) }
    return names.joinToString(" / ")
}

/** String resource ID for the short name of a series type */
internal fun seriesShortNameId(type: SeriesType): TextRef = when (type) {
    SeriesType.BG              -> CoreUiStrings.bg_label
    SeriesType.IOB             -> CoreUiStrings.iob
    SeriesType.ABS_IOB         -> CoreUiStrings.abs_insulin_shortname
    SeriesType.COB             -> CoreUiStrings.cob
    SeriesType.BGI             -> CoreUiStrings.bgi_shortname
    SeriesType.DEVIATIONS      -> CoreUiStrings.deviation_shortname
    SeriesType.SENSITIVITY     -> CoreUiStrings.sensitivity_shortname
    SeriesType.VAR_SENSITIVITY -> CoreUiStrings.variable_sensitivity_shortname
    SeriesType.DEV_SLOPE       -> CoreUiStrings.devslope_shortname
    SeriesType.HEART_RATE      -> CoreUiStrings.heartRate_shortname
    SeriesType.STEPS           -> CoreUiStrings.steps_shortname
    SeriesType.ACTIVITY        -> CoreUiStrings.activity_shortname
    SeriesType.BASAL           -> CoreUiStrings.basal_shortname
    SeriesType.PREDICTIONS     -> CoreUiStrings.predictions_shortname
}

// =========================================================================
// Graph edit button + series picker bottom sheet
// =========================================================================

/**
 * Small spinning ring in a graph's top right corner while the data it draws is being recalculated.
 * It goes round until the calculation is done. It only shows after a short wait, so the quick
 * recalculation after each new reading does not flash it on and off.
 */
@Composable
private fun GraphBusyRing(busy: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(BUSY_RING_DELAY_MS)
            shown = true
        } else shown = false
    }
    AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        )
    }
}

private const val BUSY_RING_DELAY_MS = 250L

/** Small pencil icon button overlaid on a graph */
@Composable
private fun GraphEditButton(
    onClick: () -> Unit,
    graphName: String,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(28.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            // One per graph, so it names its graph: "Edit: COB", not "Edit" four times.
            contentDescription = rowAction(CoreUiStrings.switch_to_edit, graphName),
            modifier = Modifier.size(16.dp)
        )
    }
}
