package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection
import androidx.wear.watchface.complications.data.RangedValueComplicationData

/**
 * COB Detailed Complication
 *
 * Shows detailed carbs on board (COB) information
 * Displays both total COB and additional detail if space permits
 * Tap action opens carb/wizard dialog
 *
 */
class CobDetailedComplication : ModernBaseComplicationProviderService() {

    // Not derived from DaggerService, do injection here
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT      -> {
                // Pass EventData arrays directly to DisplayFormat
                val status = arrayOf(data.statusData, data.statusData1, data.statusData2)

                val cob = displayFormat.detailedCob(status, 0)
                val builder = ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = cob.first).build(),
                    contentDescription = PlainComplicationText.Builder(text = "COB ${cob.first}").build()
                )
                    .setTapAction(complicationPendingIntent)
                if (cob.second.isNotEmpty()) {
                    builder.setTitle(PlainComplicationText.Builder(text = cob.second).build())
                }
                builder.build()
            }


            // Drives the COB gauge ring on the Watch Face Format face.
            ComplicationType.RANGED_VALUE    -> {
                // The wear Status strings are display-formatted (e.g. "1.25U", "12g"), so pull the
                // leading number out. A null result means "no data", which hides the ring instead of
                // rendering an empty gauge that would read as a genuine zero.
                val raw = GaugeRanges.leadingNumber(data.statusData.cob)
                if (raw == null) {
                    aapsLogger.debug(LTag.WEAR, "COB RANGED_VALUE skipped: unparseable '${data.statusData.cob}'")
                    null
                } else {
                    val (value, min, max) = GaugeRanges.ranged(raw, 0.0, GaugeRanges.COB_MAX_GRAMS)
                    RangedValueComplicationData.Builder(
                        value = value,
                        min = min,
                        max = max,
                        contentDescription = PlainComplicationText.Builder(text = "Carbs on board").build()
                    )
                        .setText(PlainComplicationText.Builder(text = data.statusData.cob).build())
                        .setTapAction(complicationPendingIntent)
                        .build()
                }
            }
            else                             -> {
                aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = CobDetailedComplication::class.java.canonicalName!!
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.WIZARD
}