package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.ui.MarkerTier
import app.aaps.core.graph.vico.AdaptiveStep
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.AutoIsfFactorPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.MpcPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * CartesianChartModelProducer.update() skips notifying receivers (and thus skips recomputing axis
 * ranges) when a transaction's partials AND extraStore are both unchanged from the last one. Since
 * scrolling/zooming re-submits identical series data (only the axis ranges changed), stashing
 * the axis setup here forces the extraStore to differ, so Vico actually reprocesses the
 * transaction instead of silently dropping it.
 */
private val AXIS_SCALE_KEY = ExtraStore.Key<AxisScale>()

/** The largest manual bolus and carbs entry of the loaded range: the markers are sized against them, see [TieredPointProvider]. */
private val MAX_MANUAL_BOLUS_KEY = ExtraStore.Key<Double>()
private val MAX_CARBS_KEY = ExtraStore.Key<Double>()

/** The axis setup that a scroll or zoom can change. Equal values mean the axes did not change. */
private data class AxisScale(
    val left: CartesianLayerRangeProvider,
    val right: CartesianLayerRangeProvider,
    val basal: CartesianLayerRangeProvider,
    val startAxisItemPlacer: VerticalAxis.ItemPlacer,
    val endAxisItemPlacer: VerticalAxis.ItemPlacer
)

/** Everything the chart model of a graph is built from. Equal values give the same model. */
private data class ModelInput(
    val leftSpecs: List<SeriesSpec>,
    val rightSpecs: List<SeriesSpec>,
    val basalProfile: List<Pair<Double, Double>>,
    val basalActual: List<Pair<Double, Double>>,
    /** Alpha build: the basal of the shadow loop, a third series of the basal layer when it has two points or more */
    val basalShadow: List<Pair<Double, Double>>,
    val hasBasalLayer: Boolean,
    val hasRightAxis: Boolean,
    val rightFirst: Boolean,
    val maxX: Double,
    val axisScale: AxisScale,
    val maxManualBolus: Double?,
    val maxCarbs: Double?
)

/**
 * General-purpose secondary graph composable.
 *
 * Shows one or two series. Every series draws the same way, on either axis:
 * - IOB: Blue line with area fill + bolus markers (SMBs, normal boluses, extended boluses)
 * - COB: Orange adaptive-step line with area fill + carbs markers + failover dots
 * - Simple line series (AbsIOB, BGI, Sensitivity, VarSens, DevSlope, HR, Steps): Colored line with gradient fill
 * - Deviations: Per-type colored step lines with gradient fill (POSITIVE/NEGATIVE/EQUAL/UAM/CSF)
 *
 * The first series is drawn against the left axis and the second against the right one, unless the
 * two share a unit and so one axis. The axes follow the rules in SecondaryGraphAxes.kt.
 */
