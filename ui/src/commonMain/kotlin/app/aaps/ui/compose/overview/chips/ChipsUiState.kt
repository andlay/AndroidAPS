package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable
import app.aaps.core.interfaces.overview.IsfStep

/**
 * What the overview chips show.
 *
 * Kept in their own file rather than beside `ChipsViewModel`: these are plain values with no Android
 * in them, and every composable and preview that reads them can then be shared code.
 */
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
    val isfStep: IsfStep = IsfStep.SAME,
    /** Profile ISF / ISF in use: "1.50" is 50% more insulin. When set, the chip shows it in place of the two ISF values. */
    val factorText: String = "",
    val factorStep: IsfStep = IsfStep.SAME,
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false
)
