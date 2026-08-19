package app.aaps.wear.watchfaces.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure drawing logic for the glucose-history-around-the-bezel graphic, factored out of
 * [BezelHistoryView] so it can be shared with `BezelHistoryComplicationService`, which renders the
 * same graphic to an off-screen [android.graphics.Bitmap] for Watch Face Format's PHOTO_IMAGE
 * complication slot (WFF can't run arbitrary Canvas code itself). Both call sites must stay visually
 * identical, so all geometry/color constants and draw steps live here once. Geometry is expressed in
 * the same 200x200 logical-unit space as the approved mockup (center at 100,100; 0deg = 12 o'clock,
 * clockwise-positive) and scaled at draw time to the target width/height.
 */
object BezelHistoryRenderer {

    const val GRAPH_V_MIN = 2.2
    const val GRAPH_V_MAX = 13.3
    const val GRAPH_SWEEP = 326f
    const val GRAPH_R0 = 87f
    const val GRAPH_AMP = 16f
    const val GRAPH_R_MIN = 70f
    const val GRAPH_R_MAX = 98f

    const val DEFAULT_LOW = 4.0
    const val DEFAULT_HIGH = 9.0
    const val DEFAULT_TARGET = 6.0

    private const val LINE_STROKE_WIDTH = 2.8f
    private const val TARGET_STROKE_WIDTH = 1f
    private const val NOW_DOT_RADIUS = 3.75f
    private const val AMBIENT_STROKE_FACTOR = 0.6f

    private const val LINE_ALPHA_MIN = 0.22f
    private const val LINE_ALPHA_MAX = 0.8f
    private const val BAND_ALPHA = 23
    private const val TARGET_ALPHA = 102

    private val COLOR_RED = Color.parseColor("#FF6B5E")
    private val COLOR_GREEN = Color.parseColor("#8FE3B0")
    private val COLOR_AMBER = Color.parseColor("#F6D55C")
    private val COLOR_LAVENDER = Color.parseColor("#B8A6F5")

    fun normalize(v: Double): Double =
        ((v - GRAPH_V_MIN) / (GRAPH_V_MAX - GRAPH_V_MIN)).coerceIn(0.0, 1.0)

    fun valueToRadius(v: Double): Float =
        (GRAPH_R0 + (normalize(v) - 0.5) * 2 * GRAPH_AMP).toFloat().coerceIn(GRAPH_R_MIN, GRAPH_R_MAX)

    /**
     * Paint instances for one bezel-history drawer. [BezelHistoryView] keeps a single instance across
     * redraws (it can render at animation frame rate during the reveal) to avoid per-frame
     * allocation; a complication data source, which renders at most once per
     * `UPDATE_PERIOD_SECONDS`, can just create a fresh one per request.
     */
    class Paints {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }

    /**
     * Draws the full graphic (zone bands, target ring, history line, now/leading dot) onto [canvas]
     * sized [widthPx]x[heightPx]. [revealFraction] is 1f for the normal/idle state; see
     * [BezelHistoryView.playRevealAnimation] for the oscilloscope-style reveal that drives it below 1f.
     */
    fun draw(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        points: List<GlucosePoint>,
        lowThreshold: Double,
        highThreshold: Double,
        targetValue: Double,
        ambient: Boolean,
        revealFraction: Float,
        paints: Paints
    ) {
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val scale = minOf(widthPx, heightPx) / 200f
        if (scale <= 0f) return

        if (!ambient) drawZoneBands(canvas, cx, cy, scale, lowThreshold, highThreshold, paints)
        drawTargetRing(canvas, cx, cy, scale, targetValue, ambient, paints)

        val revealing = revealFraction < 1f
        if (points.size >= 2) drawHistoryLine(canvas, points, cx, cy, scale, ambient, revealFraction, lowThreshold, highThreshold, paints)
        if (points.isNotEmpty()) {
            if (revealing) drawLeadingDot(canvas, points, cx, cy, scale, ambient, revealFraction, lowThreshold, highThreshold, paints)
            else drawNowDot(canvas, points, cx, cy, scale, ambient, lowThreshold, highThreshold, paints)
        }
    }

    // Canvas's own trig/drawArc APIs measure 0deg from 3 o'clock; this graphic's angle convention
    // measures from 12 o'clock, so subtract 90 degrees before converting to canvas coordinates.
    private fun polarToPoint(cx: Float, cy: Float, angleDeg: Float, radiusPx: Float): Pair<Float, Float> {
        val rad = Math.toRadians((angleDeg - 90f).toDouble())
        val x = cx + radiusPx * cos(rad).toFloat()
        val y = cy + radiusPx * sin(rad).toFloat()
        return x to y
    }

    private fun zoneColor(mmol: Double, lowThreshold: Double, highThreshold: Double): Int = when {
        mmol < lowThreshold -> COLOR_RED
        mmol > highThreshold -> COLOR_AMBER
        else -> COLOR_GREEN
    }

    private fun drawHistoryLine(
        canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float,
        ambient: Boolean, revealFraction: Float, lowThreshold: Double, highThreshold: Double, paints: Paints
    ) {
        val linePaint = paints.line
        val n = points.size
        val segments = n - 1
        linePaint.strokeWidth = (if (ambient) LINE_STROKE_WIDTH * AMBIENT_STROKE_FACTOR else LINE_STROKE_WIDTH) * scale
        linePaint.isAntiAlias = !ambient

        // Points are oldest-first (index 0 = oldest, index n-1 = "now"), so segment i already runs
        // old -> new; capping the loop at the reveal fraction draws the trace in chronological order
        // for free, no reordering needed.
        val visibleSegments = (segments * revealFraction).toInt().coerceIn(0, segments)
        for (i in 0 until visibleSegments) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val angle0 = -((n - 1 - i).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
            val angle1 = -((n - 1 - (i + 1)).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
            val r0 = valueToRadius(p0.mmol) * scale
            val r1 = valueToRadius(p1.mmol) * scale
            val (x0, y0) = polarToPoint(cx, cy, angle0, r0)
            val (x1, y1) = polarToPoint(cx, cy, angle1, r1)

            val fraction = if (segments <= 1) 1f else i.toFloat() / (segments - 1).toFloat()
            val alpha = ((LINE_ALPHA_MIN + fraction * (LINE_ALPHA_MAX - LINE_ALPHA_MIN)) * 255).toInt()

            linePaint.color = if (ambient) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                val avgMmol = (p0.mmol + p1.mmol) / 2.0
                val base = zoneColor(avgMmol, lowThreshold, highThreshold)
                Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            }
            canvas.drawLine(x0, y0, x1, y1, linePaint)
        }
    }

    private fun drawZoneBands(canvas: Canvas, cx: Float, cy: Float, scale: Float, lowThreshold: Double, highThreshold: Double, paints: Paints) {
        val lowR = valueToRadius(lowThreshold)
        val highR = valueToRadius(highThreshold)
        drawBand(canvas, cx, cy, scale, GRAPH_R_MIN, lowR, COLOR_RED, paints)
        drawBand(canvas, cx, cy, scale, lowR, highR, COLOR_GREEN, paints)
        drawBand(canvas, cx, cy, scale, highR, GRAPH_R_MAX, COLOR_AMBER, paints)
    }

    private fun drawBand(canvas: Canvas, cx: Float, cy: Float, scale: Float, innerR: Float, outerR: Float, color: Int, paints: Paints) {
        val lo = minOf(innerR, outerR)
        val hi = maxOf(innerR, outerR)
        val bandWidth = hi - lo
        if (bandWidth <= 0f) return
        val midR = (lo + hi) / 2f
        val bandPaint = paints.band
        bandPaint.strokeWidth = bandWidth * scale
        bandPaint.color = Color.argb(BAND_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(cx, cy, midR * scale, bandPaint)
    }

    private fun drawTargetRing(canvas: Canvas, cx: Float, cy: Float, scale: Float, targetValue: Double, ambient: Boolean, paints: Paints) {
        val targetPaint = paints.target
        val r = valueToRadius(targetValue) * scale
        targetPaint.strokeWidth = (if (ambient) TARGET_STROKE_WIDTH * AMBIENT_STROKE_FACTOR else TARGET_STROKE_WIDTH) * scale
        targetPaint.isAntiAlias = !ambient
        targetPaint.pathEffect = DashPathEffect(floatArrayOf(2f * scale, 2f * scale), 0f)
        targetPaint.color = if (ambient) {
            Color.argb(TARGET_ALPHA, 255, 255, 255)
        } else {
            Color.argb(TARGET_ALPHA, Color.red(COLOR_LAVENDER), Color.green(COLOR_LAVENDER), Color.blue(COLOR_LAVENDER))
        }
        canvas.drawCircle(cx, cy, r, targetPaint)
    }

    private fun drawNowDot(canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float, ambient: Boolean, lowThreshold: Double, highThreshold: Double, paints: Paints) {
        val dotPaint = paints.dot
        val now = points.last()
        val r = valueToRadius(now.mmol) * scale
        val (x, y) = polarToPoint(cx, cy, 0f, r)
        dotPaint.isAntiAlias = !ambient
        dotPaint.color = if (ambient) Color.WHITE else zoneColor(now.mmol, lowThreshold, highThreshold)
        val dotRadius = (if (ambient) NOW_DOT_RADIUS * AMBIENT_STROKE_FACTOR else NOW_DOT_RADIUS) * scale
        canvas.drawCircle(x, y, dotRadius, dotPaint)
    }

    /** "Pen tip" of the reveal animation: interpolated smoothly within the segment currently being
     *  drawn, rather than snapping point-to-point, so the trace looks like a continuously moving
     *  oscilloscope beam rather than a stepped one. */
    private fun drawLeadingDot(
        canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float,
        ambient: Boolean, revealFraction: Float, lowThreshold: Double, highThreshold: Double, paints: Paints
    ) {
        val dotPaint = paints.dot
        val n = points.size
        val segments = n - 1
        if (segments <= 0) return
        val exact = (segments * revealFraction).coerceIn(0f, segments.toFloat())
        val segmentIndex = exact.toInt().coerceIn(0, segments - 1)
        val segmentFraction = exact - segmentIndex
        val p0 = points[segmentIndex]
        val p1 = points[minOf(segmentIndex + 1, n - 1)]
        val mmol = p0.mmol + (p1.mmol - p0.mmol) * segmentFraction
        val angle0 = -((n - 1 - segmentIndex).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
        val angle1 = -((n - 1 - (segmentIndex + 1)).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
        val angle = angle0 + (angle1 - angle0) * segmentFraction
        val r = valueToRadius(mmol) * scale
        val (x, y) = polarToPoint(cx, cy, angle, r)
        dotPaint.isAntiAlias = !ambient
        dotPaint.color = if (ambient) Color.WHITE else zoneColor(mmol, lowThreshold, highThreshold)
        val dotRadius = (if (ambient) NOW_DOT_RADIUS * AMBIENT_STROKE_FACTOR else NOW_DOT_RADIUS) * scale
        canvas.drawCircle(x, y, dotRadius, dotPaint)
    }
}
