package app.aaps.wear.watchfaces.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
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

    /**
     * The data arc runs from 3 o'clock clockwise round to 12 o'clock, leaving the quadrant between
     * 12 and 3 free for the current reading. Angles are measured from 12 o'clock, clockwise.
     */
    const val GRAPH_START_DEG = 90f
    const val GRAPH_SWEEP = 270f

    /**
     * The arc is a fixed twelve hour window, so each twelfth of it (22.5 degrees) is one hour and the
     * bezel can be read as a clock. Everything is positioned by timestamp rather than by index, so
     * the trace lines up with the hour divisions even when readings are unevenly spaced or missing.
     */
    const val WINDOW_MS = 12 * 60 * 60 * 1000L

    private fun angleForTime(t: Long, windowStart: Long) =
        GRAPH_START_DEG + GRAPH_SWEEP * ((t - windowStart).toFloat() / WINDOW_MS.toFloat())

    /** Radial band the trace and the boundary rings are laid out within, in 200-unit space. */
    const val GRAPH_R_MIN = 70f
    const val GRAPH_R_MAX = 98f

    /**
     * Fallbacks only. The band's extent and the colour thresholds come from the profile carried on
     * each reading, so the bezel agrees with what AAPS itself treats as low and high; these apply
     * only before the phone has reported them.
     */
    const val DEFAULT_LOW = 4.0
    const val DEFAULT_HIGH = 9.0

    /** In-range marker. Deliberately 5.5 rather than a midpoint of low/high, which would drift. */
    const val DEFAULT_TARGET = 5.5

    private const val LINE_STROKE_WIDTH = 2.8f
    private const val NOW_DOT_RADIUS = 3.75f
    private const val AMBIENT_STROKE_FACTOR = 0.6f

    // Fully opaque along the whole trace. This drops the old recency fade, so age is no longer
    // encoded in the line's alpha; the now-dot is what marks the current end.
    private const val LINE_ALPHA_MIN = 1.0f
    private const val LINE_ALPHA_MAX = 1.0f

    /** Ambient keeps the rings present but restrained, since a permanently lit full-brightness ring
     *  is exactly the burn-in risk OLED watch faces are supposed to avoid. */


    private val COLOR_RED = Color.parseColor("#C30909")
    private val COLOR_GREEN = Color.parseColor("#00CD23")
    private val COLOR_AMBER = Color.parseColor("#FF7800")

    /**
     * In-target band and target line.
     *
     * The band spans low..high as a single neutral fill rather than three coloured rings. Its own
     * edges are the low and high thresholds, so the boundaries still read without colour competing
     * with the trace, which is the only thing on the bezel that should carry clinical colour.
     */
    private val COLOR_TARGET_BAND = Color.parseColor("#FFFFFF")

    /** The in-target band is a primary reference, so it reads clearly rather than as a hint. */
    private const val BAND_ALPHA = 64
    private const val BAND_AMBIENT_ALPHA = 32

    /** Preferred block length. Widened automatically when the history is long; see [bucketMsFor]. */
    private const val BUCKET_MS = 60 * 60 * 1000L

    /**
     * Upper bound on how many blocks the bezel is cut into.
     *
     * This exists because the round caps are expensive in angle: each block is inset by half the
     * band's stroke width at both ends, which at a typical band thickness is several degrees a side.
     * With AAPS sending ~400 readings, hourly blocks came out around 9 degrees wide against a ~16
     * degree total inset, so every block collapsed to a negative sweep and the band silently did not
     * draw at all. Capping the count keeps every block comfortably wider than its own end caps.
     */

    /** Each hour block is subdivided into slices of this length, each coloured by its own reading. */
    private const val SLICE_MS = 5 * 60 * 1000L

    /** Hair of overdraw between slices so antialiased seams do not show as hairlines. */
    private const val SEAM_OVERLAP_DEG = 0.35f

    /**
     * The band carries the clinical colour, but sits behind the trace, so it is muted rather than
     * saturated: pulled toward grey and held below full opacity so it reads as a background scale.
     */
    private const val SLICE_ALPHA = 165
    private const val SLICE_AMBIENT_ALPHA = 105

    /** How far each slice colour is pulled toward neutral grey. 0 keeps it vivid, 1 makes it grey. */
    private const val SLICE_DESATURATION = 0.32f
    private val COLOR_SLICE_NEUTRAL = Color.parseColor("#8A8A93")

    /** The trace is white: colour is the band's job, position is the trace's. */
    private val COLOR_TRACE = Color.parseColor("#FFFFFF")

    /**
     * Visual gap between segments, on top of the room the round caps already take. Round caps
     * overhang each arc end by half the stroke width, so without accounting for that the segments
     * would meet even at a nominally positive gap.
     */

    /**
     * Current-reading label, set on an arc across the top like the system's charging clock.
     * Radius 60 is deliberate: the WFF face's gauge rings land near 50 units in this bitmap's space
     * and the history band starts at 70, so this sits in the empty annulus between them.
     */
    private const val ARC_TEXT_RADIUS = 60f
    private const val ARC_TEXT_SIZE = 11f
    private const val ARC_TEXT_SWEEP_DEG = 150f

    /** Head/tail breathing room so a value at an extreme is not drawn exactly on the band edge. */
    private const val RANGE_PADDING_FRACTION = 0.08

    /** Guards a perfectly flat trace (or a single reading) from collapsing the scale to zero span. */
    private const val MIN_SPAN_MMOL = 2.0

    /**
     * Fixed display range. Everything from [GRAPH_V_MIN] to [GRAPH_V_MAX] maps onto the radial band.
     *
     * This replaces an earlier scale that fitted itself to whatever data was on screen. That version
     * never clamped, which sounded better than it looked: it amplified ordinary sensor noise to fill
     * the whole band, so a quiet day rendered as a mountain range and the same shape meant something
     * different every render. A fixed range means a given radius always means the same glucose value,
     * which is what makes the band behind it meaningful.
     *
     * Values outside the range are clamped to the edge of the band rather than drawn outside it.
     */
    const val GRAPH_V_MIN = 2.0
    const val GRAPH_V_MAX = 11.0

    class Scale {

        fun radius(v: Double): Float {
            val f = ((v - GRAPH_V_MIN) / (GRAPH_V_MAX - GRAPH_V_MIN)).coerceIn(0.0, 1.0)
            return (GRAPH_R_MIN + f * (GRAPH_R_MAX - GRAPH_R_MIN)).toFloat()
        }
    }

    /** The scale no longer depends on the data, but callers still go through this. */
    fun scaleFor(points: List<GlucosePoint>, low: Double, target: Double, high: Double): Scale = Scale()

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
        val arcText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            // "sans-serif-rounded" is the platform's rounded family; on devices without it this
            // resolves to the default sans, which is a graceful degradation rather than a failure.
            typeface = Typeface.create("sans-serif-rounded", Typeface.NORMAL)
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
        paints: Paints,
        /** Pre-formatted "value arrow delta", e.g. "10.0 \u2197 -0.2". Composed by the caller, which
         *  knows the display units; the renderer only decides where it goes and what colour it is. */
        currentLabel: String? = null
    ) {
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val scale = minOf(widthPx, heightPx) / 200f
        if (scale <= 0f) return

        // One scale per render, shared by the rings and the trace, so the boundaries always sit
        // exactly where the trace measures them against.
        val vScale = scaleFor(points, lowThreshold, targetValue, highThreshold)

        drawBoundaryRings(canvas, cx, cy, scale, vScale, lowThreshold, targetValue, highThreshold, ambient, points, paints)

        val revealing = revealFraction < 1f
        if (points.size >= 2) drawHistoryLine(canvas, points, cx, cy, scale, vScale, ambient, revealFraction, lowThreshold, highThreshold, paints)
        if (points.isNotEmpty()) {
            if (revealing) drawLeadingDot(canvas, points, cx, cy, scale, vScale, ambient, revealFraction, lowThreshold, highThreshold, paints)
            else drawNowDot(canvas, points, cx, cy, scale, vScale, ambient, lowThreshold, highThreshold, paints)
        }

        if (!currentLabel.isNullOrBlank() && points.isNotEmpty()) {
            drawArcLabel(canvas, currentLabel, cx, cy, scale, points.last().mmol, lowThreshold, highThreshold, ambient, paints)
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
        canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float, vScale: Scale,
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
        val windowStart = points.last().timestampMillis - WINDOW_MS
        val visibleSegments = (segments * revealFraction).toInt().coerceIn(0, segments)
        for (i in 0 until visibleSegments) {
            val p0 = points[i]
            val p1 = points[i + 1]
            // Anything older than the window falls outside the arc and is simply not drawn.
            if (p1.timestampMillis < windowStart) continue
            val angle0 = angleForTime(p0.timestampMillis, windowStart)
            val angle1 = angleForTime(p1.timestampMillis, windowStart)
            val r0 = vScale.radius(p0.mmol) * scale
            val r1 = vScale.radius(p1.mmol) * scale
            val (x0, y0) = polarToPoint(cx, cy, angle0, r0)
            val (x1, y1) = polarToPoint(cx, cy, angle1, r1)

            val fraction = if (segments <= 1) 1f else i.toFloat() / (segments - 1).toFloat()
            val alpha = ((LINE_ALPHA_MIN + fraction * (LINE_ALPHA_MAX - LINE_ALPHA_MIN)) * 255).toInt()

            // One flat blue for the whole trace. Zone colour now lives in the band beneath it, so
            // colouring the line too would say the same thing twice and fight the band for attention.
            linePaint.color = if (ambient) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                Color.argb(alpha, Color.red(COLOR_TRACE), Color.green(COLOR_TRACE), Color.blue(COLOR_TRACE))
            }
            canvas.drawLine(x0, y0, x1, y1, linePaint)
        }
    }

    /**
     * The in-target region drawn as one translucent band, with a thin line at the target value
     * inside it. Replaces the three saturated boundary rings: those made the bezel read as three
     * competing signals when only the trace's own position actually matters.
     */
    /**
     * The in-target region drawn as one rounded segment per hour of history rather than a continuous
     * ring, echoing the segmented progress indicators used elsewhere on the face. Segments come close
     * to each other without touching, which gives the bezel a sense of elapsed time as well as of
     * range: each block is an hour, so the trace can be read against the clock, not just the band.
     *
     * Falls back to a continuous ring when there is too little history to bucket, since a single
     * fragment floating on its own would read as a bug rather than a design.
     */
    /**
     * The in-target region as one continuous bar spanning the twelve hour window, coloured in five
     * minute slices from the readings inside each.
     *
     * The bar is continuous: slices butt together with no gaps, so it reads as a single progress bar
     * whose colour changes along its length rather than as separate blocks. Because the window is a
     * fixed twelve hours, each twelfth of the bar is an hour.
     */
    private fun drawBoundaryRings(
        canvas: Canvas, cx: Float, cy: Float, scale: Float, vScale: Scale,
        lowThreshold: Double, targetValue: Double, highThreshold: Double, ambient: Boolean,
        points: List<GlucosePoint>, paints: Paints
    ) {
        val rLow = vScale.radius(lowThreshold)
        val rHigh = vScale.radius(highThreshold)
        val inner = minOf(rLow, rHigh)
        val outer = maxOf(rLow, rHigh)
        val width = outer - inner
        if (width <= 0f || points.isEmpty()) return

        val midR = (inner + outer) / 2f
        val strokePx = width * scale
        val bandPaint = paints.band
        bandPaint.style = Paint.Style.STROKE
        bandPaint.isAntiAlias = !ambient
        bandPaint.strokeWidth = strokePx
        // Butt everywhere, with no end caps at all: the bar simply starts and stops square.
        bandPaint.strokeCap = Paint.Cap.BUTT

        val newest = points.last().timestampMillis
        val windowStart = newest - WINDOW_MS
        val rect = RectF(cx - midR * scale, cy - midR * scale, cx + midR * scale, cy + midR * scale)

        var sliceStart = windowStart
        while (sliceStart < newest) {
            val sliceEnd = minOf(sliceStart + SLICE_MS, newest)
            val inSlice = points.filter { it.timestampMillis in sliceStart..sliceEnd }
            if (inSlice.isNotEmpty()) {
                val a0 = angleForTime(sliceStart, windowStart)
                val a1 = angleForTime(sliceEnd, windowStart)
                val mean = inSlice.sumOf { it.mmol } / inSlice.size
                bandPaint.color = sliceColor(mean, lowThreshold, highThreshold, ambient)
                // Overdraw by a hair so antialiased seams between slices do not show as hairlines.
                canvas.drawArc(rect, a0 - 90f, (a1 - a0) + SEAM_OVERLAP_DEG, false, bandPaint)
            }
            sliceStart = sliceEnd
        }
    }

    /** Zone colour pulled toward neutral so the bar reads as a background scale, not a warning light. */
    private fun sliceColor(mmol: Double, low: Double, high: Double, ambient: Boolean): Int {
        val base = zoneColor(mmol, low, high)
        fun mute(channel: Int, neutral: Int) =
            (channel + (neutral - channel) * SLICE_DESATURATION).toInt()
        return Color.argb(
            if (ambient) SLICE_AMBIENT_ALPHA else SLICE_ALPHA,
            mute(Color.red(base), Color.red(COLOR_SLICE_NEUTRAL)),
            mute(Color.green(base), Color.green(COLOR_SLICE_NEUTRAL)),
            mute(Color.blue(base), Color.blue(COLOR_SLICE_NEUTRAL))
        )
    }

    /**
     * Current reading set on an arc across the top of the bezel, following the curve the way the
     * system's charging clock does. Coloured by the same zone rule as the trace's leading point, so
     * the number and the dot always agree.
     */
    private fun drawArcLabel(
        canvas: Canvas, label: String, cx: Float, cy: Float, scale: Float,
        currentMmol: Double, lowThreshold: Double, highThreshold: Double, ambient: Boolean, paints: Paints
    ) {
        val textPaint = paints.arcText
        textPaint.textSize = ARC_TEXT_SIZE * scale
        textPaint.isAntiAlias = !ambient
        textPaint.color = if (ambient) Color.WHITE else zoneColor(currentMmol, lowThreshold, highThreshold)

        // Canvas angles run from 3 o'clock, so the arc is centred on -90 to put it at 12 o'clock.
        val r = ARC_TEXT_RADIUS * scale
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val path = Path().apply { arcTo(box, -90f - ARC_TEXT_SWEEP_DEG / 2f, ARC_TEXT_SWEEP_DEG, true) }

        // Align.CENTER centres on hOffset along the path, so the midpoint puts the text at 12 o'clock
        // regardless of how long it is; the label grows symmetrically either side of the top.
        val mid = PathMeasure(path, false).length / 2f
        canvas.drawTextOnPath(label, path, mid, 0f, textPaint)
    }

    private fun drawNowDot(canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float, vScale: Scale, ambient: Boolean, lowThreshold: Double, highThreshold: Double, paints: Paints) {
        val dotPaint = paints.dot
        val now = points.last()
        val r = vScale.radius(now.mmol) * scale
        val (x, y) = polarToPoint(cx, cy, GRAPH_START_DEG + GRAPH_SWEEP, r)
        dotPaint.isAntiAlias = !ambient
        dotPaint.color = if (ambient) Color.WHITE else zoneColor(now.mmol, lowThreshold, highThreshold)
        val dotRadius = (if (ambient) NOW_DOT_RADIUS * AMBIENT_STROKE_FACTOR else NOW_DOT_RADIUS) * scale
        canvas.drawCircle(x, y, dotRadius, dotPaint)
    }

    /** "Pen tip" of the reveal animation: interpolated smoothly within the segment currently being
     *  drawn, rather than snapping point-to-point, so the trace looks like a continuously moving
     *  oscilloscope beam rather than a stepped one. */
    private fun drawLeadingDot(
        canvas: Canvas, points: List<GlucosePoint>, cx: Float, cy: Float, scale: Float, vScale: Scale,
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
        val windowStart = points.last().timestampMillis - WINDOW_MS
        val angle0 = angleForTime(p0.timestampMillis, windowStart)
        val angle1 = angleForTime(p1.timestampMillis, windowStart)
        val angle = angle0 + (angle1 - angle0) * segmentFraction
        val r = vScale.radius(mmol) * scale
        val (x, y) = polarToPoint(cx, cy, angle, r)
        dotPaint.isAntiAlias = !ambient
        dotPaint.color = if (ambient) Color.WHITE else zoneColor(mmol, lowThreshold, highThreshold)
        val dotRadius = (if (ambient) NOW_DOT_RADIUS * AMBIENT_STROKE_FACTOR else NOW_DOT_RADIUS) * scale
        canvas.drawCircle(x, y, dotRadius, dotPaint)
    }
}
