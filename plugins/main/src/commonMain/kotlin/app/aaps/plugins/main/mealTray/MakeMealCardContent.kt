package app.aaps.plugins.main.mealTray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import app.aaps.core.interfaces.meal.MealTray
import app.aaps.core.interfaces.meal.MealTrayItem
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.main.MainStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.round

/** The NFC meal cards plugin screen: make a card from a barcode or by hand. */
internal class MakeMealCardContent(
    private val tools: MealCardTools,
    private val tray: MealTray
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        val title = stringResource(MainStrings.meal_card_make_title)
        LaunchedEffect(title) {
            setToolbarConfig(
                ToolbarConfig(
                    title = title,
                    navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                    actions = {}
                )
            )
        }
        MakeMealCardScreen(tools, tray)
    }
}

/** How the carbs of the card are worked out from the product. */
private enum class Portion { SERVING, GRAMS }

/** A message under the buttons; [detail] is the error text where there is one. */
private data class Note(val kind: Kind, val detail: String = "") {
    enum class Kind { NOT_FOUND, LOOKUP_FAILED, SCAN_FAILED }
}

@Composable
internal fun MakeMealCardScreen(tools: MealCardTools, tray: MealTray) {
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<FoodFacts?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Note?>(null) }
    var name by remember { mutableStateOf("") }
    var portion by remember { mutableStateOf(Portion.GRAMS) }
    var gramsText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var writeJob by remember { mutableStateOf<Job?>(null) }
    var writeResult by remember { mutableStateOf<CardWriteResult?>(null) }

    // The grams the portion stands for, when the product has values per 100 g
    fun portionGrams(): Double? = when (portion) {
        Portion.SERVING -> facts?.servingGrams
        Portion.GRAMS   -> gramsText.replace(',', '.').toDoubleOrNull()
    }

    fun recalculate() {
        val grams = portionGrams() ?: return
        facts?.carbsPer100g?.let { carbsText = round(it * grams / 100).toInt().toString() }
    }

    fun scan() {
        scope.launch {
            message = null
            val barcode = try {
                tools.scanBarcode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = Note(Note.Kind.SCAN_FAILED, e.message ?: e::class.simpleName.orEmpty())
                null
            } ?: return@launch
            busy = true
            val found = try {
                tools.lookUp(barcode).also { if (it == null) message = Note(Note.Kind.NOT_FOUND) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = Note(Note.Kind.LOOKUP_FAILED, e.message ?: e::class.simpleName.orEmpty())
                null
            } finally {
                busy = false
            }
            // Not found or no network: the same form, to be filled in from the package
            facts = found
            name = listOfNotNull(found?.name, found?.brand).joinToString(", ")
            portion = if (found?.servingGrams != null) Portion.SERVING else Portion.GRAMS
            gramsText = if (found?.servingGrams != null) "" else "100"
            carbsText = ""
            recalculate()
            showForm = true
        }
    }

    val carbs = carbsText.toIntOrNull()?.takeIf { it in 0..FoodLink.MAX_CARBS_PER_FOOD }
    val grams = portionGrams()
    val item = if (carbs != null && name.isNotBlank()) MealTrayItem(
        name = name.trim(),
        carbs = carbs,
        protein = grams?.let { g -> facts?.proteinPer100g?.let { round1(it * g / 100) } },
        fat = grams?.let { g -> facts?.fatPer100g?.let { round1(it * g / 100) } }
    ) else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        Text(stringResource(MainStrings.meal_card_intro), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            Button(onClick = { scan() }, enabled = !busy) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text(stringResource(MainStrings.meal_card_scan), modifier = Modifier.padding(start = AapsSpacing.small))
            }
            OutlinedButton(onClick = { facts = null; name = ""; carbsText = ""; gramsText = ""; portion = Portion.GRAMS; message = null; showForm = true }, enabled = !busy) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Text(stringResource(MainStrings.meal_card_manual), modifier = Modifier.padding(start = AapsSpacing.small))
            }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = AapsSpacing.medium))
            Text(stringResource(MainStrings.meal_card_looking_up))
        }
        message?.let { note ->
            Text(
                when (note.kind) {
                    Note.Kind.NOT_FOUND     -> stringResource(MainStrings.meal_card_not_found)
                    Note.Kind.LOOKUP_FAILED -> stringResource(MainStrings.meal_card_lookup_failed, note.detail)
                    Note.Kind.SCAN_FAILED   -> stringResource(MainStrings.meal_card_scan_failed, note.detail)
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (showForm && !busy) {
            facts?.let { f ->
                f.carbsPer100g?.let { Text(stringResource(MainStrings.meal_card_per_100g, it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                f.servingText?.takeIf { it.isNotBlank() }?.let { Text(stringResource(MainStrings.meal_card_serving, it), style = MaterialTheme.typography.bodyMedium) }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text(stringResource(MainStrings.meal_card_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (facts?.carbsPer100g != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    facts?.servingGrams?.let { serving ->
                        FilterChip(selected = portion == Portion.SERVING, onClick = { portion = Portion.SERVING; recalculate() },
                            label = { Text(stringResource(MainStrings.meal_card_portion_serving, serving)) })
                    }
                    FilterChip(selected = portion == Portion.GRAMS, onClick = { portion = Portion.GRAMS; recalculate() },
                        label = { Text(stringResource(MainStrings.meal_card_portion_grams)) })
                }
                if (portion == Portion.GRAMS) OutlinedTextField(
                    value = gramsText, onValueChange = { gramsText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5); recalculate() },
                    label = { Text(stringResource(MainStrings.meal_card_grams)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = carbsText, onValueChange = { carbsText = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(MainStrings.meal_card_carbs)) },
                isError = carbsText.isNotEmpty() && carbs == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            item?.let { if (it.protein != null || it.fat != null) Text(stringResource(MainStrings.meal_card_protein_fat, it.protein ?: 0.0, it.fat ?: 0.0), style = MaterialTheme.typography.bodySmall) }
            if (facts != null) Text(stringResource(MainStrings.meal_card_check_package), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Button(
                    enabled = item != null,
                    onClick = {
                        val link = FoodLink.build(item ?: return@Button)
                        writeResult = null
                        writeJob = scope.launch { writeResult = tools.writeCard(link); writeJob = null }
                    }
                ) { Text(stringResource(MainStrings.meal_card_write)) }
                OutlinedButton(
                    enabled = item != null,
                    onClick = { item?.let { tray.addFromLink(FoodLink.build(it)) }; message = null; writeResult = null }
                ) { Text(stringResource(MainStrings.meal_card_add_now)) }
            }
        }
    }

    // Waiting for a card, then the result
    if (writeJob != null || writeResult != null) {
        val result = writeResult
        AlertDialog(
            onDismissRequest = { writeJob?.cancel(); writeJob = null; writeResult = null },
            title = { Text(stringResource(MainStrings.meal_card_write)) },
            text = {
                Text(
                    when (result) {
                        null                           -> stringResource(MainStrings.meal_card_hold)
                        CardWriteResult.WRITTEN        -> stringResource(MainStrings.meal_card_written, item?.name.orEmpty(), item?.carbs ?: 0)
                        CardWriteResult.NO_NFC         -> stringResource(MainStrings.meal_card_error_no_nfc)
                        CardWriteResult.NFC_OFF        -> stringResource(MainStrings.meal_card_error_nfc_off)
                        CardWriteResult.READ_ONLY      -> stringResource(MainStrings.meal_card_error_read_only)
                        CardWriteResult.TOO_SMALL      -> stringResource(MainStrings.meal_card_error_too_small)
                        CardWriteResult.NOT_SUPPORTED  -> stringResource(MainStrings.meal_card_error_not_supported)
                        CardWriteResult.FAILED,
                        CardWriteResult.NO_SCREEN      -> stringResource(MainStrings.meal_card_error_failed)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { writeJob?.cancel(); writeJob = null; writeResult = null }) {
                    Text(stringResource(if (result == null) CoreUiStrings.cancel else MainStrings.meal_card_done))
                }
            }
        )
    }
}

private fun round1(value: Double): Double = round(value * 10) / 10
