package app.aaps.wear.watchfaces.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

data class GlucosePoint(val timestampMillis: Long, val mmol: Double)

/**
 * Glucose-history line wrapped around the watch bezel, plus zone bands and a target ring, drawn
 * behind the rest of the watch face. Deliberately takes plain data via setters, with no dependency
 * on AAPS's watch-face/event types, so it can be tested and reused standalone. The actual drawing
 * geometry/steps live in [BezelHistoryRenderer], shared with the Watch Face Format complication
 * rendering path so both stay visually identical.
 */
class BezelHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var historyPoints: List<GlucosePoint> = emptyList()
    private var lowThreshold: Double = BezelHistoryRenderer.DEFAULT_LOW
    private var highThreshold: Double = BezelHistoryRenderer.DEFAULT_HIGH
    private var targetValue: Double = BezelHistoryRenderer.DEFAULT_TARGET
    private var ambient: Boolean = false

    // Oscilloscope-style reveal: on wake, the history line draws in once, oldest to newest, over
    // REVEAL_DURATION_MS, mirroring the native Wear OS wake animation convention. 1f = fully drawn
    // (the normal/idle state); setHistory()/setThresholds() etc. never touch this, only
    // playRevealAnimation() does.
    private var revealFraction: Float = 1f
    private var revealAnimator: ValueAnimator? = null

    private val paints = BezelHistoryRenderer.Paints()

    init {
        // Dashed PathEffect strokes are not reliably rendered by the hardware-accelerated canvas
        // path on all Wear OS devices; force a software layer so the target ring always shows.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHistory(points: List<GlucosePoint>) {
        historyPoints = points
        invalidate()
    }

    fun setThresholds(low: Double, high: Double, target: Double) {
        lowThreshold = low
        highThreshold = high
        targetValue = target
        invalidate()
    }

    fun setAmbientMode(isAmbient: Boolean) {
        if (ambient == isAmbient) return
        ambient = isAmbient
        invalidate()
    }

    /**
     * Plays the history line drawing in once, chronologically oldest to newest (like an
     * oscilloscope trace), over [durationMs]. Call on wake (ambient -> interactive), not on every
     * redraw -- this is a one-shot reveal, not a looping/idle animation. Caller is responsible for
     * any battery-level gating; this view just plays whatever it's told to.
     */
    fun playRevealAnimation(durationMs: Long = 1500L) {
        revealAnimator?.cancel()
        revealFraction = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                revealFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        BezelHistoryRenderer.draw(
            canvas = canvas,
            widthPx = width,
            heightPx = height,
            points = historyPoints,
            lowThreshold = lowThreshold,
            highThreshold = highThreshold,
            targetValue = targetValue,
            ambient = ambient,
            revealFraction = revealFraction,
            paints = paints
        )
    }
}
