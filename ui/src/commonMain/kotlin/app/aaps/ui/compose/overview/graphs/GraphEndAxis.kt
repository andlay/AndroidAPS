package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.utils.DecimalFormatter
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import kotlin.math.abs
import kotlin.math.round

/**
 * Right (end) axis of the overview graphs.
 *
 * All graphs share one x layout: a time is at the same place on every graph, so panning and the
 * press-and-hold line work across them. That only holds while every plot has the same width. So
 * every graph has a right axis of the same make, with the same fixed label width: graphs with a
 * second scale show its numbers, the others draw it invisible ([rememberEndAxisSpacer]).
 *
 * The axis width is fixed ([GRAPH_AXIS_SIZE]), so a wide label cannot make one plot narrower.
 */
internal val END_AXIS_LABEL_WIDTH = 30.dp

/**
 * Width of every left and right axis of the overview graphs: the label width, the 4 dp tick and the
 * 1 dp axis line. Fixed, not measured: a measured axis grows with its widest label (steps in the
 * thousands, activity with three decimals), and that graph's plot then no longer lines up in time
 * with the others. A label that is wider is drawn past the edge instead.
 */
internal val GRAPH_AXIS_SIZE = BaseAxis.Size.Fixed(END_AXIS_LABEL_WIDTH + 5.dp)

/** Tick count for the basal scale on the right of the IOB graph. */
internal const val BASAL_AXIS_TICK_COUNT = 3

/** A right axis that shows nothing. It only keeps this graph as wide as the graphs that have one. */
@Composable
internal fun rememberEndAxisSpacer(): VerticalAxis<Axis.Position.Vertical.End> = VerticalAxis.rememberEnd(
    line = rememberAxisLineComponent(fill = Fill(Color.Transparent)),
    label = rememberTextComponent(
        style = TextStyle(color = Color.Transparent),
        minWidth = TextComponent.MinWidth.fixed(END_AXIS_LABEL_WIDTH)
    ),
    tick = rememberAxisTickComponent(fill = Fill(Color.Transparent)),
    guideline = null,
    itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 2 }) },
    size = GRAPH_AXIS_SIZE
)

/**
 * A right axis with numbers, drawn in [color] so it is clear which line it belongs to. No
 * guidelines: the left axis already draws them, and two grids on one graph do not match.
 */
@Composable
internal fun rememberEndAxis(
    color: Color,
    itemPlacer: VerticalAxis.ItemPlacer,
    valueFormatter: CartesianValueFormatter
): VerticalAxis<Axis.Position.Vertical.End> = VerticalAxis.rememberEnd(
    line = rememberAxisLineComponent(fill = Fill(Color.Transparent)),
    label = rememberTextComponent(
        style = TextStyle(color = color),
        minWidth = TextComponent.MinWidth.fixed(END_AXIS_LABEL_WIDTH)
    ),
    tick = rememberAxisTickComponent(fill = Fill(color)),
    guideline = null,
    itemPlacer = itemPlacer,
    valueFormatter = valueFormatter,
    size = GRAPH_AXIS_SIZE
)

/**
 * Formats an axis number with as many decimals as [step] needs (2.5 needs one, 0.25 two).
 * With [absolute] the sign is dropped: basal is drawn hanging down from the top, so its values
 * are stored negative.
 */
internal fun axisNumber(value: Double, step: Double?, decimalFormatter: DecimalFormatter, absolute: Boolean = false): String {
    val shown = if (absolute) abs(value) else value
    return when (decimalsFor(step)) {
        0    -> decimalFormatter.to0Decimal(shown)
        1    -> decimalFormatter.to1Decimal(shown)
        else -> decimalFormatter.to2Decimal(shown)
    }
}

/** Decimals that the tick [step] needs; one when there is no fixed step. */
internal fun decimalsFor(step: Double?): Int = when {
    step == null                              -> 1
    abs(step - round(step)) < 1e-9            -> 0
    abs(step * 10 - round(step * 10)) < 1e-9 -> 1
    else                                      -> 2
}