@Composable
fun SecondaryGraphCompose(
    viewModel: GraphViewModel,
    seriesTypes: List<SeriesType>,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    activityOverlay: Boolean = false,
    onVisibleRangeChanged: ((Pair<Double, Double>?) -> Unit)? = null,
    /** While holding the BG graph, a line at the inspected time. Null turns it off. */
    inspect: GraphInspectState? = null,
    /** Gets this graph's x layout on every draw, for a pinch on another graph (see GraphForwardToBg.kt). */
    geometryHolder: GraphGeometryHolder? = null,
    /** Alpha build: also draw the IOB, SMBs and basal of the shadow loop over the real ones (IOB graph only) */
    shadowOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    if (seriesTypes.isEmpty()) return

    val sides = remember(seriesTypes) { graphSides(seriesTypes) }

    val hasRealTimeRange = derivedTimeRange != null
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = dateUtil.now()
        val dayAgo = now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L
        dayAgo to now
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    val minX = 0.0
    val maxX = remember(minTimestamp, maxTimestamp) {
        timestampToX(maxTimestamp, minTimestamp)
    }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) {
        minTimestamp to maxTimestamp
    }

    // =========================================================================
    // Collect data flows — only for the series this graph shows
    // =========================================================================

    val hasIob = SeriesType.IOB in seriesTypes
    val hasCob = SeriesType.COB in seriesTypes
    // The basal band takes the top of the graph, so only when IOB is the graph's only series
    val hasBasalLayer = sides.left == listOf(SeriesType.IOB) && sides.right.isEmpty()

    val iobData = if (hasIob) viewModel.iobGraphFlow.collectAsStateWithLifecycle().value else null
    val cobData = if (hasCob) viewModel.cobGraphFlow.collectAsStateWithLifecycle().value else null
    val treatmentData = if (hasIob || hasCob) viewModel.treatmentGraphFlow.collectAsStateWithLifecycle().value else null
    val absIobData = if (SeriesType.ABS_IOB in seriesTypes) viewModel.absIobGraphFlow.collectAsStateWithLifecycle().value else null
    val bgiData = if (SeriesType.BGI in seriesTypes) viewModel.bgiGraphFlow.collectAsStateWithLifecycle().value else null
    val deviationsData = if (SeriesType.DEVIATIONS in seriesTypes) viewModel.deviationsGraphFlow.collectAsStateWithLifecycle().value else null
    // While the user holds a point: the deviations the loop run there expected, drawn over what came
    val heldRun = if (SeriesType.DEVIATIONS in seriesTypes) viewModel.heldLoopRunFlow.collectAsStateWithLifecycle().value else null
    val predictedDeviationsData =
        if (SeriesType.DEVIATIONS in seriesTypes) heldRun?.deviations ?: viewModel.predictedDeviationsFlow.collectAsStateWithLifecycle().value else null
    val ratioData = if (SeriesType.SENSITIVITY in seriesTypes) viewModel.ratioGraphFlow.collectAsStateWithLifecycle().value else null
    val varSensData = if (SeriesType.VAR_SENSITIVITY in seriesTypes) viewModel.varSensGraphFlow.collectAsStateWithLifecycle().value else null
    val devSlopeData = if (SeriesType.DEV_SLOPE in seriesTypes) viewModel.devSlopeGraphFlow.collectAsStateWithLifecycle().value else null
    val hrData = if (SeriesType.HEART_RATE in seriesTypes) viewModel.heartRateGraphFlow.collectAsStateWithLifecycle().value else null
    val stepsData = if (SeriesType.STEPS in seriesTypes) viewModel.stepsGraphFlow.collectAsStateWithLifecycle().value else null
    // DEV carbs: one list per run, each drawn on its own
    val devCarbsRuns = if (SeriesType.DEV_CARBS in seriesTypes) viewModel.deviationsGraphFlow.collectAsStateWithLifecycle().value.devCarbs else null
    // AutoISF factors and MPC meal values are stored with the loop runs, read with the variable sensitivity
    val loopRunValues =
        if (SeriesType.AUTOISF in seriesTypes || SeriesType.MEAL_CHANCE in seriesTypes || SeriesType.MEAL_CARBS in seriesTypes || SeriesType.VAR_SENSITIVITY in seriesTypes)
            viewModel.varSensGraphFlow.collectAsStateWithLifecycle().value else null
    val autoIsfData = if (SeriesType.AUTOISF in seriesTypes) loopRunValues?.autoIsf else null
    val mpcData = if (SeriesType.MEAL_CHANCE in seriesTypes || SeriesType.MEAL_CARBS in seriesTypes) loopRunValues?.mpc else null
    // Profile ISF, drawn as a dashed line wherever variable sensitivity is drawn
    val profileIsf = if (SeriesType.VAR_SENSITIVITY in seriesTypes) loopRunValues?.profileIsf else null
    // Activity data: either as a series OR as the overlay on the IOB graph
    val needsActivity = SeriesType.ACTIVITY in seriesTypes || (activityOverlay && hasIob)
    val activityData = if (needsActivity) viewModel.activityGraphFlow.collectAsStateWithLifecycle().value else null
    val basalData = if (hasBasalLayer) viewModel.basalGraphFlow.collectAsStateWithLifecycle().value else null
    // Alpha build: what the shadow loop would have given, drawn over the real IOB and basal
    val shadowData = if (shadowOverlay && hasBasalLayer) viewModel.shadowDosingFlow.collectAsStateWithLifecycle().value else null

    // Cache last non-empty treatment data to survive reset() cycles
    val lastTreatmentData = remember { mutableStateOf(treatmentData ?: TreatmentGraphData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) }
    if (treatmentData != null && (treatmentData.boluses.isNotEmpty() || treatmentData.carbs.isNotEmpty() || treatmentData.extendedBoluses.isNotEmpty())) {
        lastTreatmentData.value = treatmentData
    }

    // =========================================================================
    // Process all series into x/y coordinates
    // =========================================================================

    // Simple line series processing (excludes BASAL — it's a fixed flipped overlay on IOB)
    val processedSimpleSeries = remember(
        seriesTypes, stableTimeRange, absIobData, bgiData, ratioData, varSensData, devSlopeData, hrData, stepsData, devCarbsRuns, activityData
    ) {
        if (!hasRealTimeRange) return@remember emptyList()
        buildList {
            absIobData?.absIob?.takeIf { it.isNotEmpty() }?.let {
                add(SeriesType.ABS_IOB to processPoints(it, minTimestamp, minX, maxX))
            }
            bgiData?.let {
                if (it.bgi.isNotEmpty()) add(SeriesType.BGI to processPoints(it.bgi, minTimestamp, minX, maxX))
                // TODO: differentiate prediction line style (e.g., dashed) from historical
                if (it.bgiPrediction.isNotEmpty()) add(SeriesType.BGI to processPoints(it.bgiPrediction, minTimestamp, minX, maxX))
            }
            ratioData?.ratio?.takeIf { it.isNotEmpty() }?.let { pts ->
                // Stored as 100*(ratio-1), display shifted by +100 to show as percentage (90%, 110%)
                val shifted = pts.map { GraphDataPoint(it.timestamp, it.value + 100.0) }
                add(SeriesType.SENSITIVITY to processPoints(shifted, minTimestamp, minX, maxX))
            }
            // Drawn negated, so a lower ISF (more insulin per mmol/L) is lower on the graph; the labels negate it back
            varSensData?.varSens?.takeIf { it.isNotEmpty() }?.let {
                add(SeriesType.VAR_SENSITIVITY to processPoints(invertIsf(it), minTimestamp, minX, maxX))
            }
            devSlopeData?.let {
                if (it.dsMax.isNotEmpty()) add(SeriesType.DEV_SLOPE to processPoints(it.dsMax, minTimestamp, minX, maxX))
                // dsMin tagged separately via DEV_SLOPE_MIN marker (see below)
            }
            hrData?.heartRates?.takeIf { it.isNotEmpty() }?.let {
                add(SeriesType.HEART_RATE to processPoints(it, minTimestamp, minX, maxX))
            }
            stepsData?.steps?.takeIf { it.isNotEmpty() }?.let {
                add(SeriesType.STEPS to processPoints(it, minTimestamp, minX, maxX))
            }
            devCarbsRuns?.forEach { run ->
                processPoints(run, minTimestamp, minX, maxX).takeIf { it.isNotEmpty() }?.let { add(SeriesType.DEV_CARBS to it) }
            }
            // Not when activity is only the overlay of the IOB graph: that one is scaled into IOB units below
            if (SeriesType.ACTIVITY in seriesTypes) {
                activityData?.let {
                    if (it.activity.isNotEmpty()) add(SeriesType.ACTIVITY to processPoints(it.activity, minTimestamp, minX, maxX))
                    if (it.activityPrediction.isNotEmpty()) add(SeriesType.ACTIVITY to processPoints(it.activityPrediction, minTimestamp, minX, maxX))
                }
            }
        }
    }

    // DevSlope min line — separate from simple series for distinct color
    val processedDevSlopeMin = remember(devSlopeData, stableTimeRange) {
        if (!hasRealTimeRange || devSlopeData == null || devSlopeData.dsMin.isEmpty()) return@remember emptyList()
        processPoints(devSlopeData.dsMin, minTimestamp, minX, maxX)
    }

    // AutoISF: one line per factor. The applied factor first, so the factors and their dots stay
    // visible where they run along it.
    val processedAutoIsf = remember(autoIsfData, stableTimeRange) {
        if (!hasRealTimeRange || autoIsfData.isNullOrEmpty()) return@remember emptyList()
        AutoIsfLine.entries.sortedBy { it != AutoIsfLine.APPLIED }.map { line ->
            line to processPoints(autoIsfData.map { GraphDataPoint(it.timestamp, line.valueAt(it)) }, minTimestamp, minX, maxX)
        }.filter { it.second.isNotEmpty() }
    }

    // MPC meal values: one line per value, the main one of each kind last so it is drawn on top
    val processedMpc = remember(mpcData, stableTimeRange) {
        if (!hasRealTimeRange || mpcData.isNullOrEmpty()) return@remember emptyList()
        MpcLine.entries.sortedBy { it.main }.map { line ->
            line to processPoints(mpcData.map { GraphDataPoint(it.timestamp, line.valueAt(it)) }, minTimestamp, minX, maxX)
        }.filter { it.second.isNotEmpty() }
    }

    // Deviations the newest loop run expects, as bars after now: one series per curve, the UAM curve
    // (usually the longest) first so the shorter ones are drawn on top of it
    val processedPredictedDeviations = remember(predictedDeviationsData, stableTimeRange) {
        if (!hasRealTimeRange || predictedDeviationsData == null) return@remember emptyList()
        listOf(
            PredictedDeviationCurve.UAM to predictedDeviationsData.uam,
            PredictedDeviationCurve.COB to predictedDeviationsData.cob,
            PredictedDeviationCurve.IOB to predictedDeviationsData.iob
        ).map { (curve, points) -> curve to processPoints(points, minTimestamp, minX, maxX) }
            .filter { it.second.isNotEmpty() }
    }

    // Deviation line data — split by DeviationType for per-type colored lines with gradient fill.
    // Each type series has ALL x-coordinates; y=0 where the point's type doesn't match.
    // With Square connector this creates filled step-rectangles at each deviation point.
    val processedDeviationLines = remember(deviationsData, stableTimeRange) {
        if (!hasRealTimeRange || deviationsData == null || deviationsData.deviations.isEmpty())
            return@remember null
        val filtered = deviationsData.deviations
            .map { Triple(timestampToX(it.timestamp, minTimestamp), it.value, it.deviationType) }
            .filter { it.first in minX..maxX }
            .sortedBy { it.first }
        if (filtered.isEmpty()) return@remember null
        val allX = filtered.map { it.first }
        val typeValueMap = filtered.associate { it.first to (it.second to it.third) }
        // Build per-type y arrays: real value where type matches, 0.0 otherwise
        val perType = DeviationType.entries.associateWith { type ->
            allX.map { x ->
                val (value, pointType) = typeValueMap[x]!!
                if (pointType == type) value else 0.0
            }
        }
        // Only include types that have at least one non-zero value
        val activeTypes = perType.filter { (_, ys) -> ys.any { it != 0.0 } }
        // A Square step holds each value until the next point, so the newest point had no width and
        // its bar was missing. Close it with one more point 5 minutes later.
        val closeX = minOf(allX.last() + 5.0, maxX)
        if (closeX > allX.last())
            ProcessedDeviationLines(allX + closeX, activeTypes.mapValues { (_, ys) -> ys + ys.last() })
        else
            ProcessedDeviationLines(allX, activeTypes)
    }

    // IOB line processing
    val processedIob = remember(iobData, stableTimeRange) {
        if (!hasRealTimeRange || iobData == null) return@remember emptyList()
        processPoints(iobData.iob, minTimestamp, minX, maxX)
    }

    // IOB treatment overlays processing. hasIob is a key: the treatments outlive the series in lastTreatmentData.
    val processedIobTreatments = remember(hasIob, lastTreatmentData.value, stableTimeRange) {
        if (!hasRealTimeRange || !hasIob) return@remember ProcessedIobOverlays.EMPTY
        processIobOverlays(lastTreatmentData.value, minTimestamp, minX, maxX)
    }

    // COB line processing
    val processedCob = remember(cobData, stableTimeRange) {
        if (!hasRealTimeRange || cobData == null) return@remember Pair(emptyList(), emptyList())
        val cobFiltered = processPoints(cobData.cob, minTimestamp, minX, maxX)
        val failoverFiltered = cobData.failOverPoints.map { timestampToX(it.timestamp, minTimestamp) to it.cobValue }
            .let { filterToRange(it, minX, maxX) }
        cobFiltered to failoverFiltered
    }

    // COB carbs overlay processing. hasCob is a key: the treatments outlive the series in lastTreatmentData.
    val processedCarbs = remember(hasCob, lastTreatmentData.value, stableTimeRange) {
        if (!hasRealTimeRange || !hasCob) return@remember emptyList()
        val pts = lastTreatmentData.value.carbs.map { timestampToX(it.timestamp, minTimestamp) to it.amount }
        filterToRange(pts, minX, maxX)
    }

    // Flipped basal overlay — fixed on IOB graphs, rendered as a second chart layer.
    // Uses negative Y values so area fill goes upward to y=0 (the top of the basal layer).
    val processedBasalProfile = remember(basalData, stableTimeRange) {
        if (!hasRealTimeRange || basalData == null) return@remember emptyList()
        val pts = processStepPoints(basalData.profileBasal, minTimestamp, minX, maxX)
        pts.map { (x, y) -> x to -y } // negate: y=0 at top, -maxBasal at bottom
    }
    val processedBasalActual = remember(basalData, stableTimeRange) {
        if (!hasRealTimeRange || basalData == null) return@remember emptyList()
        val pts = processStepPoints(basalData.actualBasal, minTimestamp, minX, maxX)
        pts.map { (x, y) -> x to -y }
    }

    // Shadow overlay: its IOB line, its SMBs (as markers on the zero line, like the real ones) and its basal (flipped)
    val processedShadowIob = remember(shadowData, stableTimeRange) {
        if (!hasRealTimeRange || shadowData == null) return@remember emptyList()
        processPoints(shadowData.iob.iob, minTimestamp, minX, maxX)
    }
    val processedShadowSmbs = remember(shadowData, stableTimeRange) {
        if (!hasRealTimeRange || shadowData == null) return@remember emptyList()
        filterToRange(shadowData.treatments.boluses.filter { it.bolusType == BolusType.SMB }.map { timestampToX(it.timestamp, minTimestamp) to 0.0 }, minX, maxX)
    }
    val processedShadowBasal = remember(shadowData, stableTimeRange) {
        if (!hasRealTimeRange || shadowData == null) return@remember emptyList()
        processStepPoints(shadowData.basal.actualBasal, minTimestamp, minX, maxX).map { (x, y) -> x to -y }
    }

    // What each series draws: its line and its markers, the same on either axis
    val seriesSpecs = remember(
        seriesTypes, processedDeviationLines, processedIob, processedIobTreatments, processedCob, processedCarbs, processedSimpleSeries, processedDevSlopeMin,
        processedPredictedDeviations, processedShadowIob, processedShadowSmbs, processedAutoIsf, processedMpc
    ) {
        seriesTypes.associateWith { type ->
            buildList {
                when (type) {
                    SeriesType.DEVIATIONS -> {
                        processedDeviationLines?.series?.forEach { (deviationType, yValues) ->
                            add(SeriesSpec(processedDeviationLines.allX, yValues, SeriesSlot.DeviationLine(deviationType)))
                        }
                        // Expected deviations after now, drawn as bars like the deviations before it
                        processedPredictedDeviations.forEach { (curve, points) -> addPoints(points, SeriesSlot.PredictedDeviation(curve)) }
                    }

                    SeriesType.IOB        -> {
                        addPoints(processedIob, SeriesSlot.IobLine)
                        addPoints(processedIobTreatments.smallSmbs, SeriesSlot.SmallSmb)
                        addPoints(processedIobTreatments.mediumSmbs, SeriesSlot.MediumSmb)
                        addPoints(processedIobTreatments.largeSmbs, SeriesSlot.LargeSmb)
                        addPoints(processedIobTreatments.normalBoluses, SeriesSlot.NormalBolus)
                        processedIobTreatments.extBoluses.forEach { (start, end, amount) ->
                            add(SeriesSpec(listOf(start, end), listOf(amount, amount), SeriesSlot.ExtBolus))
                        }
                        // Shadow IOB line and SMBs, over the real ones
                        addPoints(processedShadowIob, SeriesSlot.ShadowIobLine)
                        addPoints(processedShadowSmbs, SeriesSlot.ShadowSmb)
                    }

                    SeriesType.COB        -> {
                        addPoints(processedCob.first, SeriesSlot.CobLine)
                        addPoints(processedCob.second, SeriesSlot.FailoverDots)
                        addPoints(processedCarbs, SeriesSlot.CarbsMarker)
                    }

                    SeriesType.AUTOISF    -> processedAutoIsf.forEach { (line, points) -> addPoints(points, SeriesSlot.AutoIsfFactor(line)) }

                    SeriesType.MEAL_CHANCE, SeriesType.MEAL_CARBS ->
                        processedMpc.filter { it.first.type == type }.forEach { (line, points) -> addPoints(points, SeriesSlot.MpcValue(line)) }

                    else                  -> {
                        // Steps as bars, heart rate as a line broken where readings are missing, DEV carbs as
                        // one green or red area per run
                        processedSimpleSeries.filter { it.first == type }.forEach { (_, points) ->
                            for (run in shapedRuns(type, points)) {
                                val slot = if (type == SeriesType.DEV_CARBS) SeriesSlot.DevCarbsRun(isPositiveRun(run)) else SeriesSlot.SimpleLine(type)
                                addPoints(run, slot)
                            }
                        }
                        if (type == SeriesType.DEV_SLOPE) addPoints(processedDevSlopeMin, SeriesSlot.DevSlopeMin)
                    }
                }
            }
        }
    }
    // Deviation bars first on an axis they share, so the line of the other series stays on top
    val leftBaseSpecs = remember(sides, seriesSpecs) { sides.left.sortedBy { it != SeriesType.DEVIATIONS }.flatMap { seriesSpecs[it].orEmpty() } }
    val rightSpecs = remember(sides, seriesSpecs) { sides.right.sortedBy { it != SeriesType.DEVIATIONS }.flatMap { seriesSpecs[it].orEmpty() } }

    // Visible-window bounds (same x-unit as processed* points — minutes from minTimestamp) for
    // windowing the Y-axis scale to only the currently scrolled/zoomed portion of this graph's
    // own chart, instead of the full loaded time range.
    // The reporter writes into a StateFlow holder (not Compose state) on every draw pass — see
    // VisibleRangeHolder — and we collect it here, keeping Compose state writes off the draw path.
    // The axis ranges below are windowed to it, and the model is rebuilt when they change — Vico
    // only reliably re-applies axis ranges via a real modelProducer.runTransaction, not merely by
    // swapping the CartesianLayerRangeProvider instance.
    val visibleRangeHolder = remember { VisibleRangeHolder() }
    val visibleRangeReporter = rememberVisibleRangeReporter(visibleRangeHolder)

    var visibleRange by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Runs only when the range changes. The delay limits updates to 20 per second during
    // scroll/zoom (each one rebuilds the model); the StateFlow keeps the latest value meanwhile.
    LaunchedEffect(visibleRangeHolder) {
        visibleRangeHolder.value.collect {
            visibleRange = it
            delay(50)
        }
    }

    // Expose this graph's own (already throttled) visible window upward — used by BgGraphCompose
    // to window its own axis from this graph's synced scroll/zoom instead of attaching its own
    // decoration (that was tried and found to break BG's own pinch-zoom gesture handling).
    val currentOnVisibleRangeChanged by rememberUpdatedState(onVisibleRangeChanged)
    LaunchedEffect(visibleRange) {
        currentOnVisibleRangeChanged?.invoke(visibleRange)
    }

    val visibleMinX = visibleRange?.first
    val visibleMaxX = visibleRange?.second

    // Each axis by the rule of its series, from what the visible window shows. Carb markers count:
    // carbs come straight from the database while COB waits for the calculation, and an axis sized
    // without them drew a COB curve of 148 straight through an axis of 0..44 after a restart.
    // The profile ISF line is part of the range of the axis variable sensitivity is drawn on, so it is always in view
    val profileIsfY = profileIsf?.let { -it }
    fun withProfileIsf(side: List<SeriesType>, values: List<Double>): List<Double> =
        if (profileIsfY != null && SeriesType.VAR_SENSITIVITY in side && values.isNotEmpty()) values + profileIsfY else values
    val leftRange = remember(sides, leftBaseSpecs, profileIsfY, visibleMinX, visibleMaxX) {
        sides.left.firstOrNull()?.let { axisRange(axisRuleOf(it), withProfileIsf(sides.left, windowedValues(leftBaseSpecs, visibleMinX, visibleMaxX))) }
    }
    val rightRange = remember(sides, rightSpecs, profileIsfY, visibleMinX, visibleMaxX) {
        sides.right.firstOrNull()?.let { axisRange(axisRuleOf(it), withProfileIsf(sides.right, windowedValues(rightSpecs, visibleMinX, visibleMaxX))) }
    }

    // Activity overlay — mapped into the IOB axis' own units, zero activity on IOB zero. The IOB
    // scale is never changed to fit it: the curve gets the room the IOB axis already leaves, which
    // is the IOB data region above zero and IOB's own negative sliver below zero (usually none, so
    // a negative activity tail is clipped there).
    // Both the activity range and the IOB range come from the visible window only.
    val processedActivityOverlay = remember(activityOverlay, hasIob, activityData, leftRange, stableTimeRange, visibleMinX, visibleMaxX) {
        if (!hasRealTimeRange || !activityOverlay || !hasIob || activityData == null) return@remember Pair(emptyList(), emptyList())
        val iobRange = leftRange ?: return@remember Pair(emptyList(), emptyList())
        val hist = activityData.activity
            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
            .let { filterToRange(it, minX, maxX) }
        val pred = activityData.activityPrediction
            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
            .let { filterToRange(it, minX, maxX) }
        val windowed = windowedY(hist + pred, visibleMinX, visibleMaxX)
        if (windowed.isEmpty()) return@remember Pair(emptyList(), emptyList())
        val scale = activityOverlayScale(
            activityMin = windowed.min(),
            activityMax = windowed.max(),
            zeroLevel = 0.0,
            roomAbove = iobRange.max,
            roomBelow = -iobRange.min
        )
        if (scale.scale <= 0.0) return@remember Pair(emptyList(), emptyList())
        hist.map { (x, y) -> x to scale.map(y) } to pred.map { (x, y) -> x to scale.map(y) }
    }
    // The overlay is drawn in IOB units, so it does not count for the IOB axis it is scaled from
    val leftSpecs = remember(leftBaseSpecs, processedActivityOverlay) {
        val (activityHistory, activityPrediction) = processedActivityOverlay
        buildList {
            addAll(leftBaseSpecs)
            addPoints(activityHistory, SeriesSlot.ActivityOverlay, countsForRange = false)
            addPoints(activityPrediction, SeriesSlot.ActivityOverlay, countsForRange = false)
        }
    }

    val axes = remember(leftRange, rightRange, hasBasalLayer) { graphAxes(leftRange, rightRange, hasBasalLayer) }
    val hasRightAxis = sides.right.isNotEmpty()
    // Deviation bars are drawn first, so the line of the other axis stays on top of them
    val rightFirst = SeriesType.DEVIATIONS in sides.right

    // =========================================================================
    // Line styles
    // =========================================================================

    val seriesColors = rememberSeriesColors()
    val iobLineStyle = rememberIobLineStyles()
    val cobLineStyle = rememberCobLineStyles()
    val normalizerLine = remember { createNormalizerLine() }
    val extraStyles = rememberExtraLineStyles()

    // The normalizer comes first, here and in the model below. Vico pairs lines and series by index,
    // and when the series of this graph change, it draws the new lines with the old model until the
    // new model is built. With the normalizer at index 0 it is still paired with itself then, so the
    // point spacing stays the same as in the other graphs. When it came last, the spacing shrank for
    // that moment, Vico cut the scroll down to the new end, and the graph stayed shifted sideways.
    val leftLines = remember(leftSpecs, seriesColors, iobLineStyle, cobLineStyle, extraStyles, normalizerLine) {
        listOf(normalizerLine) + leftSpecs.map { lineFor(it.slot, seriesColors, iobLineStyle, cobLineStyle, extraStyles) }
    }
    val rightLines = remember(rightSpecs, seriesColors, iobLineStyle, cobLineStyle, extraStyles, normalizerLine) {
        listOf(normalizerLine) + rightSpecs.map { lineFor(it.slot, seriesColors, iobLineStyle, cobLineStyle, extraStyles) }
    }

    // Basal layer line styles — same as BG graph (profile dashed, actual solid with fill)
    val basalColor = AapsTheme.elementColors.tempBasal
    val basalProfileLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.dp,
                cap = StrokeCap.Round,
                dashLength = 1.dp,
                gapLength = 2.dp
            ),
            areaFill = null,
            interpolator = Square
        )
    }
    val basalActualLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(basalColor.copy(alpha = 0.3f))),
            interpolator = Square
        )
    }
    val hasShadowBasal = processedShadowBasal.size >= 2
    val basalLines = remember(basalActualLine, basalProfileLine, extraStyles, hasShadowBasal) {
        if (hasShadowBasal) listOf(basalActualLine, basalProfileLine, extraStyles.shadowBasalLine) else listOf(basalActualLine, basalProfileLine)
    }

    // =========================================================================
    // Chart rendering
    // =========================================================================

    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)
    val nowLineColor = MaterialTheme.colorScheme.onSurface
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)
    val inspectLine = rememberInspectLine(inspect)
    val geometryReporter = remember(geometryHolder) { geometryHolder?.let { GraphGeometryReporter(it) } }
    // Where insulin activity will peak, while that is still ahead (only where activity is drawn)
    val activityPeak = remember(activityData, nowTimestamp) { activityData?.let { futureActivityPeak(it.activityPrediction, nowTimestamp) } }
    val activityPeakLine = rememberActivityPeakLine(minTimestamp, activityPeak, AapsTheme.elementColors.activity)
    // The profile ISF, before any change by autosens, dynamic ISF or AutoISF (negated like the line)
    val varSensColor = seriesColors.colorFor(SeriesType.VAR_SENSITIVITY)
    val varSensOnRight = SeriesType.VAR_SENSITIVITY in sides.right
    val profileIsfLine = remember(profileIsfY, varSensOnRight, varSensColor) {
        profileIsfY?.let {
            HorizontalDashedLine(
                y = it,
                color = varSensColor.copy(alpha = 0.7f),
                axisPosition = if (varSensOnRight) Axis.Position.Vertical.End else Axis.Position.Vertical.Start
            )
        }
    }
    val clockChanges = rememberClockChangeDecoration(minTimestamp, maxTimestamp)
    val decorations = remember(nowLine, activityPeakLine, profileIsfLine, visibleRangeReporter, clockChanges, inspectLine, geometryReporter) {
        listOfNotNull(nowLine, activityPeakLine, profileIsfLine, visibleRangeReporter, clockChanges, inspectLine, geometryReporter)
    }

    val leftRangeProvider = remember(maxX, axes.left) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = axes.left.min, maxY = axes.left.max)
    }
    val rightRangeProvider = remember(maxX, axes.right) {
        val right = axes.right ?: EMPTY_AXIS
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = right.min, maxY = right.max)
    }
    // Basal range: 0 at top, -basalMaxY at bottom → basal occupies the top BASAL_HEIGHT_FRACTION of the height.
    // Windowed to the visible scroll/zoom range, like the IOB scale above, so basal doesn't
    // stay flattened/clipped when scrolled away from the loaded range's peak.
    val basalMaxY = remember(basalData, processedBasalProfile, processedBasalActual, processedShadowBasal, visibleMinX, visibleMaxX) {
        if (basalData == null || basalData.maxBasal <= 0.0) return@remember 1.0
        // Profile/actual are stored sparsely (only at rate changes — see rebuildBasalGraph), so a
        // zoom window entirely inside one constant segment (e.g. an extended zero-temp) can contain
        // zero literal points for either series. Deliberately NOT using windowedY here: its own
        // ifEmpty fallback dumps the *whole loaded day's* values the moment the window has no
        // literal point, which is exactly what over-inflated this axis in the first place. Instead,
        // filter to the window with no fallback, and separately fold in the rate actually in effect
        // at the window start (stepValueAt) for BOTH series — e.g. a high temp basal that started
        // just 1 minute before the window but runs for 2 hours has no literal change-point inside
        // the window either, so without this it would be invisible to this scale computation even
        // though its bar is drawn across the whole visible window (and could overlap IOB if it's
        // the true max) — reflects just this window's real level instead of the whole day's range.
        fun inWindow(x: Double) = visibleMinX == null || visibleMaxX == null || x in visibleMinX..visibleMaxX
        val literalWindowed = processedBasalProfile.filter { inWindow(it.first) }.map { it.second } +
            processedBasalActual.filter { inWindow(it.first) }.map { it.second } +
            processedShadowBasal.filter { inWindow(it.first) }.map { it.second }
        val hiddenValues = listOfNotNull(
            visibleMinX?.let { stepValueAt(processedBasalProfile, it) },
            visibleMinX?.let { stepValueAt(processedBasalActual, it) },
            visibleMinX?.let { stepValueAt(processedShadowBasal, it) }
        )
        val windowedAbsMax = (literalWindowed + hiddenValues).map { -it }.maxOrNull()
        (windowedAbsMax?.takeIf { it > 0.0 } ?: hiddenValues.maxOfOrNull { -it } ?: basalData.maxBasal) / BASAL_HEIGHT_FRACTION
    }
    val basalRangeProvider = remember(maxX, basalMaxY) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = -basalMaxY, maxY = 0.0)
    }

    // Build chart layers
    val leftLayer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(leftLines),
        rangeProvider = leftRangeProvider,
        verticalAxisPosition = Axis.Position.Vertical.Start
    )
    val rightLayer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(rightLines),
        rangeProvider = rightRangeProvider,
        verticalAxisPosition = Axis.Position.Vertical.End
    )
    val basalLayer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(basalLines),
        rangeProvider = basalRangeProvider,
        verticalAxisPosition = Axis.Position.Vertical.End
    )

    // Labels only where the series has values: not on the room above IOB left for basal, not on the
    // part of an axis that was stretched to put its zero next to the other axis' zero, and none at all
    // on an axis whose series has no values
    val startAxisItemPlacer = remember(leftRange) {
        leftRange?.let { NiceVerticalAxisItemPlacer(labelMin = it.min, labelMax = it.max) } ?: NoLabelsAxisItemPlacer
    }
    val endAxisItemPlacer = remember(rightRange) {
        rightRange?.let { NiceVerticalAxisItemPlacer(labelMin = it.min, labelMax = it.max) } ?: NoLabelsAxisItemPlacer
    }

    // Rebuilt only when the data or the axes change, not on every scroll step
    val axisScale = AxisScale(leftRangeProvider, rightRangeProvider, basalRangeProvider, startAxisItemPlacer, endAxisItemPlacer)
    val modelInput = ModelInput(
        leftSpecs, rightSpecs, processedBasalProfile, processedBasalActual, processedShadowBasal, hasBasalLayer, hasRightAxis, rightFirst, maxX, axisScale,
        maxManualBolus = processedIobTreatments.maxManualBolus,
        maxCarbs = processedCarbs.maxOfOrNull { it.second }
    )
    val latestModelInput by rememberUpdatedState(modelInput)
    // One transaction after the other, and none is cancelled half-way. When the input changes during a
    // transaction, the newest input waits for it, and the inputs in between are skipped. An effect keyed
    // on the input cancelled the running transaction instead. When that came just after Vico had
    // stopped the animation of the transaction before, the lines stayed where the animation stopped,
    // part-way between the old and the new scale, while the labels showed the new scale. If the next
    // input was equal to the last finished one (axes going A, B, A), Vico skipped it as a repeat, and
    // the lines stayed like that.
    LaunchedEffect(modelProducer) {
        snapshotFlow { latestModelInput }.collect { input ->
            // Always populate the model — even with no data / no real time range — so the chart frame
            // (axes, grid, now-line) renders the empty-state normalizer series instead of staying blank.
            // (Matches TreatmentBeltGraphCompose.) When data is absent the processed* lists are empty,
            // so only the normalizer is emitted; the left range provider anchors an empty 0..1 axis.
            // Each layer gets the normalizer first and then its series in the exact order of its specs,
            // so the line styles (built from the same lists) map 1:1 to the series. The normalizer keeps
            // the maxPointSize the same across all charts, see leftLines.
            val normalizer = normalizerX(input.maxX) to NORMALIZER_Y
            val left = listOf(normalizer) + input.leftSpecs.map { it.x to it.y }
            val end = when {
                input.hasBasalLayer -> listOfNotNull(
                    if (input.basalActual.size >= 2) input.basalActual.map { it.first } to input.basalActual.map { it.second } else EMPTY_BASAL,
                    if (input.basalProfile.size >= 2) input.basalProfile.map { it.first } to input.basalProfile.map { it.second } else EMPTY_BASAL,
                    // the same condition as the third line style in basalLines
                    if (input.basalShadow.size >= 2) input.basalShadow.map { it.first } to input.basalShadow.map { it.second } else null
                )

                input.hasRightAxis  -> listOf(normalizer) + input.rightSpecs.map { it.x to it.y }
                else                -> null
            }
            // The same order as the layers handed to the chart below
            val layerSeries = if (end != null && input.rightFirst) listOf(end, left) else listOfNotNull(left, end)
            modelProducer.runTransaction {
                for (seriesOfLayer in layerSeries) lineModel { seriesOfLayer.forEach { (x, y) -> series(x = x, y = y) } }

                // Forces Vico to reprocess this transaction even when the series data above is
                // identical to last time (see AXIS_SCALE_KEY doc) — otherwise scrolling/zooming
                // would re-submit the same partials and get silently skipped, never picking up the
                // updated range providers.
                extras {
                    it[AXIS_SCALE_KEY] = input.axisScale
                    input.maxManualBolus?.let { max -> it[MAX_MANUAL_BOLUS_KEY] = max }
                    input.maxCarbs?.let { max -> it[MAX_CARBS_KEY] = max }
                }
            }
        }
    }

    // Variable sensitivity is drawn negated (see invertIsf), so the labels of its axis negate it back
    val decimalFormatter = LocalDecimalFormatter.current
    val isfFormatter = remember(decimalFormatter) { isfAxisFormatter(decimalFormatter) }
    val startAxisLabel = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        minWidth = TextComponent.MinWidth.fixed(30.dp)
    )
    val startAxisGuideline = LineComponent(fill = Fill(AapsTheme.generalColors.graphGrid))
    val startAxis =
        if (SeriesType.VAR_SENSITIVITY in sides.left) VerticalAxis.rememberStart(
            valueFormatter = isfFormatter, itemPlacer = startAxisItemPlacer, label = startAxisLabel, guideline = startAxisGuideline, size = GRAPH_AXIS_SIZE
        )
        else VerticalAxis.rememberStart(itemPlacer = startAxisItemPlacer, label = startAxisLabel, guideline = startAxisGuideline, size = GRAPH_AXIS_SIZE)
    // The right axis has its labels inside the plot, in the color of its series, and no line, tick or
    // grid of its own. So it takes no width, and the hour lines stay under those of the BG graph.
    val endAxisLabel = rememberTextComponent(style = TextStyle(color = seriesColors.colorFor(sides.right.firstOrNull() ?: SeriesType.COB)))
    val endAxis =
        if (varSensOnRight) VerticalAxis.rememberEnd(
            valueFormatter = isfFormatter,
            line = null,
            label = endAxisLabel,
            horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
            tick = null,
            guideline = null,
            itemPlacer = endAxisItemPlacer
        )
        else VerticalAxis.rememberEnd(
            line = null,
            label = endAxisLabel,
            horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
            tick = null,
            guideline = null,
            itemPlacer = endAxisItemPlacer
        )
    // No time labels: the BG graph above shows them, and the hour lines stay aligned with it.
    // This keeps the graphs below BG compact.
    val bottomAxis = HorizontalAxis.rememberBottom(
        itemPlacer = bottomAxisItemPlacer,
        label = null,
        tick = null,
        guideline = LineComponent(fill = Fill(AapsTheme.generalColors.graphGrid))
    )

    // Press and hold works here too. The marker draws nothing, it only hands the time under the
    // finger to the shared inspect state; the line, the dot and the pill are drawn elsewhere.
    val inspectMarker = remember(inspect) { inspect?.let { SilentInspectMarker } }
    val defaultMarkerController = CartesianMarkerController.rememberShowOnPress()
    val markerController = inspect?.controller ?: defaultMarkerController

    val layers = when {
        hasBasalLayer  -> listOf(leftLayer, basalLayer)
        !hasRightAxis  -> listOf(leftLayer)
        rightFirst     -> listOf(rightLayer, leftLayer)
        else           -> listOf(leftLayer, rightLayer)
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            *layers.toTypedArray(),
            startAxis = startAxis,
            endAxis = endAxis.takeIf { rightRange != null },
            bottomAxis = bottomAxis, decorations = decorations, getXStep = { _, _, _ -> 1.0 },
            marker = inspectMarker, markerVisibilityListener = inspect?.visibilityListener, markerController = markerController
        ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth(),
        scrollState = scrollState, zoomState = zoomState
    )
}

