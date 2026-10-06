package app.aaps.ui.compose.shower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Start shower mode, or cancel it when it already runs. Cancel is red with a close icon, like the
 * cancel button for a running temp target.
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
        title = { Text(stringResource(if (minutesLeft == null) CoreUiStrings.shower_mode_start else CoreUiStrings.shower_mode)) },
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
                TextButton(
                    onClick = onEnd,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(CoreUiStrings.shower_mode_cancel))
                }
            }
        },
        dismissButton = {
            // While it runs, "Cancel" would read as cancelling shower mode, so this only closes.
            TextButton(onClick = onDismiss) { Text(stringResource(if (minutesLeft == null) CoreUiStrings.cancel else CoreUiStrings.close)) }
        }
    )
}
