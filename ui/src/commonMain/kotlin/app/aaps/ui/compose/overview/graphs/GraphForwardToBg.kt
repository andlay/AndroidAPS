package app.aaps.ui.compose.overview.graphs

import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch

/**
 * The x layout of the graphs (left and right edge of the plot), written on every draw pass by
 * [GraphGeometryReporter].
 *
 * Not Compose state, for the same reason as [VisibleRangeHolder]: writing state from the draw pass
 * fights Vico's gesture handling. It is only read during a pinch.
 *
 * All graphs share one x layout (see the notes at the top of GraphUtils.kt), so the layout of the
 * fixed IOB graph is used for every graph. The BG graph gets no decoration of its own, because a
 * decoration on BG was found to break its pinch zoom.
 */
class GraphGeometryHolder {

    @Volatile var left = 0f
    @Volatile var right = 0f
    @Volatile var startPadding = 0f
    @Volatile var xSpacing = 0f
    @Volatile var xStep = 0.0
    @Volatile var minX = 0.0
    @Volatile var scroll = 0f

    /** Graph x (minutes from the start of the graph) at [px] across the graph, or null before the first draw. */
    fun xAt(px: Float): Double? {
        if (xSpacing <= 0f || xStep == 0.0) return null
        return minX + (px - left - startPadding + scroll) / xSpacing * xStep
    }
}

/** Copies the current x layout of a chart into [holder] on every draw pass. Draws nothing. */
class GraphGeometryReporter(private val holder: GraphGeometryHolder) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        holder.left = context.layerBounds.left
        holder.right = context.layerBounds.right
        holder.startPadding = context.layerDimensions.startPadding
        holder.xSpacing = context.layerDimensions.xSpacing
        holder.xStep = context.ranges.xStep
        holder.minX = context.ranges.minX
        holder.scroll = context.scroll
    }

    // No equals/hashCode on purpose: compare by identity, like VisibleRangeReporter.
}

/**
 * Lets the user zoom from any graph, not only from BG.
 *
 * Only the BG graph zooms by itself; the other graphs follow it. Put this on another graph's
 * modifier: a pinch there zooms BG ([bgZoom]) around the fingers. One finger is left alone: a
 * sideways drag scrolls BG through `scrollsBgGraph`, and a press and hold shows the pop-up.
 *
 * BG is then zoomed by code, which `onScrollOrZoomGesture` on the BG graph does not see, so
 * [onGesture] is called when a pinch starts and when it ends. Events are read in the Initial pass,
 * before the chart's own handling, and consumed only while two fingers are down.
 */
@Composable
internal fun rememberPinchZoomsBg(
    geometry: GraphGeometryHolder,
    bgZoom: VicoZoomState,
    onGesture: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val currentZoom by rememberUpdatedState(bgZoom)
    val currentOnGesture by rememberUpdatedState(onGesture)

    // Remembered: the handler reads everything through the updated states above, so the same
    // modifier can be returned on every pass and the chart it is attached to can skip recomposing.
    return remember(geometry) {
        Modifier.pointerInput(geometry) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Wait for a second finger; the gesture ends when all fingers are up
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { it.pressed }) return@awaitEachGesture
                    if (event.changes.count { it.pressed } > 1) break
                }
                val target = currentZoom
                currentOnGesture()
                var zoom = target.value
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { it.pressed }) break
                    val zoomChange = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = false)
                    event.changes.forEach { it.consume() }
                    if (zoomChange == 1f || centroid == Offset.Unspecified) continue
                    zoom = (zoom * zoomChange).coerceIn(target.valueRange)
                    val width = geometry.right - geometry.left
                    val bias = if (width > 0f) ((centroid.x - geometry.left) / width).coerceIn(0f, 1f) else 0.5f
                    val fixed = Zoom.fixed(zoom)
                    scope.launch { target.animateZoom(fixed, snap(), bias) }
                }
                currentOnGesture()
            }
        }
    }
}
