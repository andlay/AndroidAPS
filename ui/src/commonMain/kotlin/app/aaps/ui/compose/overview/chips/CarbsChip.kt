package app.aaps.ui.compose.overview.chips

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource

/**
 * COB on one row, beside the temp target: the carbs icon and COB, then the carbs the loop asks for,
 * blinking, only when it asks for some.
 */
@Composable
fun CarbsChip(
    state: CobUiState,
    modifier: Modifier = Modifier
) {
    val cobColor = ElementType.COB.color()
    val chipDescription = stringResource(CoreUiStrings.cob)
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = if (state.cobValue != 0.0 || state.carbsReqText.isNotEmpty()) cobColor.copy(alpha = 0.2f) else Color.Transparent,
        // No onClick, so it does not merge on its own (see CobChip)
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = chipDescription }
    ) {
        // The carbs needed, when there are any, follow on the same row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                Icon(
                    imageVector = ElementType.COB.icon(),
                    contentDescription = null,
                    tint = cobColor,
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    state.valueText.ifEmpty { state.text },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.carbsReqText.isNotEmpty()) {
                // Blinks as the COB icon did, the cue that carbs are needed
                val blink = rememberInfiniteTransition(label = "carbsReqBlink")
                val alpha by blink.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(animation = tween(durationMillis = 800), repeatMode = RepeatMode.Reverse),
                    label = "carbsReqAlpha"
                )
                Text(
                    state.carbsReqText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cobColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                )
            }
        }
    }
}
