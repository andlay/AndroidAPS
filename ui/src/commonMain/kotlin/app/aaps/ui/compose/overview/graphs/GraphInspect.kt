package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.AutoIsfFactorPoint
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.HeldLoopRun
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.chips.toIcon
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

// Press and hold on any graph: a line in every graph and a pill with the values (a dot on the reading
// when held on the BG graph). The pill also has the values of the extra graphs that are shown.

/** Max distance from the finger's time for a reading or value, in ms. */
private const val INSPECT_MATCH_MS = 5 * 60_000L

/** Max distance from the reading for a bolus or carbs entry (half a reading interval), in ms. */
private const val INSPECT_TREATMENT_MS = 150_000L

/** How long the pill stays after the finger is lifted. */
private const val INSPECT_LINGER_MS = 2_000L

private val PILL_ICON_SIZE = 14.dp

/** The carbs icon fills more of its box than the insulin icon. */
private val PILL_COB_ICON_SIZE = 12.dp

/**
 * Shared between Vico and the compose side. Vico sets [targetX]; the compose side snaps it to a
 * reading and sets [shownX], [dotValue] and [dotColor].
 */
@Stable
class GraphInspectState {

    /** True from the long press until the finger is lifted. */
    var fingerDown by mutableStateOf(false)

    /** Set by the touch layer. Not a state: only read in callbacks. */
    var pointerDown = false

    /** Graph x under the finger, in minutes from the start of the graph. */
    var targetX by mutableStateOf<Double?>(null)

    /** Graph x of the reading that is shown. */
    var shownX by mutableStateOf<Double?>(null)

    /**
     * The series of the graph the finger went down on; the pop-up shows only its values. Empty for
     * the BG graph (and the treatment belt above it). Set by [inspectSource].
     */
    var touchedSeries by mutableStateOf<List<SeriesType>>(emptyList())

    /** Where the line is drawn, or null when the finger is up. */
    val lineX: Double? get() = if (fingerDown) shownX else null
    var dotValue by mutableStateOf<Double?>(null)
    var dotColor by mutableStateOf(Color.Unspecified)

    val controller: CartesianMarkerController = object : CartesianMarkerController {
        override val acceptsLongPress: Boolean = true

        // Only a long press starts it; it ends on lift or zoom
        override fun shouldAcceptInteraction(interaction: Interaction, targets: List<CartesianMarker.Target>): Boolean =
            when (interaction) {
                is Interaction.LongPress                                          -> true
                is Interaction.Move, is Interaction.Release, is Interaction.Zoom -> fingerDown
                else                                                              -> false
            }

        override fun shouldShowMarker(interaction: Interaction, targets: List<CartesianMarker.Target>): Boolean {
            val show = interaction is Interaction.LongPress || interaction is Interaction.Move
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

        // Vico also hides the marker when the finger leaves the plotted area. Keep inspecting while it is down.
        override fun onHidden(marker: CartesianMarker) {
            if (!pointerDown) fingerDown = false
        }
    }
}

/**
 * The dot on the shown reading. Vico puts the marker at its own nearest point (it can be a basal or
 * prediction point), so the dot is moved to the reading.
 */
class GraphInspectMarker(
    private val state: GraphInspectState
) : CartesianMarker {

    private val dotCenter = ShapeComponent(fill = Fill(Color.White), shape = CircleShape)

    override fun drawOverLayers(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>) {
        if (!state.fingerDown) return
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

/** The dashed line at the shown reading, in every graph. Drawn like [NowLine]. */
class InspectLine(
    private val state: GraphInspectState,
    private val color: Color,
    private val strokeWidthPx: Float = 2f,
    private val dashLengthPx: Float = 6f,
    private val gapLengthPx: Float = 4f
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val x = state.lineX ?: return
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return
            val canvasX = layerBounds.left +
                layerDimensions.startPadding +
                layerDimensions.xSpacing * ((x - ranges.minX) / xStep).toFloat() -
                scroll
            if (canvasX < layerBounds.left || canvasX > layerBounds.right) return
            with(mutableDrawScope) {
                drawLine(
                    color = this@InspectLine.color,
                    start = Offset(canvasX, layerBounds.top),
                    end = Offset(canvasX, layerBounds.bottom),
                    strokeWidth = strokeWidthPx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLengthPx, gapLengthPx), 0f)
                )
            }
        }
    }
}

