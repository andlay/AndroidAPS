package app.aaps.plugins.aps.mpc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MpcComposeContent(private val plugin: MpcPlugin, private val dateUtil: DateUtil) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        val details by plugin.details.collectAsStateWithLifecycle()
        MpcScreen(details, plugin.readableShape(), dateUtil)
    }
}

/** One curve or bar set of a chart. x in minutes from now, y in chart units. */
private class Curve(val points: List<Pair<Double, Double>>, val color: Color, val width: Float = 2f, val fill: Boolean = false, val dashed: Boolean = false, val bars: Boolean = false)

@Composable
fun MpcScreen(details: MpcDetails?, shape: MpcShapeSettings, dateUtil: DateUtil) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        if (details == null) {
            AapsCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(ApsStrings.mpc_no_run_yet), modifier = Modifier.padding(AapsSpacing.large))
            }
        } else {
            DecisionCard(details, dateUtil)
            BgFutureCard(details)
            CarbEffectCard(details)
            OptionsCard(details)
        }
        ShapeCard(shape)
    }
}

/** What the aim over days measured, and what it did with the low line or, in shadow mode, would do */
@Composable
private fun AimLines(a: MpcAimState, d: MpcDetails) {
    Text(
        when {
            a.measured.isNaN()     -> stringResource(ApsStrings.mpc_aim_no_data)
            a.aim == MpcAim.HBA1C  ->
                if (d.mmol) stringResource(ApsStrings.mpc_aim_hba1c_mmol, a.targetHbA1c, a.measured / 18, a.wanted / 18)
                else stringResource(ApsStrings.mpc_aim_hba1c_mgdl, a.targetHbA1c, a.measured, a.wanted)
            else                   -> stringResource(ApsStrings.mpc_aim_trade_line, a.wanted, a.measured)
        }
    )
    if (a.steering) {
        val moved = d.lowLine + d.stepDown - d.setLowLine
        Text(if (d.mmol) stringResource(ApsStrings.mpc_aim_moved_mmol, moved / 18) else stringResource(ApsStrings.mpc_aim_moved_mgdl, moved))
    } else if (!a.measured.isNaN()) {
        Text(
            stringResource(
                when {
                    a.step > 0.05  -> ApsStrings.mpc_aim_shadow_up
                    a.step < -0.05 -> ApsStrings.mpc_aim_shadow_down
                    else           -> ApsStrings.mpc_aim_shadow_hold
                }
            )
        )
    }
}

@Composable
private fun DecisionCard(d: MpcDetails, dateUtil: DateUtil) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(stringResource(ApsStrings.mpc_decision_title, dateUtil.timeString(d.time)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(ApsStrings.mpc_smb_units, d.units), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                when {
                    d.rate == 0.0     -> stringResource(ApsStrings.mpc_temp_zero, d.duration)
                    d.rate > d.basal  -> stringResource(ApsStrings.mpc_temp_high, d.rate, d.duration)
                    else              -> stringResource(ApsStrings.mpc_temp_normal, d.basal)
                },
                style = MaterialTheme.typography.titleSmall
            )
            Text(stringResource(ApsStrings.mpc_meal_chance_line, (d.mealChance * 100).roundToInt(), (d.weightedMealChance * 100).roundToInt()))
            Text(if (d.mmol) stringResource(ApsStrings.mpc_room_mmol, d.room / 18) else stringResource(ApsStrings.mpc_room_mgdl, d.room))
            Text(if (d.mmol) stringResource(ApsStrings.mpc_lines_mmol, d.lowLine / 18, d.highLine / 18) else stringResource(ApsStrings.mpc_lines_mgdl, d.lowLine, d.highLine))
            d.aim?.let { a -> AimLines(a, d) }
            if (d.stepDown > 0) Text(if (d.mmol) stringResource(ApsStrings.mpc_step_down_mmol, d.stepDown / 18) else stringResource(ApsStrings.mpc_step_down_mgdl, d.stepDown))
            Text(
                when {
                    d.impactAge < 0 -> stringResource(ApsStrings.mpc_impact_none)
                    d.afterPeak     -> stringResource(ApsStrings.mpc_impact_after_peak, d.impactAge * 5)
                    else            -> stringResource(ApsStrings.mpc_impact_running, d.impactAge * 5)
                }
            )
            Text(if (d.mmol) stringResource(ApsStrings.mpc_isf_mmol, d.isf / 18, d.aisfFactor) else stringResource(ApsStrings.mpc_isf_mgdl, d.isf, d.aisfFactor))
            Text(stringResource(ApsStrings.mpc_iob_line, d.iob))
        }
    }
}

