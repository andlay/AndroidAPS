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
 * IOB Detailed Complication
 *
 * Shows detailed insulin on board (IOB) information
 * Displays both total IOB and additional detail if space permits
 * Tap action opens bolus wizard
 *
 */
class IobDetailedComplication : ModernBaseComplicationProviderService() {

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

                val iob = displayFormat.detailedIob(status, 0)
                val builder = ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = iob.first).build(),
                    contentDescription = PlainComplicationText.Builder(text = "IOB ${iob.first}").build()
                )
                    .setTapAction(complicationPendingIntent)
                if (iob.second.isNotEmpty()) {
                    builder.setTitle(PlainComplicationText.Builder(text = iob.second).build())
                }
                builder.build()
            }


            // Drives the IOB gauge ring on the Watch Face Format face.
            ComplicationType.RANGED_VALUE    -> {
                // The wear Status strings are display-formatted (e.g. "1.25U", "12g"), so pull the
                // leading number out. A null result means "no data", which hides the ring instead of
                // rendering an empty gauge that would read as a genuine zero.
                val raw = GaugeRanges.leadingNumber(data.statusData.iobSum)
                if (raw == null) {
                    aapsLogger.debug(LTag.WEAR, "IOB RANGED_VALUE skipped: unparseable '${data.statusData.iobSum}'")
                    null
                } else {
                    val (value, min, max) = GaugeRanges.ranged(raw, 0.0, GaugeRanges.IOB_MAX_UNITS)
                    RangedValueComplicationData.Builder(
                        value = value,
                        min = min,
                        max = max,
                        contentDescription = PlainComplicationText.Builder(text = "Insulin on board").build()
                    )
                        .setText(PlainComplicationText.Builder(text = data.statusData.iobSum).build())
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

    override fun getProviderCanonicalName(): String = IobDetailedComplication::class.java.canonicalName!!
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.BOLUS
}