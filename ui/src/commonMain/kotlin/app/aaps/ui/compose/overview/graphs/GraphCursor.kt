package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Touch cursor for the overview graphs.
 *
 * Long press a graph to show a cursor line and a card with every series in that graph at that time.
 * Keep holding to read, or drag to move the cursor. The card stays after the finger lifts; a tap on
 * any graph, or a pan or zoom of the BG graph, hides it. A normal drag (no hold) still pans the
 * graphs, because the cursor only takes over the touch after the long-press time.
 *
 * The card shows real values in the user's units. Several lines are drawn rescaled (activity overlay,
 * basal bars, dual axes), so the drawn position is never used as a value.
 *
 * Below the values, the card lists the events (SMB, bolus, extended bolus, carbs, profile switch) of
 * the graph that happened since the previous point, each with its own time - see [eventBucketBounds].
 */

/** Cursor id of the BG graph. The fixed IOB graph is [CURSOR_GRAPH_IOB], secondary graph `i` is `i + 1`. */
internal const val CURSOR_GRAPH_BG = -1
internal const val CURSOR_GRAPH_IOB = 0

/** Where the cursor is: which graph, and the time under the finger. */
internal data class GraphCursor(val graphId: Int, val timestamp: Long)

/** A series point is used for the card only if it is at most this far from the cursor time. */
private const val CURSOR_MATCH_WINDOW_MS = 3 * 60_000L

/** Length of the event bucket of the first point, which has no previous point. Same as the BG interval. */
private const val CURSOR_BUCKET_MS = 5 * 60_000L

/**
 * The events that belong to the point at [pointTime] are those after the previous point, up to and
 * including this one: returns (from exclusive, to inclusive). [pointTimes] must be sorted and distinct.
 *
 * Buckets never overlap and leave no gaps, so every event shows at exactly one point. The first point
 * gets [CURSOR_BUCKET_MS]. The last point also takes everything after it: an SMB given just after the
 * newest reading has no later point yet, and must not be lost.
 */
internal fun eventBucketBounds(pointTimes: List<Long>, pointTime: Long): Pair<Long, Long> {
    val index = pointTimes.binarySearch(pointTime)
    val from = if (index > 0) pointTimes[index - 1] else pointTime - CURSOR_BUCKET_MS
    val to = if (index >= 0 && index == pointTimes.lastIndex) Long.MAX_VALUE else pointTime
    return from to to
}

// =========================================================================
// Geometry: x-value <-> canvas pixel
// =========================================================================

/**
 * How x-values map to canvas pixels, written on every draw pass by [GraphGeometryReporter].
 *
 * Not Compose state, for the same reason as [VisibleRangeHolder]: writing state from the draw pass
 * fights Vico's gesture handling. It is only read when the cursor moves.
 *
 * All graphs share one x layout (see the notes at the top of GraphUtils.kt), so the mapping of the
 * fixed IOB graph is used for every graph. The BG graph gets no decoration of its own, because a
 * decoration on BG was found to break its pinch zoom.
 */
class GraphGeometryHolder {

    @Volatile var left = 0f
    @Volatile var right = 0f
    @Volatile var startPadding = 0f
    @Volatile var xSpacing = 0f
    @Volatile var minX = 0.0
    @Volatile var xStep = 0.0
    @Volatile var scroll = 0f

    val isReady: Boolean get() = xSpacing > 0f && xStep != 0.0

    /** Inverse of [canvasXOf]. */
    fun xValueAt(canvasX: Float): Double = minX + xStep * (canvasX - left - startPadding + scroll) / xSpacing

    /** Same transform as [NowLine] uses (mirrors Vico's internal getDrawX). */
    fun canvasXOf(xValue: Double): Float = left + startPadding + xSpacing * ((xValue - minX) / xStep).toFloat() - scroll
}

/** Copies the current x layout of a chart into [holder] on every draw pass. Draws nothing. */
class GraphGeometryReporter(private val holder: GraphGeometryHolder) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            holder.left = layerBounds.left
            holder.right = layerBounds.right
            holder.startPadding = layerDimensions.startPadding
            holder.xSpacing = layerDimensions.xSpacing
            holder.minX = ranges.minX
            holder.xStep = ranges.xStep
            holder.scroll = scroll
        }
    }

    // No equals/hashCode on purpose: compare by identity, like VisibleRangeReporter.
}