@Composable
private fun BgFutureCard(d: MpcDetails) {
    val c = AapsTheme.generalColors
    val k = if (d.mmol) 1 / 18.0 else 1.0
    val ahead = { a: DoubleArray -> listOf(0.0 to d.bg * k) + a.mapIndexed { j, v -> (j + 1) * 5.0 to v * k } }
    val all = d.predictedMeal + d.predictedFalseStart + d.bg
    val yMin = min(all.min(), d.lowLine - 10) * k
    val yMax = max(all.max(), d.highLine + 10) * k
    val curves = listOf(
        Curve(ahead(d.predictedFalseStart), c.mpcFalseStartPrediction),
        Curve(ahead(d.predictedMeal), c.mpcMealPrediction),
        Curve(ahead(d.predictedExpected), c.mpcExpectedPrediction, width = 4f)
    )
    ChartCard(
        title = stringResource(ApsStrings.mpc_bg_future_title),
        curves = curves, xMin = 0.0, xMax = 180.0, yMin = yMin, yMax = yMax, xStep = 30.0,
        hLines = listOf(d.lowLine * k to c.bgLow, d.highLine * k to c.bgHigh), decimals = if (d.mmol) 1 else 0,
        legend = listOf(
            stringResource(ApsStrings.mpc_legend_expected) to c.mpcExpectedPrediction,
            stringResource(ApsStrings.mpc_legend_meal) to c.mpcMealPrediction,
            stringResource(ApsStrings.mpc_legend_false_start) to c.mpcFalseStartPrediction,
            stringResource(ApsStrings.mpc_legend_lines) to c.bgLow
        )
    )
}

@Composable
private fun CarbEffectCard(d: MpcDetails) {
    val c = AapsTheme.generalColors
    val k = if (d.mmol) 1 / 18.0 else 1.0
    val past = { a: DoubleArray -> a.mapIndexed { j, v -> -j * 5.0 to v * k }.filter { it.second.isFinite() }.reversed() }
    val ahead = { a: DoubleArray -> a.mapIndexed { j, v -> (j + 1) * 5.0 to v * k } }
    val expected = d.futureMeal.mapIndexed { j, v -> d.weightedMealChance * v + (1 - d.weightedMealChance) * d.futureFalseStart[j] }.toDoubleArray()
    val values = (d.pastDev.filter { it.isFinite() } + d.pastFitted.toList() + d.futureMeal.toList() + d.futureFalseStart.toList()).map { it * k }
    val seen = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    ChartCard(
        title = stringResource(ApsStrings.mpc_carb_effect_title),
        subtitle = stringResource(if (d.mmol) ApsStrings.mpc_carb_effect_unit_mmol else ApsStrings.mpc_carb_effect_unit_mgdl),
        curves = listOf(
            Curve(past(d.pastDev), seen, bars = true),
            Curve(past(d.pastFitted), c.mpcMealPrediction, width = 3f),
            Curve(ahead(d.futureFalseStart), c.mpcFalseStartPrediction, dashed = true),
            Curve(ahead(d.futureMeal), c.mpcMealPrediction, dashed = true),
            Curve(ahead(expected), c.mpcExpectedPrediction, width = 3f)
        ),
        xMin = -120.0, xMax = 180.0, yMin = min(0.0, values.minOrNull() ?: 0.0), yMax = max(1.0 * k, values.maxOrNull() ?: 1.0), xStep = 60.0,
        hLines = emptyList(), decimals = if (d.mmol) 2 else 0, nowLine = true,
        legend = listOf(
            stringResource(ApsStrings.mpc_legend_seen) to seen,
            stringResource(ApsStrings.mpc_legend_model) to c.mpcMealPrediction,
            stringResource(ApsStrings.mpc_legend_expected) to c.mpcExpectedPrediction,
            stringResource(ApsStrings.mpc_legend_false_start) to c.mpcFalseStartPrediction
        )
    )
}