/** The [InspectLine] for one graph, or null when [state] is null. */
@Composable
fun rememberInspectLine(state: GraphInspectState?): InspectLine? {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    return remember(state, color) { state?.let { InspectLine(it, color) } }
}

/**
 * While inspecting, consumes moves so the graph does not scroll. Vico still uses consumed moves
 * for the marker, but not for scrolling.
 * Inspecting ends in the Final pass, after Vico has seen the lift. Otherwise Vico keeps the marker.
 */
fun Modifier.graphInspectTouch(state: GraphInspectState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            state.pointerDown = event.changes.any { it.pressed }
            if (state.fingerDown && event.type == PointerEventType.Move) event.changes.forEach { it.consume() }
            // Same event, after Vico. Also covers a lift that Vico did not get.
            awaitPointerEvent(PointerEventPass.Final)
            if (!state.pointerDown) state.fingerDown = false
        }
    }
}

/**
 * Tells [state] which graph the finger went down on, so the pop-up shows the values of that graph
 * only. [series] is what the graph shows; empty for the BG graph. Does not consume the touch.
 */
fun Modifier.inspectSource(state: GraphInspectState, series: List<SeriesType>): Modifier = pointerInput(state, series) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToDownIgnoreConsumed() }) state.touchedSeries = series
        }
    }
}

/**
 * Snaps the finger to a reading, gives haptic feedback and shows the pill until [INSPECT_LINGER_MS]
 * after the lift. Uses snapshotFlow, not effect keys, so a move only recomposes when the reading changes.
 */
@Composable
fun GraphInspectOverlay(
    inspect: GraphInspectState,
    graphViewModel: GraphViewModel,
    bgReadings: List<BgDataPoint>,
    minTimestamp: Long?,
    modifier: Modifier = Modifier
) {
    var inspectData by remember { mutableStateOf<InspectData?>(null) }
    val iobGraph by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
    val cobGraph by graphViewModel.cobGraphFlow.collectAsStateWithLifecycle()
    val basalGraph by graphViewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val treatmentGraph by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val absIobGraph by graphViewModel.absIobGraphFlow.collectAsStateWithLifecycle()
    val activityGraph by graphViewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val bgiGraph by graphViewModel.bgiGraphFlow.collectAsStateWithLifecycle()
    val deviationsGraph by graphViewModel.deviationsGraphFlow.collectAsStateWithLifecycle()
    val ratioGraph by graphViewModel.ratioGraphFlow.collectAsStateWithLifecycle()
    val devSlopeGraph by graphViewModel.devSlopeGraphFlow.collectAsStateWithLifecycle()
    val varSensGraph by graphViewModel.varSensGraphFlow.collectAsStateWithLifecycle()
    val heartRateGraph by graphViewModel.heartRateGraphFlow.collectAsStateWithLifecycle()
    val stepsGraph by graphViewModel.stepsGraphFlow.collectAsStateWithLifecycle()
    val currentBgReadings by rememberUpdatedState(bgReadings)
    val currentMinTimestamp by rememberUpdatedState(minTimestamp)
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(inspect, haptic) {
        snapshotFlow { inspect.fingerDown to inspect.targetX }.collect { (fingerDown, x) ->
            val min = currentMinTimestamp
            if (!fingerDown || x == null || min == null) return@collect
            val data = inspectDataAt(
                timestamp = min + (x * 60_000).toLong(),
                bgReadings = currentBgReadings,
                iob = iobGraph.iob,
                cob = cobGraph.cob,
                actualBasal = basalGraph.actualBasal,
                profileBasal = basalGraph.profileBasal,
                boluses = treatmentGraph.boluses,
                carbs = treatmentGraph.carbs
            ).let { at ->
                at.copy(
                    extras = inspectExtrasAt(
                        timestamp = at.timestamp,
                        series = inspect.touchedSeries,
                        sources = InspectSources(
                            absIob = absIobGraph.absIob,
                            activity = activityGraph.activity + activityGraph.activityPrediction,
                            bgi = bgiGraph.bgi + bgiGraph.bgiPrediction,
                            deviations = deviationsGraph.deviations.map { GraphDataPoint(it.timestamp, it.value) },
                            devCarbs = deviationsGraph.devCarbs.flatten(),
                            ratio = ratioGraph.ratio,
                            dsMax = devSlopeGraph.dsMax,
                            dsMin = devSlopeGraph.dsMin,
                            varSens = varSensGraph.varSens,
                            autoIsf = varSensGraph.autoIsf,
                            heartRate = heartRateGraph.heartRates,
                            steps = stepsGraph.steps
                        )
                    )
                )
            }
            if (data.timestamp != inspectData?.timestamp) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // The DEV graph and the pill show the loop run at this reading
            graphViewModel.holdAt(data.timestamp)
            inspect.shownX = timestampToX(data.timestamp, min)
            inspect.dotValue = data.bg?.value
            inspectData = data
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
                graphViewModel.holdAt(null)
            }
        }
    }

    val data = inspectData
    val heldRun by graphViewModel.heldLoopRunFlow.collectAsStateWithLifecycle()
    val dotColor = data?.bg?.range?.inspectColor() ?: Color.Unspecified
    SideEffect { inspect.dotColor = dotColor }
    if (data != null) GraphInspectPill(data = data, touchedSeries = inspect.touchedSeries, heldRun = heldRun, modifier = modifier)
}

