package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection

/**
 * Glucose delta complication.
 *
 * Exists because there was no AAPS complication dedicated to glucose delta alone: the existing ones
 * are either combined (BrCobIobComplication) or full-value (SgvComplication, which carries delta only
 * as a subtitle string). The Watch Face Format face's delta gauge ring needs a provider that reports
 * delta as a real RANGED_VALUE, so the ring can be driven by the numeric value rather than by parsing
 * a formatted string on the face side (which WFF cannot do anyway).
 *
 * The gauge is symmetric about zero: [GaugeRanges.DELTA_HALF_RANGE_MGDL] either side, so a flat trend
 * rests at the middle of the quadrant, a rise sweeps forward and a fall sweeps back. That is why this
 * cannot reuse the 0..max shape the IOB/COB rings use.
 */
class DeltaComplication : ModernBaseComplicationProviderService() {

    // Not derived from DaggerService, do injection here (mirrors SgvComplication and the other
    // ModernBaseComplicationProviderService subclasses in this package).
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val bgData = data.bgData

        // deltaMgdl is nullable in EventData.SingleBg and is genuinely absent until two readings have
        // arrived. Treating that as 0.0 would render a flat trend, which is a real and misleading
        // clinical claim, so report no data instead and let the ring stay hidden.
        val deltaMgdl = bgData.deltaMgdl
        if (deltaMgdl == null) {
            aapsLogger.debug(LTag.WEAR, "DeltaComplication skipped: no deltaMgdl yet")
            return null
        }

        val displayText = bgData.delta

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                val (value, min, max) = GaugeRanges.ranged(
                    value = deltaMgdl,
                    min = -GaugeRanges.DELTA_HALF_RANGE_MGDL,
                    max = GaugeRanges.DELTA_HALF_RANGE_MGDL
                )
                RangedValueComplicationData.Builder(
                    value = value,
                    min = min,
                    max = max,
                    contentDescription = PlainComplicationText.Builder(text = "Glucose delta $displayText").build()
                )
                    .setText(PlainComplicationText.Builder(text = displayText).build())
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            ComplicationType.SHORT_TEXT   -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = displayText).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose delta $displayText").build()
                )
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            else                          -> {
                aapsLogger.warn(LTag.WEAR, "DeltaComplication unexpected type: $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = DeltaComplication::class.java.canonicalName!!
}