/** The two series a basal layer always needs, for a basal with fewer than two points. */
private val EMPTY_BASAL = listOf(0.0, 1.0) to listOf(0.0, 0.0)

/** The line style of one series. */
private fun lineFor(slot: SeriesSlot, colors: SeriesColors, iobStyles: IobLineStyles, cobStyles: CobLineStyles, extra: ExtraLineStyles): LineCartesianLayer.Line = when (slot) {
    is SeriesSlot.DeviationLine -> createDeviationLine(slot.type)
    SeriesSlot.IobLine          -> iobStyles.iobLine
    SeriesSlot.SmallSmb         -> iobStyles.smallSmbLine
    SeriesSlot.MediumSmb        -> iobStyles.mediumSmbLine
    SeriesSlot.LargeSmb         -> iobStyles.largeSmbLine
    SeriesSlot.NormalBolus      -> iobStyles.bolusLine
    SeriesSlot.ExtBolus         -> iobStyles.extBolusLine
    SeriesSlot.CobLine          -> cobStyles.cobLine
    SeriesSlot.FailoverDots     -> cobStyles.failoverDotsLine
    SeriesSlot.CarbsMarker      -> cobStyles.carbsLine
    SeriesSlot.DevSlopeMin      -> createDevSlopeMinLine()
    SeriesSlot.ActivityOverlay  -> createSeriesLine(SeriesType.ACTIVITY, colors)
    is SeriesSlot.SimpleLine    -> createSeriesLine(slot.type, colors)
    SeriesSlot.ShadowIobLine    -> extra.shadowIobLine
    SeriesSlot.ShadowSmb        -> extra.shadowSmbLine
    is SeriesSlot.DevCarbsRun   -> createDevCarbsLine(slot.positive)
    is SeriesSlot.AutoIsfFactor -> createAutoIsfLine(slot.line, extra.autoIsfAppliedColor)
    is SeriesSlot.MpcValue      -> createMpcLine(slot.line, extra.mpcColors)
    is SeriesSlot.PredictedDeviation -> createPredictedDeviationLine(
        when (slot.curve) {
            PredictedDeviationCurve.IOB -> extra.iobPredictionColor
            PredictedDeviationCurve.COB -> extra.cobPredictionColor
            PredictedDeviationCurve.UAM -> extra.uamPredictionColor
        }
    )
}

