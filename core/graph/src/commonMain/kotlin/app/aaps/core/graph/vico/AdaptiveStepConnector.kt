package app.aaps.core.graph.vico

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import kotlin.math.abs

/**
 * Adaptive Interpolator that switches between step and line segments based on slope.
 *
 * Between each pair of consecutive points:
 *  - a steep rise (|dy/dx| > 1.0, >45° on screen): draws a step (horizontal then vertical)
 *  - anything else: draws a straight line
 *
 * Made for COB: a carb entry is a jump up and is drawn as one, while absorption is always a
 * line. Steepness is measured on screen, so fast absorption at a close zoom is also steeper
 * than 45°; drawing falls as steps too turned it into a staircase.
 *
 * Replaces the older `PointConnector`-based implementation (Vico 2.x API),
 * which is now deprecated in favor of `Interpolator`.
 */
val AdaptiveStep: LineCartesianLayer.Interpolator = object : LineCartesianLayer.Interpolator {
    override fun interpolate(
        context: CartesianDrawingContext,
        path: Path,
        points: List<Offset>,
        visibleIndexRange: IntRange
    ) {
        for (index in visibleIndexRange) {
            val point = points[index]
            if (index == visibleIndexRange.first) {
                path.moveTo(point.x, point.y)
                continue
            }
            val prev = points[index - 1]
            val dx = abs(point.x - prev.x)
            val dy = abs(point.y - prev.y)
            val isSteep = if (dx > 0.0001f) (dy / dx) > 1.0f else true
            // Screen y grows downwards, so a rise is a smaller y
            val isRise = point.y < prev.y
            if (isSteep && isRise) {
                path.lineTo(point.x, prev.y)
                path.lineTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }
    }
}
