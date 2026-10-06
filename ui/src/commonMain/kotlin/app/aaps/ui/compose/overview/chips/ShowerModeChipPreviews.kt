package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true)
@Composable
internal fun ShowerModeChipPreview() {
    MaterialTheme {
        ShowerModeChip(
            state = ShowerChipState(capText = "10.8", remainingText = "(10')", minutesLeft = 10, progress = 0.33f),
            onClick = {}
        )
    }
}
