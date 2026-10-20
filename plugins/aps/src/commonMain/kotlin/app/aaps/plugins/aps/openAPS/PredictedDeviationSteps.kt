package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.PredictedDeviations
import app.aaps.core.interfaces.aps.Predictions
import kotlin.math.roundToLong

/**
 * Collects the non-insulin part of each prediction step while determine-basal builds its curves, so
 * the graph can show the deviations the loop expects. See [PredictedDeviations].
 */
internal class PredictedDeviationSteps {

    private val iob = mutableListOf<Double>()
    private val cob = mutableListOf<Double>()
    private val uam = mutableListOf<Double>()

    /** One 5 minute step, in mg/dL per 5 minutes. Steps after the 4 hours of the curves are dropped. */
    fun add(iobDeviation: Double, cobDeviation: Double, uamDeviation: Double) {
        if (iob.size >= MAX_STEPS) return
        iob.add(iobDeviation)
        cob.add(cobDeviation)
        uam.add(uamDeviation)
    }

    /**
     * Only the curves that are in [predictions], each cut to the length of its curve there. A value
     * that is not a number becomes 0, so it can not break the JSON of the loop result.
     */
    fun build(predictions: Predictions?): PredictedDeviations =
        PredictedDeviations(
            IOB = predictions?.IOB?.let { cut(iob, it.size) },
            COB = predictions?.COB?.let { cut(cob, it.size) },
            UAM = predictions?.UAM?.let { cut(uam, it.size) }
        )

    private fun cut(steps: List<Double>, curveSize: Int): List<Double> =
        steps.take((curveSize - 1).coerceAtLeast(0)).map { if (it.isFinite()) (it * 100).roundToLong() / 100.0 else 0.0 }

    companion object {

        /** The curves stop at 48 points, the first of which is the run itself. */
        const val MAX_STEPS = 47
    }
}