// =========================================================================
// Touch handling
// =========================================================================

/** What a touch did before the long-press time ran out. */
private enum class PressOutcome { TAP, MOVED, LONG_PRESS }

/**
 * Touch handling for the cursor. Put it on the graph's own modifier.
 *
 * Events are read in the Initial pass, which runs before the chart's own scroll and zoom handling.
 * Until the long-press time is over nothing is consumed, so a drag or a pinch goes to the chart as
 * before. After it, every event is consumed, so the chart does not scroll while the cursor moves.
 */
@Composable
internal fun rememberGraphCursorInput(
    graphId: Int,
    geometry: GraphGeometryHolder,
    minTimestamp: Long?,
    cursorVisible: Boolean,
    onCursorChange: (GraphCursor?) -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val currentMinTimestamp by rememberUpdatedState(minTimestamp)
    val currentCursorVisible by rememberUpdatedState(cursorVisible)
    val currentOnCursorChange by rememberUpdatedState(onCursorChange)

    return Modifier.pointerInput(graphId, geometry) {
        fun timestampAt(canvasX: Float): Long? {
            val minTs = currentMinTimestamp ?: return null
            if (!geometry.isReady) return null
            return minTs + (geometry.xValueAt(canvasX) * 60_000).toLong()
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var lastX = down.position.x
            val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var result = PressOutcome.MOVED
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A second finger is a pinch zoom: leave it to the chart.
                    if (event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        result = PressOutcome.TAP
                        break
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    lastX = change.position.x
                }
                result
            } ?: PressOutcome.LONG_PRESS

            when (outcome) {
                PressOutcome.TAP        -> if (currentCursorVisible) currentOnCursorChange(null)
                PressOutcome.MOVED      -> Unit
                PressOutcome.LONG_PRESS -> {
                    val start = timestampAt(lastX) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnCursorChange(GraphCursor(graphId, start))
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        timestampAt(change.position.x)?.let { currentOnCursorChange(GraphCursor(graphId, it)) }
                    }
                }
            }
        }
    }
}

// =========================================================================
// Series values
// =========================================================================

/**
 * A series as the cursor reads it.
 * @param isStep the value holds until the next point (basal, target), instead of being a sample
 * @param snap the cursor snaps to the points of this series. When no series sets it, the first one is used.
 * @param colorAt colour for one point, where the colour depends on the point (BG range, deviation type)
 */
private class CursorSeries(
    val label: String,
    val color: Color,
    val points: List<GraphDataPoint>,
    val format: (Double) -> String,
    val isStep: Boolean = false,
    val snap: Boolean = false,
    val colorAt: ((Long) -> Color?)? = null
)

/** One line in the card. A null value means the series has no point near this time. */
private class CursorRow(val label: String, val color: Color, val value: String?)

/** A marker on the graph (SMB, bolus, carbs...), shown in the card with the same shape and its own time. */
private class CursorEvent(val timestamp: Long, val label: String, val color: Color, val shape: Shape, val value: String)

private val UpTriangle = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}
private val DownTriangle = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2f, size.height)
    close()
}

internal fun List<GraphDataPoint>.nearestTo(timestamp: Long): GraphDataPoint? =
    minByOrNull { abs(it.timestamp - timestamp) }?.takeIf { abs(it.timestamp - timestamp) <= CURSOR_MATCH_WINDOW_MS }

internal fun List<GraphDataPoint>.valueInEffectAt(timestamp: Long): Double? =
    filter { it.timestamp <= timestamp }.maxByOrNull { it.timestamp }?.value

private fun List<BgDataPoint>.toGraphPoints(): List<GraphDataPoint> = map { GraphDataPoint(it.timestamp, it.value) }

