package app.aaps.ui.compose.shower

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.IntKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource

/**
 * Overview banner while shower mode runs: time left and an End button. Nothing when it is off.
 */
@Composable
fun ShowerModeBanner(minutesLeft: Int?, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = minutesLeft != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        ElevatedCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ElementType.SHOWER_MODE.icon(),
                    contentDescription = null,
                    tint = ElementType.SHOWER_MODE.color(),
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    text = stringResource(CoreUiStrings.shower_mode_left, stringResource(CoreUiStrings.format_mins, minutesLeft ?: 0)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(onClick = onEnd) {
                    Text(stringResource(CoreUiStrings.shower_mode_stop))
                }
            }
        }
    }
}

/**
 * Start shower mode, or end it when it already runs.
 *
 * @param minutesLeft null when shower mode is off
 * @param defaultMinutes the length from the Safety settings
 */
@Composable
fun ShowerModeDialog(
    minutesLeft: Int?,
    defaultMinutes: Int,
    onStart: (minutes: Int) -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableIntStateOf(defaultMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = ElementType.SHOWER_MODE.icon(), contentDescription = null, tint = ElementType.SHOWER_MODE.color()) },
        title = { Text(stringResource(if (minutesLeft == null) CoreUiStrings.shower_mode_start else CoreUiStrings.shower_mode_end)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                if (minutesLeft == null) {
                    Text(stringResource(CoreUiStrings.shower_mode_confirm, stringResource(CoreUiStrings.format_mins, minutes)))
                    NumberInputRow(
                        labelRef = CoreUiStrings.shower_mode_minutes,
                        value = minutes.toDouble(),
                        onValueChange = { minutes = it.toInt() },
                        valueRange = IntKey.SafetyShowerModeMinutes.min.toDouble()..IntKey.SafetyShowerModeMinutes.max.toDouble(),
                        step = 5.0,
                        formatAsInt = true
                    )
                } else {
                    Text(stringResource(CoreUiStrings.shower_mode_running, stringResource(CoreUiStrings.format_mins, minutesLeft)))
                }
            }
        },
        confirmButton = {
            if (minutesLeft == null) {
                TextButton(onClick = { onStart(minutes) }) { Text(stringResource(CoreUiStrings.ok)) }
            } else {
                TextButton(onClick = onEnd) { Text(stringResource(CoreUiStrings.shower_mode_end)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
        }
    )
}
