package app.aaps.ui.compose.treatments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.aaps.core.data.model.CA
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import kotlin.math.roundToLong

/** Longest carb duration the Carbs dialog allows, in hours. */
private const val MAX_DURATION_HOURS = 10

/**
 * Edit one carb entry: grams, time, duration (whole hours, as in the Carbs dialog) and note.
 * [onSave] gets the new values; the duration is the original one when its hours were not changed,
 * so an entry with minutes in its duration is not rounded by an unrelated edit.
 */
@Composable
fun EditCarbsDialog(
    carbs: CA,
    maxCarbs: Int,
    onSave: (grams: Int, timestamp: Long, durationMs: Long, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val dateUtil = LocalDateUtil.current
    val originalHours = (carbs.duration / T.hours(1).msecs().toDouble()).roundToLong().toInt()
    var gramsText by remember { mutableStateOf(carbs.amount.toInt().toString()) }
    var hoursText by remember { mutableStateOf(originalHours.toString()) }
    var note by remember { mutableStateOf(carbs.notes.orEmpty()) }
    var timestamp by remember { mutableLongStateOf(carbs.timestamp) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val grams = gramsText.toIntOrNull()
    val hours = hoursText.toIntOrNull()
    val gramsValid = grams != null && grams in 1..maxCarbs
    val hoursValid = hours != null && hours in 0..MAX_DURATION_HOURS

    if (showDatePicker) EventDatePicker(eventTimeMillis = timestamp, onEventTimeChanged = { timestamp = it }, onDismiss = { showDatePicker = false })
    if (showTimePicker) EventTimePicker(eventTimeMillis = timestamp, onEventTimeChanged = { timestamp = it }, onDismiss = { showTimePicker = false })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiStrings.edit_carbs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                OutlinedTextField(
                    value = gramsText,
                    onValueChange = { gramsText = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(InterfacesStrings.carbs)) },
                    suffix = { Text(stringResource(CoreUiStrings.shortgramm)) },
                    isError = !gramsValid,
                    supportingText = if (gramsValid) null else {
                        { Text(stringResource(UiStrings.edit_carbs_range, maxCarbs)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    Text(stringResource(CoreUiStrings.time), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { showDatePicker = true }) { Text(dateUtil.dateString(timestamp)) }
                    OutlinedButton(onClick = { showTimePicker = true }) { Text(dateUtil.timeString(timestamp)) }
                }
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(CoreUiStrings.duration)) },
                    suffix = { Text(stringResource(InterfacesStrings.unit_hours)) },
                    isError = !hoursValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(CoreUiStrings.notes_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = gramsValid && hoursValid,
                onClick = {
                    val durationMs = if (hours == originalHours) carbs.duration else T.hours(hours!!.toLong()).msecs()
                    onSave(grams!!, timestamp, durationMs, note.trim().ifEmpty { null })
                }
            ) { Text(stringResource(CoreUiStrings.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}
