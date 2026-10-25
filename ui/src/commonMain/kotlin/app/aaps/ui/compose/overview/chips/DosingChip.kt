package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.stringResource

/** Height of the two-line dosing widget: two chip rows and the gap between them. */
internal val DOSING_CHIP_HEIGHT: Dp = AapsSpacing.chipHeight * 2 + 2.dp

/**
 * Basal and SMB in one widget. Top: the temp basal icon and the basal delivered now. Bottom: an up
 * triangle, as on the IOB graph, filled when SMBs are on and empty when they are off, with the SMB
 * of the last loop run when it gave one. A tap shows what the last loop run asked for and why.
 */
@Composable
fun DosingChip(
    tbrState: TbrState,
    state: DosingUiState,
    onTempBasalDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDecision by remember { mutableStateOf(false) }
    val basalColor = AapsTheme.elementColors.tempBasal
    val smbColor = ElementType.INSULIN.color()
    val haptic = LocalHapticFeedback.current
    val description = stringResource(CoreUiStrings.loop_decision)
    // Whether a temp basal runs, and which way, and whether SMBs are on, are otherwise only told by the icons
    val tbrDescription = stringResource(tbrState.toDescription())
    val smbDescription = stringResource(if (state.smbOn) CoreUiStrings.smb_on else CoreUiStrings.smb_off)
    val stateText = stringResource(CoreUiStrings.dosing_state_description, tbrDescription, smbDescription)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showDecision = true },
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = basalColor.copy(alpha = 0.2f),
            modifier = modifier.semantics {
                contentDescription = description
                stateDescription = stateText
            }
        ) {
            Column(
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    Icon(
                        imageVector = tbrState.toIcon(),
                        contentDescription = null,
                        tint = basalColor,
                        modifier = Modifier.size(AapsSpacing.chipIconSize)
                    )
                    Text(state.basalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    SmbTriangle(filled = state.smbOn, color = smbColor, modifier = Modifier.size(AapsSpacing.chipIconSize))
                    if (state.smbText.isNotEmpty())
                        Text(state.smbText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    if (showDecision) {
        DosingDialog(
            state = state,
            onTempBasalDetails = { showDecision = false; onTempBasalDetails() },
            onDismiss = { showDecision = false }
        )
    }
}

/** An up triangle, filled or as an outline. */
@Composable
private fun SmbTriangle(filled: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val inset = size.minDimension * 0.15f
        val path = Path().apply {
            moveTo(size.width / 2f, inset)
            lineTo(size.width - inset, size.height - inset)
            lineTo(inset, size.height - inset)
            close()
        }
        drawPath(path, color, style = if (filled) Fill else Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
private fun DosingDialog(state: DosingUiState, onTempBasalDetails: () -> Unit, onDismiss: () -> Unit) {
    val dateUtil = LocalDateUtil.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiStrings.loop_decision)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (state.basalText.isNotEmpty())
                    Text(stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.basal_now), state.basalText), style = MaterialTheme.typography.bodyMedium)
                if (state.lastRunAt > 0L) {
                    Text(
                        stringResource(CoreUiStrings.loop_decision_time, dateUtil.timeString(state.lastRunAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.temp_basal), state.decisionTemp), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.smb_shortname), state.decisionSmb), style = MaterialTheme.typography.titleSmall)
                    state.smbReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onTempBasalDetails) { Text(stringResource(CoreUiStrings.temp_basal_details)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.ok)) }
        }
    )
}
