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

/**
 * The dosing widget: the basal now, whether SMBs are on and the last SMB, and the last loop decision behind its dialog.
 *
 * @param basalText the basal rate delivered now
 * @param smbText the SMB of the last loop run, empty when it gave none
 * @param smbOn SMBs are on (the triangle is filled): closed loop and on in a recent loop run
 * @param lastRunAt when the loop last ran, 0 before the first run
 * @param decisionTemp the temp basal the last run asked for, or "no change"
 * @param decisionSmb the SMB the last run gave, or "none"
 * @param reason the reason line of the last run
 * @param smbReason the log line that says why SMBs were on or off, when there was one
 */
@Immutable
data class DosingUiState(
    val basalText: String = "",
    val smbText: String = "",
    val smbOn: Boolean = false,
    val lastRunAt: Long = 0L,
    val decisionTemp: String = "",
    val decisionSmb: String = "",
    val reason: String = "",
    val smbReason: String? = null
)
