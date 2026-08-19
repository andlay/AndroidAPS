package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.interaction.utils.SmallestDoubleString
import dagger.android.AndroidInjection
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.wear.watchface.complications.data.RangedValueComplicationData

/**
 * SGV (Sensor Glucose Value) Complication
 *
 * Shows current blood glucose with arrow, auto-updating time, and delta
 * Display format: "6.8↘" with "5m Δ-3" above
 * - Time auto-updates every minute (battery efficient)
 * - Delta is static until new BG reading
 */
class SgvComplication : ModernBaseComplicationProviderService() {

    @Inject lateinit var sp: SP

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
        // Use dataset 0 (primary)
        val bgData = data.bgData
        aapsLogger.debug(LTag.WEAR, "SgvComplication building: dataset=0 sgv=${bgData.sgvString} arrow=${bgData.slopeArrow}")

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                buildShortTextComplication(bgData, complicationPendingIntent)
            }

            // Drives the BG gauge ring on the Watch Face Format face. The range is derived from the
            // profile's own low/high thresholds (padded either side) rather than a fixed 40-400
            // span, so the in-range band sits around the middle of the ring's sweep and small
            // excursions are actually visible instead of being lost in a huge scale.
            ComplicationType.RANGED_VALUE -> {
                buildRangedValueComplication(bgData, complicationPendingIntent)
            }

            else -> {
                aapsLogger.warn(LTag.WEAR, "SgvComplication unexpected type: $type")
                null
            }
        }
    }

    private fun buildRangedValueComplication(
        bgData: app.aaps.core.interfaces.rx.weardata.EventData.SingleBg,
        pendingIntent: PendingIntent
    ): ComplicationData? {
        // sgv == 0.0 is the "no reading yet" default from ComplicationStore, not a real glucose of
        // zero. Returning null hides the ring rather than pinning it at empty, which would otherwise
        // look exactly like a genuine dangerous low.
        if (bgData.sgv <= 0.0) {
            aapsLogger.debug(LTag.WEAR, "SgvComplication RANGED_VALUE skipped: no reading")
            return null
        }

        val low = if (bgData.low > 0.0) bgData.low else GaugeRanges.BG_FALLBACK_LOW_MGDL
        val high = if (bgData.high > low) bgData.high else GaugeRanges.BG_FALLBACK_HIGH_MGDL
        val (value, min, max) = GaugeRanges.ranged(
            value = bgData.sgv,
            min = low - GaugeRanges.BG_RANGE_PADDING_MGDL,
            max = high + GaugeRanges.BG_RANGE_PADDING_MGDL
        )

        return RangedValueComplicationData.Builder(
            value = value,
            min = min,
            max = max,
            contentDescription = PlainComplicationText.Builder(text = "Glucose ${bgData.sgvString}").build()
        )
            .setText(PlainComplicationText.Builder(text = bgData.sgvString).build())
            .setTapAction(pendingIntent)
            .build()
    }

    private fun buildShortTextComplication(
        bgData: app.aaps.core.interfaces.rx.weardata.EventData.SingleBg,
        pendingIntent: PendingIntent
    ): ShortTextComplicationData {
        // Main text: BG value + arrow (with variation selector to prevent emoji)
        val mainText = bgData.sgvString + bgData.slopeArrow + "\uFE0E"

        // Title: auto-updating time + delta (e.g., "5m Δ-3")
        val titleText = buildDeltaAndTimeTitle(bgData)

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = mainText).build(),
            contentDescription = PlainComplicationText.Builder(text = "Glucose $mainText").build()
        )
            .setTitle(titleText)
            .setTapAction(pendingIntent)
            .build()
    }

    /**
     * Build combined delta and time title (e.g., "5m Δ-3")
     * Time auto-updates, delta is static
     * Uses ^1 placeholder which is replaced with the time difference
     */
    private fun buildDeltaAndTimeTitle(bgData: app.aaps.core.interfaces.rx.weardata.EventData.SingleBg): TimeDifferenceComplicationText {
        // Use detailed delta if preference is enabled, otherwise use simple delta
        val rawDelta = if (sp.getBoolean(app.aaps.wear.R.string.key_show_detailed_delta, false)) {
            bgData.deltaDetailed
        } else {
            bgData.delta
        }

        // Add delta symbol if Unicode complications are enabled
        val useUnicode = sp.getBoolean("complication_unicode", true)
        val deltaSymbol = if (useUnicode) "\u0394" else ""

        // Minimize delta to leave room for time (max 7 chars total for SHORT_TEXT title)
        val deltaText = deltaSymbol + SmallestDoubleString(rawDelta).minimise(4)

        // ^1 is replaced with auto-updating time (e.g., "5m")
        // Format: "5m Δ-3" where time auto-updates every minute
        return TimeDifferenceComplicationText.Builder(
            style = TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            countUpTimeReference = CountUpTimeReference(Instant.ofEpochMilli(bgData.timeStamp))
        )
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .setText("^1 $deltaText")
            .build()
    }

    override fun getProviderCanonicalName(): String = SgvComplication::class.java.canonicalName!!
}