/** The line styles of the series this fork adds: the shadow loop, AutoISF, MPC and the expected deviations. */
internal data class ExtraLineStyles(
    val shadowIobLine: LineCartesianLayer.Line,
    val shadowSmbLine: LineCartesianLayer.Line,
    val shadowBasalLine: LineCartesianLayer.Line,
    /** The applied AutoISF factor: white on the dark theme, as the text it is drawn over */
    val autoIsfAppliedColor: Color,
    val mpcColors: MpcColors,
    /** Expected deviations: the colour of their prediction line on the BG graph */
    val iobPredictionColor: Color,
    val cobPredictionColor: Color,
    val uamPredictionColor: Color
)

@Composable
private fun rememberExtraLineStyles(): ExtraLineStyles {
    val shadowIobColor = AapsTheme.generalColors.shadowIob
    val shadowBasalColor = AapsTheme.generalColors.shadowBasal
    val autoIsfAppliedColor = MaterialTheme.colorScheme.onSurface
    // MPC meal values: in the colours of the MPC prediction lines on the BG graph
    val mpcColors = MpcColors(AapsTheme.generalColors.mpcMealPrediction, AapsTheme.generalColors.mpcFalseStartPrediction, AapsTheme.generalColors.mpcExpectedPrediction)
    val iobPredictionColor = AapsTheme.generalColors.iobPrediction
    val cobPredictionColor = AapsTheme.generalColors.cobPrediction
    val uamPredictionColor = AapsTheme.generalColors.uamPrediction
    return remember(shadowIobColor, shadowBasalColor, autoIsfAppliedColor, mpcColors, iobPredictionColor, cobPredictionColor, uamPredictionColor) {
        ExtraLineStyles(
            shadowIobLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(shadowIobColor)),
                stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
                areaFill = null,
                interpolator = Square
            ),
            shadowSmbLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(shadowIobColor), shape = TriangleShape), size = 10.dp)
                )
            ),
            shadowBasalLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(shadowBasalColor)),
                stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
                areaFill = null,
                interpolator = Square
            ),
            autoIsfAppliedColor = autoIsfAppliedColor,
            mpcColors = mpcColors,
            iobPredictionColor = iobPredictionColor,
            cobPredictionColor = cobPredictionColor,
            uamPredictionColor = uamPredictionColor
        )
    }
}

