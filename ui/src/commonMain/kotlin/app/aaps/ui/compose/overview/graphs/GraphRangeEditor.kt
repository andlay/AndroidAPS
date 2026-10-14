package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.AxisRange
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * How the ends of one axis are typed: the step of the +/- buttons, the decimals, the allowed values
 * and the value an end starts at when it is switched from Auto to a number.
 *
 * [toShown] and [fromShown] convert between the stored and the typed value. They are only needed for
 * the BG axis, which is stored in mg/dL and typed in the user's units.
 */
internal class AxisUnit(
    val step: Double,
    val decimals: Int,
    val valueRange: ClosedFloatingPointRange<Double>,
    val defaultMin: Double,
    val defaultMax: Double,
    val toShown: (Double) -> Double = { it },
    val fromShown: (Double) -> Double = { it }
)

/** Typing rules for the BG axis, in [isMmol] units. Stored values are mg/dL. */
internal fun bgAxisUnit(isMmol: Boolean, toShown: (Double) -> Double, fromShown: (Double) -> Double): AxisUnit =
    if (isMmol) AxisUnit(0.5, 1, 0.0..40.0, 2.0, 15.0, toShown, fromShown)
    else AxisUnit(5.0, 0, 0.0..700.0, 40.0, 270.0, toShown, fromShown)

/**
 * Typing rules for an axis whose first trace is [trace], in that trace's own unit. BG as a trace is
 * already in the user's units, so it needs to know [isMmol].
 */
internal fun axisUnitFor(trace: GraphTrace, isMmol: Boolean): AxisUnit =
    if (trace.transform == TraceTransform.ROLLING_SUM) AxisUnit(10.0, 0, -100_000.0..100_000.0, 0.0, 5000.0)
    else when (trace.type) {
        SeriesType.BG                                          -> bgAxisUnit(isMmol, { it }, { it })

        SeriesType.IOB, SeriesType.ABS_IOB                     -> AxisUnit(0.1, 1, -50.0..100.0, 0.0, 5.0)
        SeriesType.BASAL                                       -> AxisUnit(0.1, 1, 0.0..50.0, 0.0, 3.0)
        SeriesType.COB                                         -> AxisUnit(1.0, 0, 0.0..500.0, 0.0, 60.0)
        // Drawn below zero, so its axis values are negative
        SeriesType.CARBS_ABSORBED                              -> AxisUnit(0.5, 1, -100.0..0.0, -5.0, 0.0)
        SeriesType.BGI, SeriesType.DEVIATIONS, SeriesType.DEV_SLOPE ->
            if (isMmol) AxisUnit(0.1, 1, -50.0..50.0, -1.0, 1.0) else AxisUnit(1.0, 0, -500.0..500.0, -20.0, 20.0)

        SeriesType.SENSITIVITY                                 -> AxisUnit(5.0, 0, 0.0..500.0, 50.0, 150.0)
        SeriesType.VAR_SENSITIVITY                             -> AxisUnit(0.1, 1, 0.0..1000.0, 0.0, 10.0)
        SeriesType.HEART_RATE                                  -> AxisUnit(1.0, 0, 0.0..300.0, 40.0, 160.0)
        SeriesType.STEPS                                       -> AxisUnit(10.0, 0, 0.0..100_000.0, 0.0, 1000.0)
        SeriesType.ACTIVITY                                    -> AxisUnit(0.005, 3, -1.0..1.0, 0.0, 0.05)
        SeriesType.PREDICTIONS                                 -> AxisUnit(1.0, 0, -1000.0..1000.0, 0.0, 10.0)
    }

/** Typing rules for the basal rate on the right of an IOB graph (U/h). */
internal val BASAL_AXIS_UNIT = AxisUnit(0.1, 1, 0.1..50.0, 0.0, 3.0)

/**
 * Min and max of one axis, each Auto or a typed number.
 *
 * @param maxOnly only the max can be set (the basal axis always starts at 0)
 */
@Composable
internal fun AxisRangeEditor(title: TextRef, range: AxisRange, unit: AxisUnit, onChange: (AxisRange) -> Unit, maxOnly: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        if (!maxOnly) {
            AxisEndRow(UiStrings.graph_axis_min, range.min, unit, unit.defaultMin) { onChange(range.copy(min = it)) }
        }
        AxisEndRow(UiStrings.graph_axis_max, range.max, unit, unit.defaultMax) { onChange(range.copy(max = it)) }
    }
}

/** One end: an Auto chip, and the number when it is not Auto. [value] is stored, the field shows it converted. */
@Composable
private fun AxisEndRow(label: TextRef, value: Double?, unit: AxisUnit, defaultShown: Double, onChange: (Double?) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SheetChip(
            selected = value == null,
            label = stringResource(UiStrings.graph_axis_auto),
            onClick = { onChange(if (value == null) unit.fromShown(defaultShown) else null) }
        )
        Spacer(Modifier.width(8.dp))
        if (value == null) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        } else {
            NumberInputRow(
                labelRef = label,
                value = unit.toShown(value).coerceIn(unit.valueRange),
                onValueChange = { onChange(unit.fromShown(it)) },
                valueRange = unit.valueRange,
                step = unit.step,
                decimalPlaces = unit.decimals,
                formatAsInt = unit.decimals == 0,
                compact = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