/** The values at one reading. Null means no data near that time. */
data class InspectData(
    val timestamp: Long,
    val bg: BgDataPoint?,
    val iob: Double?,
    val cob: Double?,
    val basal: Double?,
    val tbrState: TbrState,
    val boluses: List<BolusGraphPoint>,
    val carbs: List<CarbsGraphPoint>,
    /** Values of the extra graphs at this time, in the order of the graphs. */
    val extras: List<InspectExtra> = emptyList()
)

/** A value of an extra graph at the shown time. */
sealed interface InspectExtra {

    /** One value of [type]. For DEVSLOPE there are two: [isMin] marks the falling slope. */
    data class Value(val type: SeriesType, val value: Double, val isMin: Boolean = false) : InspectExtra

    /** The AutoISF factors of the loop run nearest to the shown time. */
    data class AutoIsf(val point: AutoIsfFactorPoint) : InspectExtra
}

/** The data of the extra graphs, as the graphs have it. */
class InspectSources(
    val absIob: List<GraphDataPoint>,
    val activity: List<GraphDataPoint>,
    val bgi: List<GraphDataPoint>,
    val deviations: List<GraphDataPoint>,
    val devCarbs: List<GraphDataPoint>,
    val ratio: List<GraphDataPoint>,
    val dsMax: List<GraphDataPoint>,
    val dsMin: List<GraphDataPoint>,
    val varSens: List<GraphDataPoint>,
    val autoIsf: List<AutoIsfFactorPoint>,
    val heartRate: List<GraphDataPoint>,
    val steps: List<GraphDataPoint>
)

/**
 * The values of [series] at [timestamp], each series once, in the order given. IOB, COB and basal
 * are in the pill already. A series with no value near that time is left out.
 */
internal fun inspectExtrasAt(timestamp: Long, series: List<SeriesType>, sources: InspectSources): List<InspectExtra> = buildList {
    fun value(type: SeriesType, points: List<GraphDataPoint>, isMin: Boolean = false) {
        points.nearest(timestamp) { it.timestamp }?.let { add(InspectExtra.Value(type, it.value, isMin)) }
    }
    for (type in series.distinct()) when (type) {
        SeriesType.ABS_IOB         -> value(type, sources.absIob)
        SeriesType.ACTIVITY        -> value(type, sources.activity)
        SeriesType.BGI             -> value(type, sources.bgi)
        SeriesType.DEVIATIONS      -> value(type, sources.deviations)
        SeriesType.DEV_CARBS       -> value(type, sources.devCarbs)
        // Stored as 100 * (ratio - 1), shown like the graph as a percentage
        SeriesType.SENSITIVITY     -> sources.ratio.nearest(timestamp) { it.timestamp }?.let { add(InspectExtra.Value(type, it.value + 100.0)) }
        SeriesType.DEV_SLOPE       -> {
            value(type, sources.dsMax)
            value(type, sources.dsMin, isMin = true)
        }

        // Stored at each loop run: the run at or before the time
        SeriesType.VAR_SENSITIVITY -> sources.varSens.lastOrNull { it.timestamp <= timestamp + INSPECT_TREATMENT_MS }?.let { add(InspectExtra.Value(type, it.value)) }
        SeriesType.AUTOISF         -> sources.autoIsf.nearest(timestamp) { it.timestamp }?.let { add(InspectExtra.AutoIsf(it)) }
        SeriesType.HEART_RATE      -> value(type, sources.heartRate)
        SeriesType.STEPS           -> value(type, sources.steps)
        SeriesType.IOB, SeriesType.COB, SeriesType.PREDICTIONS -> Unit
    }
}

