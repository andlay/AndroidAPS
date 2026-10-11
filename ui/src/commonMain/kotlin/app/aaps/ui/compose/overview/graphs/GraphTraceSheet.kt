package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.PlotStyle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/** Short name of a trace for the graph title and the touch card, e.g. "HR" or "HR EWMA 30m". */
@Composable
internal fun traceLabel(trace: GraphTrace): String {
    val name = stringResource(seriesShortNameId(trace.type))
    return when (trace.transform) {
        TraceTransform.NONE         -> name
        TraceTransform.EWMA         -> stringResource(UiStrings.graph_trace_ewma_label, name, trace.windowMinutes)
        TraceTransform.ROLLING_MEAN -> stringResource(UiStrings.graph_trace_mean_label, name, trace.windowMinutes)
        TraceTransform.ROLLING_SUM  -> stringResource(UiStrings.graph_trace_sum_label, name, trace.windowMinutes)
    }
}

private fun transformNameId(transform: TraceTransform): TextRef = when (transform) {
    TraceTransform.NONE         -> UiStrings.graph_transform_none
    TraceTransform.EWMA         -> UiStrings.graph_transform_ewma
    TraceTransform.ROLLING_MEAN -> UiStrings.graph_transform_mean
    TraceTransform.ROLLING_SUM  -> UiStrings.graph_transform_sum
}

private fun styleNameId(style: PlotStyle): TextRef = when (style) {
    PlotStyle.LINE   -> UiStrings.graph_style_line
    PlotStyle.POINTS -> UiStrings.graph_style_points
    PlotStyle.BARS   -> UiStrings.graph_style_bars
    PlotStyle.AREA   -> UiStrings.graph_style_area
}

/** Window choices step by 5 minutes. */
private const val WINDOW_STEP_MINUTES = 5.0

/**
 * Edit sheet of a secondary graph: height, the series shown as they are, and per trace its
 * calculation, window, plot style and colour.
 *
 * @param availableSeries series the user can pick
 * @param onTracesChange the new trace list. An empty list means the user removed everything.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GraphTracesBottomSheet(
    title: String,
    traces: List<GraphTrace>,
    availableSeries: List<SeriesType>,
    height: Int,
    onHeightChange: (Int) -> Unit,
    onTracesChange: (List<GraphTrace>) -> Unit,
    onDismiss: () -> Unit,
    onRemoveGraph: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()
    val colors = rememberSeriesColors()
    var expanded by remember { mutableIntStateOf(-1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (onRemoveGraph != null) {
                    TextButton(onClick = onRemoveGraph) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(CoreUiStrings.remove))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberInputRow(
                labelRef = CoreUiStrings.graph_height,
                value = height.toDouble(),
                onValueChange = { onHeightChange(it.toInt()) },
                valueRange = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP.toDouble()..GraphConfig.MAX_GRAPH_HEIGHT_DP.toDouble(),
                step = 10.0,
                formatAsInt = true
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (type in availableSeries) {
                    SheetChip(
                        selected = traces.any { it.type == type && !it.isDerived },
                        label = stringResource(seriesShortNameId(type)),
                        onClick = {
                            expanded = -1
                            onTracesChange(traces.toggleRaw(type))
                        }
                    )
                }
            }
            if (traces.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(UiStrings.graph_traces), style = MaterialTheme.typography.titleSmall)
                for ((index, trace) in traces.withIndex()) {
                    TraceRow(
                        trace = trace,
                        color = lookOf(trace, colors).color,
                        expanded = expanded == index,
                        onExpand = { expanded = if (expanded == index) -1 else index },
                        onRemove = {
                            expanded = -1
                            onTracesChange(traces.filterIndexed { i, _ -> i != index })
                        }
                    )
                    if (expanded == index) {
                        TraceOptions(
                            trace = trace,
                            availableSeries = availableSeries,
                            onChange = { onTracesChange(traces.replaced(index, it)) }
                        )
                    }
                }
                traces.withNewDerived()?.let { withDerived ->
                    TextButton(onClick = {
                        expanded = withDerived.lastIndex
                        onTracesChange(withDerived)
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(UiStrings.graph_trace_add))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TraceRow(trace: GraphTrace, color: Color, expanded: Boolean, onExpand: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(traceLabel(trace), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onExpand) {
            Icon(if (expanded) Icons.Filled.Close else Icons.Filled.Tune, contentDescription = stringResource(CoreUiStrings.switch_to_edit))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(CoreUiStrings.remove))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceOptions(trace: GraphTrace, availableSeries: List<SeriesType>, onChange: (GraphTrace) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 8.dp)) {
        if (trace.isDerived) {
            OptionTitle(UiStrings.graph_trace_series)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (type in availableSeries) {
                    SheetChip(selected = trace.type == type, label = stringResource(seriesShortNameId(type)), onClick = { onChange(trace.copy(type = type)) })
                }
            }
        }
        OptionTitle(UiStrings.graph_trace_calculation)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (transform in TraceTransform.entries) {
                SheetChip(selected = trace.transform == transform, label = stringResource(transformNameId(transform)), onClick = { onChange(trace.copy(transform = transform)) })
            }
        }
        if (trace.isDerived) {
            Spacer(Modifier.height(4.dp))
            NumberInputRow(
                labelRef = UiStrings.graph_trace_window,
                value = trace.windowMinutes.toDouble(),
                onValueChange = { onChange(trace.copy(windowMinutes = it.toInt())) },
                valueRange = GraphTrace.MIN_WINDOW_MINUTES.toDouble()..GraphTrace.MAX_WINDOW_MINUTES.toDouble(),
                step = WINDOW_STEP_MINUTES,
                unitLabel = CoreUiStrings.units_min,
                formatAsInt = true
            )
        }
        if (trace.isStylable) {
            OptionTitle(UiStrings.graph_trace_style)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val current = trace.effectiveStyle()
                for (style in PlotStyle.entries) {
                    SheetChip(selected = current == style, label = stringResource(styleNameId(style)), onClick = { onChange(trace.copy(style = style)) })
                }
            }
            OptionTitle(UiStrings.graph_trace_colour)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (choice in TRACE_COLOR_CHOICES) {
                    val stored = choice.toStoredColor()
                    ColorSwatch(color = choice, selected = trace.color == stored, onClick = { onChange(trace.copy(color = stored)) })
                }
                TextButton(onClick = { onChange(trace.copy(style = null, color = null)) }) {
                    Text(stringResource(UiStrings.graph_trace_reset))
                }
            }
        }
    }
}

@Composable
private fun OptionTitle(text: TextRef) {
    Spacer(Modifier.height(8.dp))
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SheetChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(32.dp)
            .border(width = if (selected) 3.dp else 1.dp, color = ring, shape = CircleShape)
            .padding(4.dp)
            .background(color, CircleShape)
            .clickable(onClick = onClick)
    )
}
