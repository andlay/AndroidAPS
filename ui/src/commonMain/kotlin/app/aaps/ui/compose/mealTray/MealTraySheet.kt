package app.aaps.ui.compose.mealTray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.meal.MealTray
import app.aaps.core.interfaces.meal.MealTrayItem
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlin.math.round

/**
 * The foods collected from NFC meal cards, with their total. [onOpenWizard] gets the total carbs
 * and the food names for the wizard's note. Nothing is dosed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTraySheet(
    tray: MealTray,
    onOpenWizard: (carbs: Int, notes: String) -> Unit,
    onDismiss: () -> Unit
) {
    val items by tray.items.collectAsStateWithLifecycle()
    val totalCarbs = items.sumOf { it.carbs }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
        ) {
            Text(stringResource(UiStrings.meal_tray_title), style = MaterialTheme.typography.titleMedium)
            if (items.isEmpty()) {
                Text(stringResource(UiStrings.meal_tray_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEachIndexed { index, item ->
                MealTrayRow(item = item, onRemove = { tray.remove(index) })
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(UiStrings.meal_tray_total), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(InterfacesStrings.format_carbs, totalCarbs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(UiStrings.meal_tray_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tray.clear(); onDismiss() }) { Text(stringResource(UiStrings.meal_tray_clear)) }
                Row(modifier = Modifier.weight(1f)) {}
                Button(
                    enabled = totalCarbs > 0,
                    onClick = { onOpenWizard(totalCarbs, items.joinToString(", ") { it.name }) }
                ) { Text(stringResource(CoreUiStrings.boluswizard)) }
            }
        }
    }
}

@Composable
private fun MealTrayRow(item: MealTrayItem, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            if (item.protein != null || item.fat != null) {
                Text(
                    stringResource(UiStrings.meal_tray_protein_fat, round1(item.protein ?: 0.0), round1(item.fat ?: 0.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(stringResource(InterfacesStrings.format_carbs, item.carbs), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = stringResource(UiStrings.meal_tray_remove)) }
    }
}

private fun round1(value: Double): Double = round(value * 10) / 10
