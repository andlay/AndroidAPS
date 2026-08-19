package app.aaps.wear.watchfaces.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * One dashed gauge ring + progress arc + tip dot, matching the Pixel Watch mockup's quadrant
 * complications (BG hero, delta, IOB, COB).
 *
 * Geometry (angles, radius, stroke width) is expressed in the same 200x200 logical-unit space as
 * the mockup (center at 100,100; 0deg = 12 o'clock, clockwise-positive) and scaled at draw time to
 * this view's actual measured size, so instances can be full-bleed (match_parent) and just overlap
 * on the shared face center. Multiple instances are meant to be stacked in the same position, each
 * drawing only its own arc.
 */
class RingComplicationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var startAngle: Float = 0f
        set(value) { field = value; invalidate() }
    var endAngle: Float = 72f
        set(value) { field = value; invalidate() }
    var ringRadius: Float = 46f
        set(value) { field = value; invalidate() }
    var ringStrokeWidth: Float = 3.2f
        set(value) { field = value; invalidate() }
    var trackColor: Int = Color.parseColor("#302C3A")
        set(value) { field = value; invalidate() }
    var hero: Boolean = false
        set(value) { field = value; invalidate() }

    private var valueFraction: Float = 0f
    private var valueColor: Int = Color.WHITE
    private var ambient: Boolean = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val oval = RectF()
    private val trackPath = Path()
    private val progressPath = Path()

    init {
        // Dashed PathEffect strokes are not reliably rendered by the hardware-accelerated canvas
        // path on all Wear OS devices; force a software layer so the dashed track always shows.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Sets the gauge's filled fraction (0f-1f of startAngle..endAngle) and its progress color. */
    fun setValue(fraction: Float, color: Int) {
        valueFraction = fraction.coerceIn(0f, 1f)
        valueColor = color
        invalidate()
    }

    fun setAmbientMode(isAmbient: Boolean) {
        if (ambient == isAmbient) return
        ambient = isAmbient
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // The mockup's 200x200 logical viewBox maps 1:1 onto this view's full measured extent.
        val scale = minOf(width, height) / 200f
        if (scale <= 0f) return

        val radiusPx = ringRadius * scale
        val strokePx = (if (ambient) ringStrokeWidth * 0.6f else ringStrokeWidth) * scale
        oval.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)

        // Canvas.drawArc/addArc measure startAngle from 3 o'clock (positive x-axis); the mockup's
        // (and this view's) angle convention measures from 12 o'clock, so subtract 90 to convert.
        val canvasStart = startAngle - 90f
        val sweep = endAngle - startAngle

        trackPaint.strokeWidth = strokePx
        trackPaint.isAntiAlias = !ambient
        if (ambient) {
            trackPaint.pathEffect = null
            trackPaint.color = Color.argb(60, 255, 255, 255)
        } else {
            trackPaint.pathEffect = DashPathEffect(floatArrayOf(1f * scale, 6.4f * scale), 0f)
            trackPaint.color = trackColor
        }
        trackPath.reset()
        trackPath.addArc(oval, canvasStart, sweep)
        canvas.drawPath(trackPath, trackPaint)

        if (valueFraction <= 0f) return

        val progressSweep = sweep * valueFraction
        progressPaint.strokeWidth = strokePx
        progressPaint.isAntiAlias = !ambient
        progressPaint.color = if (ambient) Color.WHITE else valueColor
        progressPath.reset()
        progressPath.addArc(oval, canvasStart, progressSweep)
        canvas.drawPath(progressPath, progressPaint)

        val tipAngleRad = Math.toRadians((canvasStart + progressSweep).toDouble())
        val dotX = cx + radiusPx * cos(tipAngleRad).toFloat()
        val dotY = cy + radiusPx * sin(tipAngleRad).toFloat()
        val dotRadiusLogical = if (hero) 4.2f else 3.4f
        dotPaint.color = if (ambient) Color.WHITE else valueColor
        canvas.drawCircle(dotX, dotY, dotRadiusLogical * scale, dotPaint)
    }
}