/** The name of the value of [type] in the pill, as in the graph labels. IOB, COB and basal have their own icons. */
internal fun inspectLabel(type: SeriesType, isMin: Boolean = false): TextRef = when (type) {
    SeriesType.DEV_SLOPE -> if (isMin) CoreUiStrings.devslope_min_shortname else CoreUiStrings.devslope_max_shortname
    else                 -> seriesShortNameId(type)
}

/** The nearest reading to [timestamp], with the values and treatments at its time. */
fun inspectDataAt(
    timestamp: Long,
    bgReadings: List<BgDataPoint>,
    iob: List<GraphDataPoint>,
    cob: List<GraphDataPoint>,
    actualBasal: List<GraphDataPoint>,
    profileBasal: List<GraphDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>
): InspectData {
    val bg = bgReadings.nearest(timestamp) { it.timestamp }
    val time = bg?.timestamp ?: timestamp
    // Basal is stored at each change
    val basal = actualBasal.lastOrNull { it.timestamp <= time }?.value
    return InspectData(
        timestamp = time,
        bg = bg,
        iob = iob.nearest(time) { it.timestamp }?.value,
        cob = cob.nearest(time) { it.timestamp }?.value,
        basal = basal,
        tbrState = tbrState(basal, profileBasal.lastOrNull { it.timestamp <= time }?.value),
        boluses = boluses.filter { it.isValid && abs(it.timestamp - time) <= INSPECT_TREATMENT_MS },
        carbs = carbs.filter { it.isValid && abs(it.timestamp - time) <= INSPECT_TREATMENT_MS }
    )
}

/** Same rule as the TBR chip in the overview. */
internal fun tbrState(basal: Double?, profileBasal: Double?): TbrState = when {
    basal == null || profileBasal == null -> TbrState.NONE
    abs(basal - profileBasal) < 0.01      -> TbrState.NONE
    basal > profileBasal                  -> TbrState.HIGH
    else                                  -> TbrState.LOW
}

/** COB in whole grams, or null at 0 g so it is left out. */
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

/** Time, glucose, IOB, COB, basal rate and treatments at the shown reading. */
@Composable
fun GraphInspectPill(
    data: InspectData,
    /** The series of the touched graph (see [GraphInspectState.touchedSeries]); empty for the BG graph. */
    touchedSeries: List<SeriesType> = emptyList(),
    /** The loop run at the shown reading: what it expected and asked for. Shown on the BG graph. */
    heldRun: HeldLoopRun? = null,
    modifier: Modifier = Modifier
) {
    val onBg = touchedSeries.isEmpty()
    val onIob = SeriesType.IOB in touchedSeries
    val onCob = SeriesType.COB in touchedSeries
    val dateUtil = LocalDateUtil.current
    val profileUtil = LocalProfileUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.large)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
                modifier = Modifier.padding(vertical = AapsSpacing.small)
            ) {
                PillText(dateUtil.timeString(data.timestamp), MaterialTheme.colorScheme.onSurfaceVariant)
                // Only the values of the touched graph
                if (onBg) data.bg?.let { bg ->
                    val text = if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(bg.value) else decimalFormatter.to1Decimal(bg.value)
                    PillText(text, bg.range.inspectColor())
                }
                if (onIob) data.iob?.let {
                    PillItem(ElementType.INSULIN.icon(), stringResource(InterfacesStrings.format_insulin_units, it), ElementType.INSULIN.color())
                }
                if (onCob) shownCob(data.cob)?.let {
                    PillItem(ElementType.COB.icon(), stringResource(InterfacesStrings.format_carbs, it), ElementType.COB.color(), iconSize = PILL_COB_ICON_SIZE)
                }
                if (onIob) data.basal?.let {
                    PillItem(data.tbrState.toIcon(), stringResource(CoreUiStrings.pump_base_basal_rate, it), ElementType.TEMP_BASAL.color())
                }
                // Same triangles as the IOB and COB graphs
                if (onIob) data.boluses.forEach { bolus ->
                    PillTriangleItem(pointsUp = bolus.bolusType == BolusType.SMB, text = bolus.label, color = ElementType.INSULIN.color())
                }
                if (onCob) data.carbs.forEach { carbs -> PillTriangleItem(pointsUp = false, text = carbs.label, color = ElementType.CARBS.color()) }
                data.extras.forEach { extra -> PillExtra(extra) }
                if (onBg) heldRun?.let { PillLoopRun(it) }
            }
        }
    }
}

