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
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.concurrent.Volatile
import kotlin.math.abs
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

/** What a touch turned into: a move past the touch slop, or a press held without moving. */
private enum class TouchKind { UP_OR_DOWN, SIDEWAYS, PINCH, HOLD }

/**
 * Lets the user pan and zoom from any graph, not only from BG.
 *
 * Only the BG graph scrolls and zooms by itself; the other graphs follow it. Put this on another
 * graph's modifier: a sideways drag there scrolls BG ([bgScroll]) and a pinch zooms BG ([bgZoom]).
 * An up or down drag is left alone, so the screen still scrolls. A tap is left alone too.
 *
 * BG is then moved by code, which `onScrollOrZoomGesture` on the BG graph does not see, so
 * [onGesture] is called when such a drag or pinch starts and when it ends. Events are read in the
 * Initial pass, before the chart's own handling.
 *
 * A press held without moving shows the values pop-up, the same as on the BG graph ([inspect]).
 * The finger can then move sideways to other times until it is lifted.
 */
@Composable
internal fun rememberForwardToBgInput(
    geometry: GraphGeometryHolder,
    bgScroll: VicoScrollState,
    bgZoom: VicoZoomState,
    inspect: GraphInspectState?,
    onGesture: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val currentScroll by rememberUpdatedState(bgScroll)
    val currentZoom by rememberUpdatedState(bgZoom)
    val currentInspect by rememberUpdatedState(inspect)
    val currentOnGesture by rememberUpdatedState(onGesture)

    // Remembered: the handler reads everything through the updated states above, so the same
    // modifier can be returned on every pass and the chart it is attached to can skip recomposing.
    return remember(geometry) {
        Modifier.pointerInput(geometry) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var kind: TouchKind? = null
                var lifted = false
                // Without a pop-up to show there is no time limit: wait for a move as before
                val holdTime = if (currentInspect != null) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE
                withTimeoutOrNull(holdTime) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size > 1) {
                            kind = TouchKind.PINCH
                            return@withTimeoutOrNull
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            lifted = true
                            return@withTimeoutOrNull
                        }
                        val moved = change.position - down.position
                        if (moved.getDistance() > viewConfiguration.touchSlop) {
                            kind = if (abs(moved.x) > abs(moved.y)) TouchKind.SIDEWAYS else TouchKind.UP_OR_DOWN
                            return@withTimeoutOrNull
                        }
                    }
                }
                if (lifted) return@awaitEachGesture

                when (kind ?: TouchKind.HOLD) {
                    TouchKind.UP_OR_DOWN -> Unit

                    TouchKind.HOLD       -> {
                        val state = currentInspect ?: return@awaitEachGesture
                        state.pointerDown = true
                        geometry.xAt(down.position.x)?.let { state.targetX = it }
                        state.fingerDown = true
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // The graph must not scroll while the pop-up follows the finger
                            event.changes.forEach { it.consume() }
                            if (!change.pressed) break
                            geometry.xAt(change.position.x)?.let { state.targetX = it }
                        }
                        state.pointerDown = false
                        state.fingerDown = false
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
