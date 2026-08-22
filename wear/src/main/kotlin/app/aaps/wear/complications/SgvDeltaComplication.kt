package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection

/**
 * Current glucose with its delta and trend arrow as one pre-formatted string, e.g. `5.5 (-0.5 ↓)`.
 *
 * Exists because the watch face renders this as a single curved [TextCircular] element, which binds
 * to exactly one complication slot and can only lay out one string. Combining
 * [SgvComplication]'s text with [DeltaComplication]'s would need two slots along the same arc, and
 * they could not be kept from overlapping as the numbers change width.
 *
 * The arrow is normalised to the five cardinal trend directions, so it always reads as flat, rising
 * or falling at 45 or 90 degrees rather than as one of the doubled glyphs some sources emit.
 */
class SgvDeltaComplication : ModernBaseComplicationProviderService() {

    // Not derived from DaggerService, do injection here (same pattern as the others in this package).
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            aapsLogger.warn(LTag.WEAR, "SgvDeltaComplication unexpected type: $type")
            return null
        }

        val bg = data.bgData
        val value = bg.sgvString.takeIf { it.isNotBlank() } ?: NO_DATA
        val delta = bg.delta.takeIf { it.isNotBlank() && it != "--" }
        val arrow = normaliseArrow(bg.slopeArrow)

        // "5.5 (-0.5 ↓)", dropping the bracket entirely when there is nothing to put in it rather
        // than showing an empty pair.
        val detail = listOfNotNull(delta, arrow.takeIf { it.isNotEmpty() }).joinToString(" ")
        val text = if (detail.isEmpty()) value else "$value ($detail)"

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = text).build(),
            contentDescription = PlainComplicationText.Builder(text = "Glucose $text").build()
        )
            .setTapAction(complicationPendingIntent)
            .build()
    }

    /** Collapses the various trend glyphs onto flat, 45 degrees, or 90 degrees, up or down. */
    private fun normaliseArrow(raw: String): String = when (raw.trim()) {
        "↑↑", "⇈", "↑" -> "↑"   // rising fast / rising -> up
        "↗"                          -> "↗"   // 45 up
        "→"                          -> "→"   // flat
        "↘"                          -> "↘"   // 45 down
        "↓↓", "⇊", "↓" -> "↓"  // falling fast / falling -> down
        else                              -> ""         // "??" and anything unrecognised: say nothing
    }

    override fun getProviderCanonicalName(): String = SgvDeltaComplication::class.java.canonicalName!!

    companion object {

        private const val NO_DATA = "---"
    }
}