/** Axis labels for variable sensitivity, which is drawn negated: the label negates the value back. */
private fun isfAxisFormatter(decimalFormatter: DecimalFormatter): CartesianValueFormatter = CartesianValueFormatter { _, value, _ ->
    val isf = if (value == 0.0) 0.0 else -value
    // mg/dL ISFs are tens, mmol/L ones a few units
    if (abs(isf) >= 10.0) decimalFormatter.to0Decimal(isf) else decimalFormatter.to1Decimal(isf)
}

// =========================================================================
// Series slot identifiers (for matching line styles to data series)
// =========================================================================

/**
 * One drawn series: its x/y data plus the slot that selects its line style. [countsForRange] is
 * false for a series drawn in the units of another one (the activity overlay in IOB units).
 */
internal class SeriesSpec(val x: List<Double>, val y: List<Double>, val slot: SeriesSlot, val countsForRange: Boolean = true)

/** Adds [points] as one series, unless there are none. */
private fun MutableList<SeriesSpec>.addPoints(points: List<Pair<Double, Double>>, slot: SeriesSlot, countsForRange: Boolean = true) {
    if (points.isNotEmpty()) add(SeriesSpec(points.map { it.first }, points.map { it.second }, slot, countsForRange))
}

internal sealed class SeriesSlot {
    data class DeviationLine(val type: DeviationType) : SeriesSlot()
    data object IobLine : SeriesSlot()
    data object SmallSmb : SeriesSlot()
    data object MediumSmb : SeriesSlot()
    data object LargeSmb : SeriesSlot()
    data object NormalBolus : SeriesSlot()
    data object ExtBolus : SeriesSlot()
    data object CobLine : SeriesSlot()
    data object FailoverDots : SeriesSlot()
    data object CarbsMarker : SeriesSlot()
    data class SimpleLine(val type: SeriesType) : SeriesSlot()
    data object DevSlopeMin : SeriesSlot()
    data object ActivityOverlay : SeriesSlot()
    data object ShadowIobLine : SeriesSlot()
    data object ShadowSmb : SeriesSlot()
    data class DevCarbsRun(val positive: Boolean) : SeriesSlot()
    data class AutoIsfFactor(val line: AutoIsfLine) : SeriesSlot()
    data class MpcValue(val line: MpcLine) : SeriesSlot()
    data class PredictedDeviation(val curve: PredictedDeviationCurve) : SeriesSlot()
}

