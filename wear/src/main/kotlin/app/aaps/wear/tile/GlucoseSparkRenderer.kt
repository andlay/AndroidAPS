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
    const val WIDTH_PX = 420
    const val HEIGHT_PX = 130

    private const val LINE_STROKE = 5f
    private const val LABEL_TEXT_SIZE = 20f
    private const val MARKER_STROKE = 2.5f
    private const val MARKER_DOT_RADIUS = 4.5f

    // Vertical breathing room so the min/max labels are never clipped by the bitmap edge.
    private const val PAD_TOP = 40f
    private const val PAD_BOTTOM = 26f
    private const val PAD_X = 26f

    /** Corner radius of the pill. Half the height gives the full capsule the platform tile uses. */
    private const val CORNER_RADIUS = HEIGHT_PX / 2f

    // Matched to the platform heart rate tile: a single bright cyan trace with white labels, rather
    // than colouring the line by glucose zone. The zone colour still carries on the current reading
    // below the chart, so the clinical signal is not lost by making the chart match the system one.
    private val COLOR_PILL = Color.parseColor("#39412F")
    private val COLOR_LINE = Color.parseColor("#4FC8F5")
    private val COLOR_LABEL = Color.parseColor("#FFFFFF")
    private val COLOR_MARKER = Color.parseColor("#FFFFFF")

    /**
     * @param values oldest-first glucose readings, in the user's display units.
     * @param format renders a value for the min/max labels, so the caller keeps control of decimals
     *   and units rather than this renderer guessing at mmol/L versus mg/dL.
     */
    fun render(values: List<Double>, format: (Double) -> String): Bitmap {
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
        val path = Path()
        path.moveTo(xAt(0), yAt(values[0]))
        for (i in 0 until values.size - 1) {
            val x0 = xAt(i)
            val y0 = yAt(values[i])
            val x1 = xAt(i + 1)
            val y1 = yAt(values[i + 1])
            path.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f)
        }
        path.lineTo(xAt(values.size - 1), yAt(values[values.size - 1]))

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LINE_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = COLOR_LINE
        }
        canvas.drawPath(path, linePaint)

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

    /** Keeps a label fully inside the bitmap when its point sits near either end of the trace. */
    private fun clampLabelX(x: Float, paint: Paint, text: String): Float {
        val half = paint.measureText(text) / 2f
        return min(max(x, half + 2f), WIDTH_PX - half - 2f)
    }
}
