package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable

/**
 * What the overview chips show.
 *
 * Kept in their own file rather than beside `ChipsViewModel`: these are plain values with no Android
 * in them, and every composable and preview that reads them can then be shared code.
 */
/**
 * Shower mode while it runs.
 *
 * @param capText the BG the loop is capped at, in the user's units
 * @param remainingText time left in the chips' usual form, e.g. "(12')"
 * @param minutesLeft time left, rounded up
 * @param progress share of the time already gone, 0..1
 */
@Immutable
data class ShowerChipState(
    val capText: String,
    val remainingText: String,
    val minutesLeft: Int,
    val progress: Float
)

@Immutable
data class IobUiState(
    val text: String = "",
    val iobTotal: Double = 0.0
)

@Immutable
data class CobUiState(
    val text: String = "",
    val carbsReq: Int = 0,
    val cobValue: Double = 0.0
)

@Immutable
data class SensitivityUiState(
    val asText: String = "",
    val isfFrom: String = "",
    val isfTo: String = "",
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false
)