// =========================================================================
// Data processing helpers
// =========================================================================

/**
 * Y-values of [points] restricted to [visibleMinX]..[visibleMaxX], falling back to the full
 * (unwindowed) values when the visible window currently has no points (e.g. scrolled into a
 * future gap with no data).
 */
internal fun windowedY(points: List<Pair<Double, Double>>, visibleMinX: Double?, visibleMaxX: Double?): List<Double> {
    fun inWindow(x: Double) = visibleMinX == null || visibleMaxX == null || x in visibleMinX..visibleMaxX
    return points.filter { inWindow(it.first) }.map { it.second }.ifEmpty { points.map { it.second } }
}

/**
 * Value in effect at [x] for a sparse change-point-only step series — [points] only stores a new
 * entry when the value changes (see `rebuildBasalGraph`), so a narrow zoom window can contain zero
 * literal points even though the series has a well-defined value throughout it. The value actually
 * in effect at any [x] is whatever the most recent point at-or-before [x] holds (falls back to the
 * first point if [x] precedes all of them).
 */
internal fun stepValueAt(points: List<Pair<Double, Double>>, x: Double): Double? =
    points.lastOrNull { it.first <= x }?.second ?: points.firstOrNull()?.second

/**
 * The y values of the [series] that count for an axis, inside [visibleMinX]..[visibleMaxX]. When none
 * of them has a point there (e.g. scrolled into a future gap with no data), all their values: the
 * fallback applies to the whole set, so windowed points of one series are never mixed with all the
 * points of another.
 */
internal fun windowedValues(series: List<SeriesSpec>, visibleMinX: Double?, visibleMaxX: Double?): List<Double> {
    fun inWindow(x: Double) = visibleMinX == null || visibleMaxX == null || x in visibleMinX..visibleMaxX
    val counted = series.filter { it.countsForRange }
    return counted.flatMap { spec -> spec.x.indices.filter { inWindow(spec.x[it]) }.map { spec.y[it] } }
        .ifEmpty { counted.flatMap { it.y } }
}

@Suppress("SameParameterValue")
private fun processPoints(points: List<GraphDataPoint>, minTimestamp: Long, minX: Double, maxX: Double): List<Pair<Double, Double>> {
    val pts = points.map { timestampToX(it.timestamp, minTimestamp) to it.value }
    return filterToRange(pts, minX, maxX)
}

/**
 * [processPoints] for a step series that has to reach the right edge of the axis.
 *
 * A step series stores a point only where the value changes (see `rebuildBasalGraph`), plus one
 * closing point at the end of the range it was built for. That closing point is the only thing that
 * carries the line from the last change to the edge, and it is regularly **outside** [maxX]:
 * `OverviewDataCacheImpl.graphEndTime` ends the basal at the newest prediction time, while the axis
 * ends at `TimeRange.toTime` whenever the predictions overlay is off. [filterToRange] then drops it
 * and the line simply stops at the last change - up to an hour short of the edge, because
 * `OverviewData.toTime` is the current time rounded **up** to a whole hour.
 *
 * So the last point is clamped to [maxX] instead of dropped, carrying the value that
 * [stepValueAt] says is in effect there. That is correct however far the producer overshoots, and
 * it cannot fall out of step with the axis again: the producer's end is always at or past [maxX]
 * (equal to it when predictions are on), never before it.
 *
 * Only for step series. On a sampled line (IOB, COB, activity) the same clamp would invent a
 * reading that was never taken.
 */
internal fun processStepPoints(points: List<GraphDataPoint>, minTimestamp: Long, minX: Double, maxX: Double): List<Pair<Double, Double>> {
    val pts = points.map { timestampToX(it.timestamp, minTimestamp) to it.value }.sortedBy { it.first }
    val inRange = filterToRange(pts, minX, maxX)
    if (inRange.isEmpty() || inRange.last().first >= maxX) return inRange
    val edgeValue = stepValueAt(pts, maxX) ?: return inRange
    return inRange + (maxX to edgeValue)
}

/**
 * Process IOB treatment overlays: split SMBs by size, extract boluses and extended boluses.
 * An SMB is sized like a treatment marker ([tierOf]) against the largest SMB of the loaded range,
 * a manual bolus against the largest manual bolus (see [ProcessedIobOverlays.maxManualBolus]).
 */
@Suppress("SameParameterValue")
internal fun processIobOverlays(data: TreatmentGraphData, minTimestamp: Long, minX: Double, maxX: Double): ProcessedIobOverlays {
    // SMBs of the loaded range, not only the visible window: a marker must not change size while the graph is scrolled
    val smbs = data.boluses.filter { it.bolusType == BolusType.SMB && timestampToX(it.timestamp, minTimestamp) in minX..maxX }
    // SMBs stand on the zero line, so the size cannot come from the y of the point: one series per size
    val smbAmounts = smbs.map { it.amount }
    val smbsByTier = smbs.groupBy({ tierOf(it.amount, smbAmounts) }) { timestampToX(it.timestamp, minTimestamp) to 0.0 }
    val smallSmbs = smbsByTier[MarkerTier.SMALL].orEmpty()
    val mediumSmbs = smbsByTier[MarkerTier.MEDIUM].orEmpty()
    val largeSmbs = smbsByTier[MarkerTier.LARGE].orEmpty()

    val normalBoluses = data.boluses.filter { it.bolusType == BolusType.NORMAL }
    val bolusFiltered = if (normalBoluses.isNotEmpty()) {
        filterToRange(normalBoluses.map { timestampToX(it.timestamp, minTimestamp) to it.amount }, minX, maxX)
    } else emptyList()

    val extBoluses = data.extendedBoluses.mapNotNull { eb ->
        val startX = timestampToX(eb.timestamp, minTimestamp)
        val endX = timestampToX(eb.timestamp + eb.duration, minTimestamp)
        if (endX < minX || startX > maxX) return@mapNotNull null
        val clampedStart = startX.coerceIn(minX, maxX)
        val clampedEnd = endX.coerceIn(minX, maxX)
        if (clampedStart == clampedEnd) return@mapNotNull null
        Triple(clampedStart, clampedEnd, eb.amount)
    }

    return ProcessedIobOverlays(
        filterToRange(smallSmbs, minX, maxX),
        filterToRange(mediumSmbs, minX, maxX),
        filterToRange(largeSmbs, minX, maxX),
        bolusFiltered, extBoluses
    )
}

