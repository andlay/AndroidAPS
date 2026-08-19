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

    const val GRAPH_SWEEP = 326f

    /** Radial band the trace and the boundary rings are laid out within, in 200-unit space. */
    const val GRAPH_R_MIN = 70f
    const val GRAPH_R_MAX = 98f

    const val DEFAULT_LOW = 4.0
    const val DEFAULT_HIGH = 9.0

    /** In-range marker. Deliberately 5.5 rather than a midpoint of low/high, which would drift. */
    const val DEFAULT_TARGET = 5.5

    private const val LINE_STROKE_WIDTH = 2.8f
    private const val BOUNDARY_STROKE_WIDTH = 1.6f
    private const val NOW_DOT_RADIUS = 3.75f
    private const val AMBIENT_STROKE_FACTOR = 0.6f

    private const val LINE_ALPHA_MIN = 0.22f
    private const val LINE_ALPHA_MAX = 0.8f

    /** Ambient keeps the rings present but restrained, since a permanently lit full-brightness ring
     *  is exactly the burn-in risk OLED watch faces are supposed to avoid. */
    private const val BOUNDARY_AMBIENT_ALPHA = 110

    /** The target line sits inside the band, so it needs presence without shouting. */
    private const val TARGET_LINE_ALPHA = 175

    private val COLOR_RED = Color.parseColor("#FF6B5E")
    private val COLOR_GREEN = Color.parseColor("#8FE3B0")
    private val COLOR_AMBER = Color.parseColor("#F6D55C")

    /**
     * In-target band and target line.
     *
     * The band spans low..high as a single neutral fill rather than three coloured rings. Its own
     * edges are the low and high thresholds, so the boundaries still read without colour competing
     * with the trace, which is the only thing on the bezel that should carry clinical colour.
     */
    private val COLOR_TARGET_BAND = Color.parseColor("#FFFFFF")
    private val COLOR_BOUNDARY_TARGET = Color.parseColor("#FFFFFF")

    /** Enough to read as a lighter ring behind the trace, not enough to compete with it. */
    private const val BAND_ALPHA = 38
    private const val BAND_AMBIENT_ALPHA = 20

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
     * Maps glucose values onto the radial band.
     *
     * The domain is computed per render from the data actually being shown, together with the three
     * boundary values, rather than being a fixed 2.2..13.3 window. Two consequences, both intended:
     * nothing is ever clamped, so a genuine excursion is drawn at its true position instead of being
     * silently flattened against the edge of the band; and a quiet day spent inside a narrow range
     * fills the band rather than rendering as an almost straight line.
     *
     * Including the boundaries in the domain guarantees all three rings stay on screen whatever the
     * data does, which is what keeps the trace readable when the scale moves under it.
     */
    class Scale(dataMin: Double, dataMax: Double) {

        private val lo: Double
        private val hi: Double

        init {
            val mid = (dataMin + dataMax) / 2.0
            val span = maxOf(dataMax - dataMin, MIN_SPAN_MMOL)
            val padded = span * (1.0 + 2 * RANGE_PADDING_FRACTION)
            lo = mid - padded / 2.0
            hi = mid + padded / 2.0
        }

        /** No coerce: the domain is built to contain everything it will be asked to plot. */
        fun radius(v: Double): Float =
            (GRAPH_R_MIN + ((v - lo) / (hi - lo)) * (GRAPH_R_MAX - GRAPH_R_MIN)).toFloat()
    }

    /** Builds the scale for one render: every plotted point plus every boundary line. */
    fun scaleFor(points: List<GlucosePoint>, low: Double, target: Double, high: Double): Scale {
        val values = points.map { it.mmol } + listOf(low, target, high)
        return Scale(values.min(), values.max())
    }

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

        drawBoundaryRings(canvas, cx, cy, scale, vScale, lowThreshold, targetValue, highThreshold, ambient, paints)

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
        val visibleSegments = (segments * revealFraction).toInt().coerceIn(0, segments)
        for (i in 0 until visibleSegments) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val angle0 = -((n - 1 - i).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
            val angle1 = -((n - 1 - (i + 1)).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
            val r0 = vScale.radius(p0.mmol) * scale
            val r1 = vScale.radius(p1.mmol) * scale
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

    /**
     * The in-target region drawn as one translucent band, with a thin line at the target value
     * inside it. Replaces the three saturated boundary rings: those made the bezel read as three
     * competing signals when only the trace's own position actually matters.
     */
    private fun drawBoundaryRings(
        canvas: Canvas, cx: Float, cy: Float, scale: Float, vScale: Scale,
        lowThreshold: Double, targetValue: Double, highThreshold: Double, ambient: Boolean, paints: Paints
    ) {
        val rLow = vScale.radius(lowThreshold)
        val rHigh = vScale.radius(highThreshold)
        val inner = minOf(rLow, rHigh)
        val outer = maxOf(rLow, rHigh)
        val width = outer - inner

        if (width > 0f) {
            // Drawn as a single thick stroked circle at the band's midline, which is cheaper and
            // cleaner-edged than filling an annulus with two paths.
            val bandPaint = paints.band
            bandPaint.style = Paint.Style.STROKE
            bandPaint.isAntiAlias = !ambient
            bandPaint.strokeWidth = width * scale
            bandPaint.color = Color.argb(
                if (ambient) BAND_AMBIENT_ALPHA else BAND_ALPHA,
                Color.red(COLOR_TARGET_BAND), Color.green(COLOR_TARGET_BAND), Color.blue(COLOR_TARGET_BAND)
            )
            canvas.drawCircle(cx, cy, ((inner + outer) / 2f) * scale, bandPaint)
        }

        drawBoundaryRing(canvas, cx, cy, scale, vScale, targetValue, COLOR_BOUNDARY_TARGET, ambient, paints)
    }

    private fun drawBoundaryRing(
        canvas: Canvas, cx: Float, cy: Float, scale: Float, vScale: Scale,
        value: Double, color: Int, ambient: Boolean, paints: Paints
    ) {
        val ringPaint = paints.target
        ringPaint.pathEffect = null
        ringPaint.strokeWidth = (if (ambient) BOUNDARY_STROKE_WIDTH * AMBIENT_STROKE_FACTOR else BOUNDARY_STROKE_WIDTH) * scale
        ringPaint.isAntiAlias = !ambient
        ringPaint.color = Color.argb(
            if (ambient) BOUNDARY_AMBIENT_ALPHA else TARGET_LINE_ALPHA,
            Color.red(color), Color.green(color), Color.blue(color)
        )
        canvas.drawCircle(cx, cy, vScale.radius(value) * scale, ringPaint)
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
        val angle0 = -((n - 1 - segmentIndex).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
        val angle1 = -((n - 1 - (segmentIndex + 1)).toFloat() / (n - 1).toFloat()) * GRAPH_SWEEP
        val angle = angle0 + (angle1 - angle0) * segmentFraction
        val r = vScale.radius(mmol) * scale
        val (x, y) = polarToPoint(cx, cy, angle, r)
        dotPaint.isAntiAlias = !ambient
        dotPaint.color = if (ambient) Color.WHITE else zoneColor(mmol, lowThreshold, highThreshold)
        val dotRadius = (if (ambient) NOW_DOT_RADIUS * AMBIENT_STROKE_FACTOR else NOW_DOT_RADIUS) * scale
        canvas.drawCircle(x, y, dotRadius, dotPaint)
    }
}