@Composable
private fun OptionsCard(d: MpcDetails) {
    if (d.options.isEmpty()) return
    val best = d.options.first().cost
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(stringResource(ApsStrings.mpc_options_title), style = MaterialTheme.typography.titleMedium)
            d.options.forEachIndexed { i, o ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        when (o.mode) {
                            1    -> stringResource(ApsStrings.mpc_option_zero, o.units)
                            -1   -> stringResource(ApsStrings.mpc_option_high, o.units)
                            else -> stringResource(ApsStrings.mpc_option_normal, o.units)
                        },
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        if (i == 0) stringResource(ApsStrings.mpc_option_chosen)
                        else stringResource(ApsStrings.mpc_option_cost, if (best > 0) (o.cost / best * 100).roundToInt() else 100),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeCard(shape: MpcShapeSettings) {
    val c = AapsTheme.generalColors
    val (a, b) = remember(shape.peakPosition, shape.halfWidth) { MpcShapeSettings.exponents(shape.peakPosition, shape.halfWidth) }
    val minutes = shape.typicalMinutes
    val n = 120
    val effect = (0..n).map { i -> i * minutes / n to MpcShapeSettings.unitPulse(i.toDouble() / n, a, b) }
    val total = effect.sumOf { it.second }
    var run = 0.0
    val taken = effect.map { (x, y) -> run += y; x to run / total }
    val takenUpColor = c.bgInRange
    ChartCard(
        title = stringResource(ApsStrings.mpc_shape_title),
        curves = listOf(Curve(taken, takenUpColor, fill = true), Curve(effect, c.mpcMealPrediction, width = 3f, fill = true)),
        xMin = 0.0, xMax = ceil(minutes / 30) * 30, yMin = 0.0, yMax = 1.0, xStep = 30.0, hLines = emptyList(), decimals = 1,
        legend = listOf(stringResource(ApsStrings.mpc_legend_carb_effect) to c.mpcMealPrediction, stringResource(ApsStrings.mpc_legend_carbs_taken_up) to takenUpColor),
        footer = listOf(
            stringResource(ApsStrings.mpc_shape_summary, shape.typicalGrams, minutes, shape.peakPosition * minutes, shape.halfWidth * minutes),
            stringResource(ApsStrings.mpc_shape_tail, shape.tailHalfLifeMinutes)
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(
    title: String,
    curves: List<Curve>,
    xMin: Double, xMax: Double, yMin: Double, yMax: Double, xStep: Double,
    hLines: List<Pair<Double, Color>>,
    decimals: Int,
    legend: List<Pair<String, Color>>,
    subtitle: String? = null,
    nowLine: Boolean = false,
    footer: List<String> = emptyList()
) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Chart(curves, xMin, xMax, yMin, yMax, xStep, hLines, decimals, nowLine, Modifier.fillMaxWidth().height(220.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                legend.forEach { (label, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                        Box(modifier = Modifier.size(AapsSpacing.large).background(color))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            footer.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** A plain line chart in the style of the insulin curve chart: dashed grid, axis labels, area fill. */
@Composable
private fun Chart(
    curves: List<Curve>, xMin: Double, xMax: Double, yMin0: Double, yMax0: Double, xStep: Double,
    hLines: List<Pair<Double, Color>>, decimals: Int, nowLine: Boolean, modifier: Modifier
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    // round the y range to a step that gives about 5 grid lines
    val span = max(yMax0 - yMin0, 1e-6)
    val rawStep = span / 5
    val mag = 10.0.pow(floor(log10(rawStep)))
    val yStep = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= rawStep }
    val yMin = floor(yMin0 / yStep) * yStep
    val yMax = ceil(yMax0 / yStep) * yStep
    Canvas(modifier = modifier) {
        val left = 44.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        val px = { x: Double -> left + ((x - xMin) / (xMax - xMin) * w).toFloat() }
        val py = { y: Double -> (h - (y - yMin) / (yMax - yMin) * h).toFloat() }
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        // grid and labels
        var y = yMin
        while (y <= yMax + yStep / 2) {
            drawLine(gridColor, Offset(left, py(y)), Offset(size.width, py(y)), pathEffect = dash)
            val label = measurer.measure(formatNumber(y, decimals), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6f, py(y) - label.size.height / 2))
            y += yStep
        }
        var x = ceil(xMin / xStep) * xStep
        while (x <= xMax + 1e-9) {
            drawLine(gridColor, Offset(px(x), 0f), Offset(px(x), h), pathEffect = dash)
            val label = measurer.measure(formatMinutes(x), labelStyle)
            drawText(label, topLeft = Offset(px(x) - label.size.width / 2, h + 2f))
            x += xStep
        }
        drawLine(axisColor, Offset(left, h), Offset(size.width, h))
        drawLine(axisColor, Offset(left, 0f), Offset(left, h))
        if (nowLine) drawLine(axisColor, Offset(px(0.0), 0f), Offset(px(0.0), h), strokeWidth = 2f)
        if (yMin < 0 && yMax > 0) drawLine(axisColor, Offset(left, py(0.0)), Offset(size.width, py(0.0)))
        hLines.forEach { (v, color) -> drawLine(color, Offset(left, py(v)), Offset(size.width, py(v)), strokeWidth = 2f, pathEffect = dash) }
        // curves
        for (cv in curves) {
            val pts = cv.points.filter { it.first in xMin..xMax && it.second.isFinite() }
            if (pts.isEmpty()) continue
            if (cv.bars) {
                val bw = (w / ((xMax - xMin) / 5) * 0.7).toFloat()
                for ((bx, by) in pts) {
                    val top = min(py(by), py(0.0))
                    drawRect(cv.color, Offset(px(bx) - bw / 2, top), Size(bw, abs(py(by) - py(0.0))))
                }
                continue
            }
            val path = Path()
            pts.forEachIndexed { i, (cx, cy) -> if (i == 0) path.moveTo(px(cx), py(cy)) else path.lineTo(px(cx), py(cy)) }
            if (cv.fill) {
                val area = Path()
                area.addPath(path)
                area.lineTo(px(pts.last().first), py(max(yMin, 0.0)))
                area.lineTo(px(pts.first().first), py(max(yMin, 0.0)))
                area.close()
                drawPath(area, Brush.verticalGradient(listOf(cv.color.copy(alpha = 0.3f), Color.Transparent)))
            }
            drawPath(path, cv.color, style = Stroke(width = cv.width, pathEffect = if (cv.dashed) dash else null))
        }
    }
}

private fun formatNumber(v: Double, decimals: Int): String {
    if (decimals <= 0) return v.roundToInt().toString()
    var f = 1.0
    repeat(decimals) { f *= 10 }
    val r = (v * f).roundToInt() / f
    val s = r.toString()
    val dot = s.indexOf('.')
    return if (dot < 0) s + "." + "0".repeat(decimals) else s + "0".repeat(max(0, decimals - (s.length - dot - 1)))
}

/** Axis label for minutes: hours when whole hours, else minutes */
private fun formatMinutes(m: Double): String {
    val v = m.roundToInt()
    return if (v % 60 == 0) "${v / 60}h" else "${v}m"
}