internal data class ProcessedIobOverlays(
    val smallSmbs: List<Pair<Double, Double>>,
    val mediumSmbs: List<Pair<Double, Double>>,
    val largeSmbs: List<Pair<Double, Double>>,
    /** (x, amount) of each manual bolus, drawn at its amount and sized by it, see [TieredPointProvider]. */
    val normalBoluses: List<Pair<Double, Double>>,
    val extBoluses: List<Triple<Double, Double, Double>>
) {

    /** The largest manual bolus of the loaded range, which the sizes of the manual bolus markers are measured against. */
    val maxManualBolus: Double? get() = normalBoluses.maxOfOrNull { it.second }

    companion object {

        val EMPTY = ProcessedIobOverlays(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

// =========================================================================
// Line styles
// =========================================================================

/** Color map for simple line series */
data class SeriesColors(
    val iob: Color,
    val absIob: Color,
    val cob: Color,
    val bgi: Color,
    val deviations: Color,
    val sensitivity: Color,
    val varSensitivity: Color,
    val devSlope: Color,
    val heartRate: Color,
    val steps: Color,
    val activity: Color,
    val mpc: Color
) {

    fun colorFor(type: SeriesType): Color = when (type) {
        SeriesType.IOB             -> iob
        SeriesType.ABS_IOB         -> absIob
        SeriesType.COB             -> cob
        SeriesType.BGI             -> bgi
        SeriesType.DEVIATIONS      -> deviations
        SeriesType.SENSITIVITY     -> sensitivity
        SeriesType.VAR_SENSITIVITY -> varSensitivity
        SeriesType.DEV_SLOPE       -> devSlope
        SeriesType.DEV_CARBS       -> deviations
        SeriesType.AUTOISF         -> varSensitivity
        SeriesType.MEAL_CHANCE     -> mpc
        SeriesType.MEAL_CARBS      -> mpc
        SeriesType.HEART_RATE      -> heartRate
        SeriesType.STEPS           -> steps
        SeriesType.ACTIVITY        -> activity
        SeriesType.PREDICTIONS     -> activity // unused — PREDICTIONS is a BG overlay flag, not a secondary series
    }
}

@Composable
fun rememberSeriesColors(): SeriesColors {
    val iobColor = AapsTheme.generalColors.iobPrediction
    val cobColor = AapsTheme.generalColors.cobPrediction
    val onSurface = MaterialTheme.colorScheme.onSurface
    val mpcColor = AapsTheme.generalColors.mpcExpectedPrediction
    return remember(iobColor, cobColor, onSurface, mpcColor) {
        SeriesColors(
            iob = iobColor,                         // #1e88e5 blue (matches @color/iob)
            absIob = iobColor,                      // same blue as IOB
            cob = cobColor,                         // #FB8C00 orange (matches @color/cob)
            bgi = Color(0xFF00EEEE),                // cyan (matches @color/bgi)
            deviations = Color(0xFF00EEEE),         // cyan (matches @color/bgi — legacy uses per-type colors)
            sensitivity = onSurface,                // #000000 light / #FFFFFF dark (matches @color/ratio)
            varSensitivity = onSurface,             // same as sensitivity
            devSlope = Color(0xFFFFFF00),            // yellow (matches @color/devSlopePos)
            heartRate = Color(0xFFFFFF66),           // pale yellow (matches @color/heartRate #FFFFFF66)
            steps = Color(0xFF66FFB8),              // mint green (matches @color/steps)
            activity = Color(0xFFD3F166),           // lime green (matches @color/activity)
            mpc = mpcColor                          // the expected MPC prediction line: near black light / near white dark
        )
    }
}

/** DevSlope min line — magenta, no fill (matches @color/devSlopeNeg #FF00FF) */
private fun createDevSlopeMinLine(): LineCartesianLayer.Line {
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(devSlopeMinColor())),
        areaFill = null
    )
}
/** The falling DEVSLOPE line (magenta), also used for its value in the pop-up. */
internal fun devSlopeMinColor(): Color = Color(0xFFFF00FF)

/** The colours of the DEV carbs runs, also used for their value in the pop-up. */
internal fun devCarbsPositiveColor(): Color = DEVIATION_COLOR_POSITIVE
internal fun devCarbsNegativeColor(): Color = DEVIATION_COLOR_NEGATIVE

/**
 * The points of [type] as the runs to draw: steps as bars ([barPoints]), heart rate as a line broken
 * where readings are missing ([splitAtGaps]), every other series as one run. DEV carbs come as runs
 * already.
 */
internal fun shapedRuns(type: SeriesType, points: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> =
    when (type) {
        SeriesType.STEPS      -> listOf(barPoints(points))
        SeriesType.HEART_RATE -> splitAtGaps(points)
        else                  -> listOf(points)
    }

/**
 * A DEV carbs run is positive (carbs that explain a rise) when its point furthest from zero is above
 * zero. A run can go a little past zero for one reading, when a move back is not a turn yet.
 */
internal fun isPositiveRun(run: List<Pair<Double, Double>>): Boolean = (run.maxByOrNull { abs(it.second) }?.second ?: 0.0) > 0.0

/**
 * A DEV carbs run: the running total as a line, filled down (or up) to zero. Green for a rise the
 * insulin does not explain, red for a drop, the same colours as the DEV bars.
 */
private fun createDevCarbsLine(positive: Boolean): LineCartesianLayer.Line {
    val color = if (positive) DEVIATION_COLOR_POSITIVE else DEVIATION_COLOR_NEGATIVE
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.3f))),
        // Straight lines between the points: a curve could cross zero between two points of one run
        interpolator = LineCartesianLayer.Interpolator.Sharp
    )
}

/** The AutoISF axis shows at least 0.9 to 1.1. */
internal const val AUTOISF_MIN_DEVIATION = 0.1

/**
 * One line of the AutoISF graph: each factor in its own colour with a small dot at each loop run, and
 * the factor that was applied in bold in the text colour, under the others. The labels are used in the
 * values pop-up.
 */
internal enum class AutoIsfLine(val color: Color?, val label: TextRef) {
    ACCE(Color(0xFFFFA726), CoreUiStrings.autoisf_acce_shortname),     // acceleration of BG: speeding up or braking
    BG(Color(0xFF29B6F6), CoreUiStrings.autoisf_bg_shortname),         // how far BG is from the target
    PP(Color(0xFF66BB6A), CoreUiStrings.autoisf_pp_shortname),         // rise after a meal
    DURA(Color(0xFFEC407A), CoreUiStrings.autoisf_dura_shortname),     // stuck high
    APPLIED(null, CoreUiStrings.autoisf_applied_shortname);            // the factor that was used

    fun valueAt(point: AutoIsfFactorPoint): Double = when (this) {
        ACCE    -> point.acce
        BG      -> point.bg
        PP      -> point.pp
        DURA    -> point.dura
        APPLIED -> point.applied
    }
}

private fun createAutoIsfLine(line: AutoIsfLine, appliedColor: Color): LineCartesianLayer.Line {
    val color = line.color ?: appliedColor
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = if (line == AutoIsfLine.APPLIED) 3.dp else 1.5.dp),
        areaFill = null,
        // A small dot per loop run on the factors, so a factor that equals the applied one is still seen
        pointProvider = if (line == AutoIsfLine.APPLIED) null else LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = CircleShape), size = AUTOISF_DOT_SIZE)
        )
    )
}

/** The colours of the MPC meal lines: if a meal, if no meal, and the expected / weighted value. */
internal data class MpcColors(val meal: Color, val noMeal: Color, val main: Color)

/**
 * One line of the MPC meal graphs. [MEAL_CHANCE][SeriesType.MEAL_CHANCE]: the chance from the rise alone and with the room
 * above the target (the one the plan uses). [MEAL_CARBS][SeriesType.MEAL_CARBS]: carbs still to come if a meal, if no meal,
 * and expected. The [main] line of each is bold.
 */
internal enum class MpcLine(val type: SeriesType, val main: Boolean) {
    CHANCE_FROM_RISE(SeriesType.MEAL_CHANCE, false),
    CHANCE_WITH_ROOM(SeriesType.MEAL_CHANCE, true),
    CARBS_IF_MEAL(SeriesType.MEAL_CARBS, false),
    CARBS_IF_NO_MEAL(SeriesType.MEAL_CARBS, false),
    CARBS_EXPECTED(SeriesType.MEAL_CARBS, true);

    fun valueAt(point: MpcPoint): Double = when (this) {
        CHANCE_FROM_RISE -> point.mealChance
        CHANCE_WITH_ROOM -> point.weightedMealChance
        CARBS_IF_MEAL    -> point.carbsIfMeal
        CARBS_IF_NO_MEAL -> point.carbsIfNoMeal
        CARBS_EXPECTED   -> point.carbsExpected
    }

    fun color(colors: MpcColors): Color = when (this) {
        CHANCE_FROM_RISE, CARBS_IF_MEAL -> colors.meal
        CARBS_IF_NO_MEAL                -> colors.noMeal
        CHANCE_WITH_ROOM, CARBS_EXPECTED -> colors.main
    }
}

private fun createMpcLine(line: MpcLine, colors: MpcColors): LineCartesianLayer.Line {
    val color = line.color(colors)
    // Meal chance as dots (one per loop run), so it is not mixed up with the carb lines on a shared graph
    if (line.type == SeriesType.MEAL_CHANCE) return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = CircleShape), size = if (line.main) MPC_DOT_SIZE_MAIN else MPC_DOT_SIZE)
        )
    )
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = if (line.main) 3.dp else 1.5.dp),
        areaFill = null,
        // Each loop run is one value: no curve between them
        interpolator = Square
    )
}