/** Number formats for the card, by kind of value. */
private class CursorFormats(
    val glucose: (Double) -> String,
    val glucoseChange: (Double) -> String,
    val insulin: (Double) -> String,
    val whole: (Double) -> String,
    val activity: (Double) -> String
)

@Composable
private fun rememberCursorFormats(): CursorFormats {
    val decimalFormatter = LocalDecimalFormatter.current
    val units = LocalProfileUtil.current.units
    return remember(decimalFormatter, units) {
        val mgdl = units == GlucoseUnit.MGDL
        CursorFormats(
            glucose = { if (mgdl) decimalFormatter.to0Decimal(it) else decimalFormatter.to1Decimal(it) },
            glucoseChange = { if (mgdl) decimalFormatter.to1Decimal(it) else decimalFormatter.to2Decimal(it) },
            insulin = { decimalFormatter.to2Decimal(it) },
            whole = { decimalFormatter.to0Decimal(it) },
            activity = { decimalFormatter.to3Decimal(it) }
        )
    }
}

@Composable
private fun rememberBgCursorSeries(viewModel: GraphViewModel, overlays: List<SeriesType>): List<CursorSeries> {
    val formats = rememberCursorFormats()
    val bucketed by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val regular by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val predictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val targets by viewModel.targetLineFlow.collectAsStateWithLifecycle()
    val activity by viewModel.activityGraphFlow.collectAsStateWithLifecycle()

    val lowColor = AapsTheme.generalColors.bgLow
    val inRangeColor = AapsTheme.generalColors.bgInRange
    val highColor = AapsTheme.generalColors.bgHigh
    val predictionLabels = listOf(
        BgType.IOB_PREDICTION to (stringResource(CoreUiStrings.iob) to AapsTheme.generalColors.iobPrediction),
        BgType.COB_PREDICTION to (stringResource(CoreUiStrings.cob) to AapsTheme.generalColors.cobPrediction),
        BgType.A_COB_PREDICTION to (stringResource(UiStrings.graph_cursor_acob_shortname) to AapsTheme.generalColors.aCobPrediction),
        BgType.UAM_PREDICTION to (stringResource(UiStrings.graph_cursor_uam_shortname) to AapsTheme.generalColors.uamPrediction),
        BgType.ZT_PREDICTION to (stringResource(UiStrings.graph_cursor_zt_shortname) to AapsTheme.generalColors.ztPrediction)
    )
    val bgLabel = stringResource(CoreUiStrings.bg_label)
    val targetLabel = stringResource(CoreUiStrings.target_short)
    val activityLabel = stringResource(CoreUiStrings.activity_shortname)
    val targetColor = AapsTheme.elementColors.tempTarget
    val activityColor = rememberSeriesColors().activity
    val showPredictions = SeriesType.PREDICTIONS in overlays
    val showActivity = SeriesType.ACTIVITY in overlays

    return remember(bucketed, regular, predictions, targets, activity, overlays, formats, predictionLabels, bgLabel, targetLabel, activityLabel) {
        buildList {
            val readings = bucketed.ifEmpty { regular }
            val rangeByTime = readings.associate { it.timestamp to it.range }
            add(
                CursorSeries(bgLabel, inRangeColor, readings.toGraphPoints(), formats.glucose, snap = true, colorAt = { t ->
                    when (rangeByTime[t]) {
                        BgRange.LOW      -> lowColor
                        BgRange.HIGH     -> highColor
                        BgRange.IN_RANGE -> inRangeColor
                        null             -> null
                    }
                })
            )
            if (showPredictions) {
                for ((type, labelAndColor) in predictionLabels) {
                    val points = predictions.filter { it.type == type }
                    if (points.isNotEmpty()) add(CursorSeries(labelAndColor.first, labelAndColor.second, points.toGraphPoints(), formats.glucose, snap = true))
                }
            }
            add(CursorSeries(targetLabel, targetColor, targets.targets, formats.glucose, isStep = true))
            if (showActivity) add(CursorSeries(activityLabel, activityColor, activity.activity + activity.activityPrediction, formats.activity))
        }
    }
}