/** What the loop run at the reading expected (eventual and lowest BG) and asked for (insulinReq, SMB). */
@Composable
private fun PillLoopRun(run: HeldLoopRun) {
    val decimalFormatter = LocalDecimalFormatter.current
    val profileUtil = LocalProfileUtil.current
    fun bg(v: Double) = if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(v) else decimalFormatter.to1Decimal(v)
    val onSurface = MaterialTheme.colorScheme.onSurface
    run.eventualBg?.let { PillLabelItem(label = stringResource(CoreUiStrings.eventual_bg_shortname), text = bg(it), color = onSurface, bold = false) }
    run.minPredBg?.let { PillLabelItem(label = stringResource(CoreUiStrings.min_pred_bg_shortname), text = bg(it), color = onSurface, bold = false) }
    run.insulinReq?.takeIf { it > 0.0 }?.let {
        PillLabelItem(label = stringResource(CoreUiStrings.insulin_req_shortname), text = stringResource(InterfacesStrings.format_insulin_units, it), color = ElementType.INSULIN.color(), bold = false)
    }
    run.smb?.takeIf { it > 0.0 }?.let { PillTriangleItem(pointsUp = true, text = stringResource(InterfacesStrings.format_insulin_units, it), color = ElementType.INSULIN.color()) }
}

@Composable
private fun PillText(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
}

/** A value of an extra graph: its name and the value in the colour of its graph. */
@Composable
private fun PillExtra(extra: InspectExtra) {
    val decimalFormatter = LocalDecimalFormatter.current
    val profileUtil = LocalProfileUtil.current
    val colors = rememberSeriesColors()
    val mgdl = profileUtil.units == GlucoseUnit.MGDL
    when (extra) {
        is InspectExtra.AutoIsf -> {
            // The factors in their line colours, the applied one in bold in the text colour
            AutoIsfLine.entries.forEach { line ->
                PillLabelItem(
                    label = stringResource(line.label),
                    text = decimalFormatter.to2Decimal(line.valueAt(extra.point)),
                    color = line.color ?: MaterialTheme.colorScheme.onSurface,
                    bold = line == AutoIsfLine.APPLIED
                )
            }
        }

        is InspectExtra.Value   -> {
            val v = extra.value
            val text = when (extra.type) {
                SeriesType.ABS_IOB                           -> stringResource(InterfacesStrings.format_insulin_units, v)
                SeriesType.DEV_CARBS                         -> stringResource(InterfacesStrings.format_carbs, v.roundToInt())
                SeriesType.SENSITIVITY                       -> stringResource(CoreUiStrings.format_percent, v.roundToInt())
                SeriesType.ACTIVITY                          -> decimalFormatter.to3Decimal(v)
                SeriesType.HEART_RATE, SeriesType.STEPS      -> decimalFormatter.to0Decimal(v)
                // BG changes per 5 minutes and ISF are in the user's units
                else                                         -> if (mgdl) decimalFormatter.to0Decimal(v) else decimalFormatter.to1Decimal(v)
            }
            val color = when {
                extra.type == SeriesType.DEV_CARBS -> if (v >= 0) devCarbsPositiveColor() else devCarbsNegativeColor()
                extra.type == SeriesType.DEV_SLOPE && extra.isMin -> devSlopeMinColor()
                else                               -> colors.colorFor(extra.type)
            }
            PillLabelItem(label = stringResource(inspectLabel(extra.type, extra.isMin)), text = text, color = color, bold = false)
        }
    }
}

@Composable
private fun PillLabelItem(label: String, text: String, color: Color, bold: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Bold, color = color)
    }
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
 * Same proportions as [TriangleShape] and [InvertedTriangleShape]. Those only fill the top half of
 * their box, so the triangle is drawn here to be centered.
 */
@Composable
private fun PillTriangle(pointsUp: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(PILL_ICON_SIZE)) {
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
