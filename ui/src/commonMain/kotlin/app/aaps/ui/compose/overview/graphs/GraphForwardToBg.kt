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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The x layout of the graphs (left and right edge of the plot, and which time is where), written on
 * every draw pass by [GraphGeometryReporter].
 *
 * Not Compose state, for the same reason as [VisibleRangeHolder]: writing state from the draw pass
 * fights Vico's gesture handling. It is only read during a pinch and during press and hold.
 *
 * All graphs share one x layout (see the notes at the top of GraphUtils.kt and GraphEndAxis.kt), so
 * the layout of the treatment belt, which is always there, is used for every graph. The BG graph gets
 * no decoration of its own, because a decoration on BG was found to break its pinch zoom.
 */
class GraphGeometryHolder {

    @Volatile var left = 0f
    @Volatile var right = 0f

    /** Graph x (minutes from the start of the graph) at [left]. */
    @Volatile var firstVisibleX = 0.0

    /** Graph x per pixel; 0 until the first draw. */
    @Volatile var xPerPixel = 0.0

    /** The graph x at the canvas position [px], kept inside the plot; null before the first draw. */
    fun xAt(px: Float): Double? {
        if (xPerPixel <= 0.0 || right <= left) return null
        return firstVisibleX + (px.coerceIn(left, right) - left) * xPerPixel
    }
}

/** Copies the current x layout of a chart into [holder] on every draw pass. Draws nothing. */
class GraphGeometryReporter(private val holder: GraphGeometryHolder) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            holder.left = layerBounds.left
            holder.right = layerBounds.right
            val xStep = ranges.xStep
            val xSpacing = layerDimensions.xSpacing
            if (xStep == 0.0 || xSpacing <= 0f) return
            // Same transform as VisibleRangeReporter
            holder.firstVisibleX = ranges.minX + xStep * (scroll - layerDimensions.startPadding) / xSpacing
            holder.xPerPixel = xStep / xSpacing
        }
    }

    // No equals/hashCode on purpose: compare by identity, like VisibleRangeReporter.
}

/** What a touch turned into: it moved past the touch slop, it was held still, or it ended first. */
private enum class TouchKind { UP_OR_DOWN, SIDEWAYS, PINCH, HOLD, ENDED }

/**
 * Lets the user pan and zoom from any graph, not only from BG, and press and hold on it.
 *
 * Only the BG graph scrolls and zooms by itself; the other graphs follow it. Put this on another
 * graph's modifier: a sideways drag there scrolls BG ([bgScroll]) and a pinch zooms BG ([bgZoom]).
 * An up or down drag is left alone, so the screen still scrolls. A tap is left alone too.
 * A finger held still for a long press starts press and hold in [inspect], from graph
 * [graphIndex]; it then follows the finger until it is lifted, like on the BG graph.
 *
 * BG is then moved by code, which `onScrollOrZoomGesture` on the BG graph does not see, so
 * [onGesture] is called when such a drag or pinch starts and when it ends. Events are read in the
 * Initial pass, before the chart's own handling.
 */
@Composable
internal fun rememberForwardToBgInput(
    geometry: GraphGeometryHolder,
    bgScroll: VicoScrollState,
    bgZoom: VicoZoomState,
    onGesture: () -> Unit,
    inspect: GraphInspectState,
    graphIndex: Int
): Modifier {
    val scope = rememberCoroutineScope()
    val currentScroll by rememberUpdatedState(bgScroll)
    val currentZoom by rememberUpdatedState(bgZoom)
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentGraphIndex by rememberUpdatedState(graphIndex)

    // Remembered: the handler reads everything through the updated states above, so the same
    // modifier can be returned on every pass and the chart it is attached to can skip recomposing.
    return remember(geometry, inspect) {
        Modifier.pointerInput(geometry, inspect) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // No move and no lift within the long press time is a hold
                val kind = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitTouchKind(down.id, down.position) } ?: TouchKind.HOLD

                when (kind) {
                    TouchKind.UP_OR_DOWN, TouchKind.ENDED -> Unit

                    TouchKind.HOLD       -> {
                        currentOnGesture()
                        inspect.startHold(currentGraphIndex, geometry.xAt(down.position.x))
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Eaten, so the screen does not scroll while the finger moves along the graph
                                event.changes.forEach { it.consume() }
                                if (!change.pressed) break
                                geometry.xAt(change.position.x)?.let { inspect.targetX = it }
                            }
                        } finally {
                            inspect.endHold()
                        }
                        currentOnGesture()
                    }

                    TouchKind.SIDEWAYS   -> {
                        val target = currentScroll
                        currentOnGesture()
                        val startScroll = target.value
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            event.changes.forEach { it.consume() }
                            if (!change.pressed || event.changes.size > 1) break
                            val scroll = startScroll - (change.position.x - down.position.x)
                            scope.launch { target.scroll(Scroll.Absolute.pixels(scroll)) }
                        }
                        currentOnGesture()
                    }

                    TouchKind.PINCH      -> {
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
    }
}

/** Waits until the touch [id] that went down at [start] moves past the touch slop, gets a second finger, or ends. */
private suspend fun AwaitPointerEventScope.awaitTouchKind(id: PointerId, start: Offset): TouchKind {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return TouchKind.PINCH
        val change = event.changes.firstOrNull { it.id == id } ?: return TouchKind.ENDED
        if (!change.pressed) return TouchKind.ENDED
        val moved = change.position - start
        if (moved.getDistance() > viewConfiguration.touchSlop) {
            return if (abs(moved.x) > abs(moved.y)) TouchKind.SIDEWAYS else TouchKind.UP_OR_DOWN
        }
    }
}
