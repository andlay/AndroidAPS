package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull

/**
 * Shower mode while it runs: the BG the loop is capped at and the time left, drawn like the temp
 * target chip beside it. A tap opens the shower dialog, where it can be ended.
 *
 * @see ShowerModeChipPreview
 */
@Composable
fun ShowerModeChip(
    state: ShowerChipState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = ElementType.SHOWER_MODE.color()
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = color.copy(alpha = 0.2f),
        modifier = modifier
            .fillMaxWidth()
            .height(AapsSpacing.chipHeight)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            ) {
                Icon(
                    imageVector = ElementType.SHOWER_MODE.icon(),
                    contentDescription = stringResourceOrNull(ElementType.SHOWER_MODE.label()),
                    tint = color,
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    text = stringResource(CoreUiStrings.shower_mode_chip, state.capText, state.remainingText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = AapsSpacing.medium)
                )
            }
            if (state.progress > 0f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(AapsSpacing.chipProgressHeight),
                    color = color,
                    trackColor = color.copy(alpha = 0.3f)
                )
            }
        }
    }
}
