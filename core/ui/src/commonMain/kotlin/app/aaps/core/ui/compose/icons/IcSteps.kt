package app.aaps.core.ui.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icon for steps: two footprints, the right one a step ahead. Each print is a sole and a heel.
 *
 * Bounding box: x: 5.5-18.5, y: 2.5-20.5 (viewport: 24x24, ~75% height)
 *
 * @see IcStepsIconPreview
 */
val IcSteps: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcSteps",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Left foot, behind
            ellipse(8f, 11.5f, 2.5f, 4f)
            ellipse(8.2f, 18.3f, 1.9f, 2.2f)
            // Right foot, ahead
            ellipse(16f, 6.5f, 2.5f, 4f)
            ellipse(15.8f, 13.3f, 1.9f, 2.2f)
        }
    }.build()
}

/** An ellipse around ([cx], [cy]) with radii [rx] and [ry], as two half arcs. */
private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    moveTo(cx - rx, cy)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * rx, dy1 = 0f)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * rx, dy1 = 0f)
    close()
}
