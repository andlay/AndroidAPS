package app.aaps.wear.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the glucose sparkline shown inside the tile's pill, in the shape of the platform's own heart
 * rate tile: a smooth trace with the minimum and maximum labelled directly on the line rather than on
 * an axis, and a marker at the low point.
 *
 * This exists because protolayout has no line-chart primitive. The trace is rendered to a bitmap here
 * and handed to the tile as an inline image resource, the same approach the watch face's bezel uses
 * for its history ring.
 *
 * The pill itself is drawn into the bitmap rather than being a protolayout Box behind it. That is
 * forced by the legacy tiles ResourceBuilders, whose inline images support only RGB_565 and therefore
 * carry no alpha channel, but it also matches the platform tile more closely: one opaque rounded
 * surface with the trace inside it, not an image floated over a container.
 */
object GlucoseSparkRenderer {

    /** Rendered at roughly 2x the pill's dp size so the trace stays crisp on a high density screen. */
    const val WIDTH_PX = 520
    const val HEIGHT_PX = 210

    private const val LINE_STROKE = 6f
    private const val LABEL_TEXT_SIZE = 24f
    private const val MARKER_STROKE = 2.5f
    private const val MARKER_DOT_RADIUS = 4.5f

    // Vertical breathing room so the min/max labels are never clipped by the bitmap edge.
    private const val PAD_TOP = 54f
    private const val PAD_BOTTOM = 34f
    private const val PAD_X = 34f

    /** Corner radius of the pill. Half the height gives the full capsule the platform tile uses. */
    private const val CORNER_RADIUS = HEIGHT_PX / 2f

    // Matched to the platform heart rate tile: a single bright cyan trace with white labels, rather
    // than colouring the line by glucose zone. The zone colour still carries on the current reading
    // below the chart, so the clinical signal is not lost by making the chart match the system one.
    private val COLOR_PILL = Color.parseColor("#39412F")

    // The trace is coloured by value rather than being one flat accent: green through the in-target
    // band, blending to red below the low threshold and amber above the high one. The blend happens
    // across a soft zone either side of each threshold rather than switching abruptly, so a reading
    // drifting toward a boundary shows it before it crosses.
    private val COLOR_IN_RANGE = Color.parseColor("#7BE3A4")
    private val COLOR_LOW = Color.parseColor("#FF5A4E")
    private val COLOR_HIGH = Color.parseColor("#FFA93A")

    /** Blend zone either side of a threshold, as a fraction of the in-target band's width. */
    private const val BLEND_FRACTION = 0.28
    private val COLOR_LABEL = Color.parseColor("#FFFFFF")
    private val COLOR_MARKER = Color.parseColor("#FFFFFF")

    /**
     * @param values oldest-first glucose readings, in the user's display units.
     * @param format renders a value for the min/max labels, so the caller keeps control of decimals
     *   and units rather than this renderer guessing at mmol/L versus mg/dL.
     */
    fun render(values: List<Double>, low: Double, high: Double, format: (Double) -> String): Bitmap {
        // RGB_565: the tiles inline-image format has no alpha, so the surface is painted here.
        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_PILL
        }
        canvas.drawRoundRect(
            0f, 0f, WIDTH_PX.toFloat(), HEIGHT_PX.toFloat(),
            CORNER_RADIUS, CORNER_RADIUS, pillPaint
        )

        if (values.size < 2) return bitmap

        val lo = values.min()
        val hi = values.max()
        // A flat trace would divide by zero and, worse, render as a line pinned to one edge; centre it.
        val span = if (hi - lo < 1e-6) 1.0 else hi - lo

        val left = PAD_X
        val right = WIDTH_PX - PAD_X
        val top = PAD_TOP
        val bottom = HEIGHT_PX - PAD_BOTTOM

        fun xAt(i: Int) = left + (right - left) * (i.toFloat() / (values.size - 1))
        fun yAt(v: Double) = (bottom - ((v - lo) / span) * (bottom - top)).toFloat()

        // Smooth the trace with quadratic segments through midpoints, which reads much closer to the
        // platform tile than straight point-to-point lines.
        // Drawn as short overlapping segments rather than one path, because a single stroked path
        // can only carry one colour and Android's gradient shaders run along a straight axis, not
        // along a curve. Round caps butt the segments together so it still reads as one trace.
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LINE_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (i in 0 until values.size - 1) {
            val x0 = xAt(i)
            val y0 = yAt(values[i])
            val x1 = xAt(i + 1)
            val y1 = yAt(values[i + 1])
            val seg = Path().apply {
                moveTo(x0, y0)
                quadTo((x0 + x1) / 2f, y0, x1, y1)
            }
            linePaint.color = colorFor((values[i] + values[i + 1]) / 2.0, low, high)
            canvas.drawPath(seg, linePaint)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_SIZE
            color = COLOR_LABEL
            textAlign = Paint.Align.CENTER
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = MARKER_STROKE
            color = COLOR_MARKER
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_LABEL
        }

        val maxIndex = values.indexOf(hi)
        val minIndex = values.indexOf(lo)

        // Max sits above its point on the trace.
        val maxX = clampLabelX(xAt(maxIndex), labelPaint, format(hi))
        canvas.drawText(format(hi), maxX, yAt(hi) - 12f, labelPaint)

        // Min gets the vertical tick and dot the platform tile uses to anchor its low reading.
        val minX = xAt(minIndex)
        canvas.drawLine(minX, yAt(lo), minX, top - 8f, markerPaint)
        canvas.drawCircle(minX, yAt(lo), MARKER_DOT_RADIUS, dotPaint)
        canvas.drawText(format(lo), clampLabelX(minX, labelPaint, format(lo)), top - 14f, labelPaint)

        return bitmap
    }

    /**
     * Green through the in-target band, blending to red below [low] and amber above [high]. The
     * blend is centred on each threshold so the crossover reads as a transition rather than a step.
     */
    private fun colorFor(v: Double, low: Double, high: Double): Int {
        val band = (high - low).coerceAtLeast(0.1)
        val soft = band * BLEND_FRACTION
        return when {
            v <= low - soft  -> COLOR_LOW
            v < low + soft   -> lerp(COLOR_LOW, COLOR_IN_RANGE, ((v - (low - soft)) / (2 * soft)).toFloat())
            v <= high - soft -> COLOR_IN_RANGE
            v < high + soft  -> lerp(COLOR_IN_RANGE, COLOR_HIGH, ((v - (high - soft)) / (2 * soft)).toFloat())
            else             -> COLOR_HIGH
        }
    }

    private fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.rgb(
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to))
        )
    }

    /** Keeps a label fully inside the bitmap when its point sits near either end of the trace. */
    private fun clampLabelX(x: Float, paint: Paint, text: String): Float {
        val half = paint.measureText(text) / 2f
        return min(max(x, half + 2f), WIDTH_PX - half - 2f)
    }
}