@Composable
private fun rememberIobCursorSeries(viewModel: GraphViewModel, overlays: List<SeriesType>): List<CursorSeries> {
    val formats = rememberCursorFormats()
    val iob by viewModel.iobGraphFlow.collectAsStateWithLifecycle()
    val basal by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val activity by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val colors = rememberSeriesColors()
    val basalColor = AapsTheme.elementColors.tempBasal
    val iobLabel = stringResource(CoreUiStrings.iob)
    val basalLabel = stringResource(CoreUiStrings.basal_shortname)
    val activityLabel = stringResource(CoreUiStrings.activity_shortname)
    val showActivity = SeriesType.ACTIVITY in overlays

    return remember(iob, basal, activity, showActivity, formats, colors, basalColor, iobLabel, basalLabel, activityLabel) {
        buildList {
            add(CursorSeries(iobLabel, colors.iob, iob.iob, formats.insulin))
            add(CursorSeries(basalLabel, basalColor, basal.actualBasal, formats.insulin, isStep = true))
            if (showActivity) add(CursorSeries(activityLabel, colors.activity, activity.activity + activity.activityPrediction, formats.activity))
        }
    }
}

@Composable
private fun rememberSecondaryCursorSeries(viewModel: GraphViewModel, types: List<SeriesType>): List<CursorSeries> {
    val formats = rememberCursorFormats()
    val colors = rememberSeriesColors()
    return types.flatMap { type ->
        val label = stringResource(seriesShortNameId(type))
        val color = colors.colorFor(type)
        when (type) {
            SeriesType.IOB             -> {
                val data by viewModel.iobGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.iob, formats.insulin))
            }

            SeriesType.ABS_IOB         -> {
                val data by viewModel.absIobGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.absIob, formats.insulin))
            }

            SeriesType.COB             -> {
                val data by viewModel.cobGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.cob, formats.whole))
            }

            SeriesType.BGI             -> {
                val data by viewModel.bgiGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.bgi + data.bgiPrediction, formats.glucoseChange))
            }

            SeriesType.DEVIATIONS      -> {
                val data by viewModel.deviationsGraphFlow.collectAsStateWithLifecycle()
                val typeByTime = remember(data) { data.deviations.associate { it.timestamp to it.deviationType } }
                val points = remember(data) { data.deviations.map { GraphDataPoint(it.timestamp, it.value) } }
                listOf(CursorSeries(label, color, points, formats.glucoseChange, colorAt = { t -> typeByTime[t]?.let { deviationColor(it) } }))
            }

            SeriesType.SENSITIVITY     -> {
                // Stored as 100*(ratio-1); the graph shows it shifted by +100 as a percentage, so does the card.
                val data by viewModel.ratioGraphFlow.collectAsStateWithLifecycle()
                val points = remember(data) { data.ratio.map { GraphDataPoint(it.timestamp, it.value + 100.0) } }
                listOf(CursorSeries(label, color, points, formats.whole))
            }

            SeriesType.VAR_SENSITIVITY -> {
                val data by viewModel.varSensGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.varSens, formats.glucose))
            }

            SeriesType.DEV_SLOPE       -> {
                val data by viewModel.devSlopeGraphFlow.collectAsStateWithLifecycle()
                listOf(
                    CursorSeries(label, color, data.dsMax, formats.glucoseChange),
                    CursorSeries(label, DEV_SLOPE_MIN_COLOR, data.dsMin, formats.glucoseChange)
                )
            }

            SeriesType.HEART_RATE      -> {
                val data by viewModel.heartRateGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.heartRates, formats.whole))
            }

            SeriesType.STEPS           -> {
                val data by viewModel.stepsGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.steps, formats.whole))
            }

            SeriesType.ACTIVITY        -> {
                val data by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
                listOf(CursorSeries(label, color, data.activity + data.activityPrediction, formats.activity))
            }

            SeriesType.PREDICTIONS     -> emptyList() // a BG graph overlay flag, never a secondary series
        }
    }
}

// =========================================================================
// Events (markers)
// =========================================================================