/** The meal chance dots: the chance the plan used larger than the chance from the rise alone. */
private val MPC_DOT_SIZE_MAIN = 6.dp
private val MPC_DOT_SIZE = 4.dp

/** The dots on the AutoISF factor lines. */
private val AUTOISF_DOT_SIZE = 4.dp

/** Bars: a [Square] step line with a solid fill down to zero. */
private fun createBarLine(color: Color): LineCartesianLayer.Line = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(color)),
    areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.5f))),
    interpolator = Square
)


/** Create a line style for a given series type, matching legacy rendering */
fun createSeriesLine(type: SeriesType, colors: SeriesColors): LineCartesianLayer.Line {
    val color = colors.colorFor(type)
    return when (type) {
        // Step function with area fill (like IOB)
        SeriesType.ABS_IOB                                                       -> LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = LineCartesianLayer.AreaFill.single(
                Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
            ),
            interpolator = Square
        )
        // Line only, no fill
        SeriesType.DEV_SLOPE, SeriesType.SENSITIVITY, SeriesType.VAR_SENSITIVITY -> LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = null
        )
        // A line, no fill; broken where readings are missing (see shapedRuns)
        SeriesType.HEART_RATE                                                    -> LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = null
        )
        // A bar per period, like the DEV bars (see barPoints)
        SeriesType.STEPS                                                         -> createBarLine(color)
        // Default: smooth line with gradient area fill
        else                                                                     -> LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = LineCartesianLayer.AreaFill.single(
                Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
            )
        )
    }
}

// =========================================================================
// IOB line styles (bolus markers)
// =========================================================================

data class IobLineStyles(
    val iobLine: LineCartesianLayer.Line,
    val smallSmbLine: LineCartesianLayer.Line,
    val mediumSmbLine: LineCartesianLayer.Line,
    val largeSmbLine: LineCartesianLayer.Line,
    val bolusLine: LineCartesianLayer.Line,
    val extBolusLine: LineCartesianLayer.Line
)

/**
 * One size for the bolus and carbs labels on the graphs. Vico gives a label no more width than the
 * gap to its neighbour in the same series and ends it with "..." when the text is wider. The bolus
 * labels used 16 sp and lost that race far more often than the 14 sp carbs labels, so both use the
 * smaller size now.
 */
private val TREATMENT_LABEL_FONT_SIZE = 14.sp

@Composable
fun rememberIobLineStyles(): IobLineStyles {
    val iobColor = AapsTheme.generalColors.iobPrediction
    val smbColor = AapsTheme.elementColors.insulin
    val extBolusColor = AapsTheme.elementColors.extendedBolus
    val decimalFormatter = LocalDecimalFormatter.current

    val bolusLabelComponent = remember(smbColor) {
        TextComponent(textStyle = TextStyle(color = smbColor, fontSize = TREATMENT_LABEL_FONT_SIZE))
    }
    val bolusValueFormatter = remember {
        CartesianValueFormatter { _, value, _ -> formatBolusLabel(value, decimalFormatter) }
    }
    val extBolusLabelComponent = remember(extBolusColor) {
        TextComponent(textStyle = TextStyle(color = extBolusColor, fontSize = TREATMENT_LABEL_FONT_SIZE))
    }
    val extBolusValueFormatter = remember {
        CartesianValueFormatter { _, value, _ -> formatBolusLabel(value, decimalFormatter) }
    }

    return remember(iobColor, smbColor, extBolusColor, bolusLabelComponent, bolusValueFormatter, extBolusLabelComponent, extBolusValueFormatter) {
        IobLineStyles(
            iobLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(iobColor)),
                areaFill = LineCartesianLayer.AreaFill.single(
                    Fill(Brush.verticalGradient(listOf(iobColor.copy(alpha = 1f), Color.Transparent)))
                ),
                interpolator = Square
            ),
            smallSmbLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(smbColor), shape = TriangleShape), size = 10.dp)
                )
            ),
            mediumSmbLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(smbColor), shape = TriangleShape), size = 16.dp)
                )
            ),
            largeSmbLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(smbColor), shape = TriangleShape), size = 22.dp)
                )
            ),
            // A manual bolus points down, with its tip on its amount, the same as on the BG graph. SMBs
            // point up, so a manual bolus among them still reads as one. Sized as on the BG graph
            bolusLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = TieredPointProvider(InvertedTriangleShape, smbColor, MAX_MANUAL_BOLUS_KEY),
                dataLabel = bolusLabelComponent,
                dataLabelPosition = Position.Vertical.Top,
                dataLabelValueFormatter = bolusValueFormatter
            ),
            extBolusLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(extBolusColor)),
                areaFill = null,
                dataLabel = extBolusLabelComponent,
                dataLabelPosition = Position.Vertical.Top,
                dataLabelValueFormatter = extBolusValueFormatter
            )
        )
    }
}

// =========================================================================
// COB line styles (carbs markers)
// =========================================================================

data class CobLineStyles(
    val cobLine: LineCartesianLayer.Line,
    val failoverDotsLine: LineCartesianLayer.Line,
    val carbsLine: LineCartesianLayer.Line
)

@Composable
fun rememberCobLineStyles(): CobLineStyles {
    val cobColor = AapsTheme.generalColors.cobPrediction
    val carbsColor = AapsTheme.elementColors.carbs
    val decimalFormatter = LocalDecimalFormatter.current

    val carbsLabelComponent = remember(carbsColor) {
        TextComponent(textStyle = TextStyle(color = carbsColor, fontSize = TREATMENT_LABEL_FONT_SIZE))
    }
    val carbsValueFormatter = remember {
        CartesianValueFormatter { _, value, _ -> formatCarbsLabel(value, decimalFormatter) }
    }

    return remember(cobColor, carbsColor, carbsLabelComponent, carbsValueFormatter) {
        CobLineStyles(
            cobLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(cobColor)),
                areaFill = LineCartesianLayer.AreaFill.single(
                    Fill(Brush.verticalGradient(listOf(cobColor.copy(alpha = 1f), Color.Transparent)))
                ),
                interpolator = AdaptiveStep
            ),
            failoverDotsLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(cobColor), shape = CircleShape), size = 6.dp)
                )
            ),
            // Sized as on the BG graph
            carbsLine = LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                areaFill = null,
                pointProvider = TieredPointProvider(InvertedTriangleShape, carbsColor, MAX_CARBS_KEY),
                dataLabel = carbsLabelComponent,
                dataLabelPosition = Position.Vertical.Top,
                dataLabelValueFormatter = carbsValueFormatter
            )
        )
    }
}

// =========================================================================
// Label formatters (shared with IOB/COB graph composables)
// =========================================================================

/** Formats bolus amount: 1.0->"1", 1.2->"1.2", 0.8->".8" (drop leading zero) */
private fun formatBolusLabel(value: Double, decimalFormatter: DecimalFormatter): String {
    if (value == 0.0) return ""
    val formatted = decimalFormatter.to2Decimal(value).trimEnd('0').trimEnd('.').trimEnd(',')
    return if (formatted.startsWith("0.")) formatted.substring(1) else formatted
}

/** Formats carbs amount: 45.0->"45", 7.5->"7.5", 0.0->"" */
private fun formatCarbsLabel(value: Double, decimalFormatter: DecimalFormatter): String {
    if (value == 0.0) return ""
    return decimalFormatter.to1Decimal(value).trimEnd('0').trimEnd('.').trimEnd(',')
}

// =========================================================================
// Deviation line styles (per-type step lines with gradient fill)
// =========================================================================

private val DEVIATION_COLOR_POSITIVE = Color(0xC000FF00) // green (matches @color/deviationGreen)
private val DEVIATION_COLOR_NEGATIVE = Color(0xC0FF0000) // red (matches @color/deviationRed)
private val DEVIATION_COLOR_EQUAL = Color(0x72000000)    // black (matches @color/deviationBlack)
private val DEVIATION_COLOR_UAM = Color(0xFFC9BD60)      // yellow (matches @color/uam)
private val DEVIATION_COLOR_CSF = Color(0xC8666666)      // grey (matches @color/deviationGrey)

private fun deviationColor(type: DeviationType): Color = when (type) {
    DeviationType.POSITIVE -> DEVIATION_COLOR_POSITIVE
    DeviationType.NEGATIVE -> DEVIATION_COLOR_NEGATIVE
    DeviationType.EQUAL    -> DEVIATION_COLOR_EQUAL
    DeviationType.UAM      -> DEVIATION_COLOR_UAM
    DeviationType.CSF      -> DEVIATION_COLOR_CSF
}

/** Step line with solid area fill and thin stroke, per-deviation-type color */
private fun createDeviationLine(type: DeviationType): LineCartesianLayer.Line {
    val color = deviationColor(type)
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 0.dp),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.5f))),
        interpolator = Square
    )
}

/** The prediction curve an expected deviation belongs to, see `PredictedDeviationsGraphData`. */
internal enum class PredictedDeviationCurve { IOB, COB, UAM }

/** A bar per 5 minutes like [createDeviationLine], lighter, as it is expected and not measured. */
private fun createPredictedDeviationLine(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 0.dp),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.35f))),
        interpolator = Square
    )

/** Processed deviation data split by type for multi-series line rendering */
internal data class ProcessedDeviationLines(
    val allX: List<Double>,
    val series: Map<DeviationType, List<Double>>
)