/** The markers drawn on this graph: profile switches on BG, insulin on IOB graphs, carbs on COB graphs. */
@Composable
private fun rememberCursorEvents(viewModel: GraphViewModel, graphId: Int, seriesTypes: List<SeriesType>): List<CursorEvent> {
    val formats = rememberCursorFormats()
    if (graphId == CURSOR_GRAPH_BG) {
        val eps by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
        val label = stringResource(UiStrings.graph_cursor_profile_shortname)
        val color = AapsTheme.elementColors.profileSwitch
        return remember(eps, label, color) { eps.map { CursorEvent(it.timestamp, label, color, CircleShape, it.label) } }
    }
    val showInsulin = graphId == CURSOR_GRAPH_IOB || SeriesType.IOB in seriesTypes
    val showCarbs = SeriesType.COB in seriesTypes
    if (!showInsulin && !showCarbs) return emptyList()

    val treatments by viewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val smbLabel = stringResource(CoreUiStrings.smb_shortname)
    val bolusLabel = stringResource(UiStrings.graph_cursor_bolus_shortname)
    val extendedLabel = stringResource(UiStrings.graph_cursor_extended_shortname)
    val carbsLabel = stringResource(UiStrings.graph_cursor_carbs_shortname)
    val insulinColor = AapsTheme.elementColors.insulin
    val extendedColor = AapsTheme.elementColors.extendedBolus
    val carbsColor = AapsTheme.elementColors.carbs
    return remember(treatments, showInsulin, showCarbs, formats, smbLabel, bolusLabel, extendedLabel, carbsLabel, insulinColor, extendedColor, carbsColor) {
        buildList {
            if (showInsulin) {
                treatments.boluses.filter { it.isValid }.forEach {
                    if (it.bolusType == BolusType.SMB) add(CursorEvent(it.timestamp, smbLabel, insulinColor, UpTriangle, it.label))
                    else add(CursorEvent(it.timestamp, bolusLabel, insulinColor, DownTriangle, it.label))
                }
                treatments.extendedBoluses.forEach {
                    add(CursorEvent(it.timestamp, extendedLabel, extendedColor, RoundedCornerShape(AapsSpacing.extraSmall), formats.insulin(it.amount)))
                }
            }
            if (showCarbs) {
                treatments.carbs.filter { it.isValid }.forEach {
                    add(CursorEvent(it.timestamp, carbsLabel, carbsColor, CircleShape, formats.whole(it.amount)))
                }
            }
        }
    }
}

// =========================================================================
// Overlay: cursor line + card
// =========================================================================

/**
 * Draws the cursor line and the card for one graph. Put it in the graph's Box, after the graph,
 * with `Modifier.matchParentSize()`. It has no touch handling, so the graph and its edit button
 * still get every touch.
 *
 * The cursor snaps to the nearest point of the graph's main series (on the BG graph, BG readings
 * and predictions), and the card shows that point's time.
 *
 * @param seriesTypes the BG overlays, the IOB overlays, or the series of a secondary graph
 */
@Composable
internal fun GraphCursorOverlay(
    viewModel: GraphViewModel,
    graphId: Int,
    seriesTypes: List<SeriesType>,
    cursorTimestamp: Long,
    minTimestamp: Long,
    geometry: GraphGeometryHolder,
    modifier: Modifier = Modifier
) {
    val series = when (graphId) {
        CURSOR_GRAPH_BG  -> rememberBgCursorSeries(viewModel, seriesTypes)
        CURSOR_GRAPH_IOB -> rememberIobCursorSeries(viewModel, seriesTypes)
        else             -> rememberSecondaryCursorSeries(viewModel, seriesTypes)
    }

    val allEvents = rememberCursorEvents(viewModel, graphId, seriesTypes)

    val snapSeries = series.filter { it.snap }.ifEmpty { series.take(1) }
    val snapTimes = snapSeries.flatMap { s -> s.points.map { it.timestamp } }.distinct().sorted()
    val snapped = snapTimes.minByOrNull { abs(it - cursorTimestamp) } ?: cursorTimestamp

    if (!geometry.isReady) return
    val x = geometry.canvasXOf(timestampToX(snapped, minTimestamp))
    if (x < geometry.left || x > geometry.right) return

    val rows = series.map { s ->
        val value = if (s.isStep) s.points.valueInEffectAt(snapped) else s.points.nearestTo(snapped)?.value
        val pointTime = if (s.isStep) null else s.points.nearestTo(snapped)?.timestamp
        CursorRow(
            label = s.label,
            color = pointTime?.let { s.colorAt?.invoke(it) } ?: s.color,
            value = value?.let(s.format)
        )
    }

    val (eventsFrom, eventsTo) = eventBucketBounds(snapTimes, snapped)
    val events = allEvents.filter { it.timestamp > eventsFrom && it.timestamp <= eventsTo }.sortedBy { it.timestamp }

    val dateUtil = LocalDateUtil.current
    val eventTimes = events.map { dateUtil.timeString(it.timestamp) }
    val timeText = dateUtil.timeString(snapped)
    val deltaText = cursorDeltaText(snapped, dateUtil.now())
    val lineColor = MaterialTheme.colorScheme.onSurface

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        CursorCardPlacement(anchorX = x, modifier = Modifier.matchParentSize()) {
            CursorCard(timeText, deltaText, rows, events, eventTimes)
        }
    }
}

/** Time from now of the touched point, for example "−20 min", "+1 h 20 min" or "now". */
@Composable
private fun cursorDeltaText(timestamp: Long, now: Long): String {
    val minutes = ((timestamp - now) / 60_000.0).roundToLong()
    if (minutes == 0L) return stringResource(UiStrings.graph_cursor_now)
    val total = abs(minutes).toInt()
    val hours = total / 60
    val mins = total % 60
    val duration = when {
        hours == 0 -> stringResource(CoreUiStrings.format_mins, mins)
        mins == 0  -> stringResource(CoreUiStrings.format_hours_only, hours)
        else       -> stringResource(CoreUiStrings.format_hour_minute, hours, mins)
    }
    return stringResource(if (minutes < 0) UiStrings.graph_cursor_ago else UiStrings.graph_cursor_ahead, duration)
}

/** Places the card beside the cursor line: on its right, or on its left when there is no room. */
@Composable
private fun CursorCardPlacement(anchorX: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        // The card may be taller than a short graph; let it overflow rather than squeeze it.
        val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val gap = AapsSpacing.medium.roundToPx()
            val rightSide = anchorX.roundToInt() + gap
            val x = if (rightSide + card.width <= constraints.maxWidth) rightSide else anchorX.roundToInt() - gap - card.width
            card.place(x.coerceIn(0, (constraints.maxWidth - card.width).coerceAtLeast(0)), AapsSpacing.small.roundToPx())
        }
    }
}

@Composable
private fun CursorCard(timeText: String, deltaText: String, rows: List<CursorRow>, events: List<CursorEvent>, eventTimes: List<String>) {
    val shape = MaterialTheme.shapes.small
    val textStyle = MaterialTheme.typography.labelSmall
    val numberStyle = textStyle.copy(fontFeatureSettings = "tnum")
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = timeText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(AapsSpacing.large))
            Spacer(Modifier.weight(1f))
            Text(text = deltaText, style = numberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(AapsSpacing.medium)
                        .background(row.color, RoundedCornerShape(AapsSpacing.extraSmall))
                )
                Spacer(Modifier.width(AapsSpacing.small))
                Text(text = row.label, style = textStyle)
                Spacer(Modifier.width(AapsSpacing.large))
                Spacer(Modifier.weight(1f))
                Text(
                    text = row.value ?: "–",
                    style = numberStyle,
                    color = if (row.value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (events.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = AapsSpacing.extraSmall),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            events.forEachIndexed { index, event ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(AapsSpacing.medium)
                            .background(event.color, event.shape)
                    )
                    Spacer(Modifier.width(AapsSpacing.small))
                    Text(text = event.label, style = textStyle)
                    Spacer(Modifier.width(AapsSpacing.large))
                    Spacer(Modifier.weight(1f))
                    Text(text = event.value, style = numberStyle)
                    Spacer(Modifier.width(AapsSpacing.medium))
                    Text(text = eventTimes[index], style = numberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
