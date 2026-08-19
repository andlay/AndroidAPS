package app.aaps.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_MEDIUM
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Semantics
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.interaction.menus.MainMenuActivity
import com.google.common.util.concurrent.ListenableFuture
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import javax.inject.Inject

/**
 * Glanceable glucose tile, styled after the platform's own heart-rate tile: one large value, a unit
 * caption, and a small supporting row, on a flat dark surface.
 *
 * This is deliberately NOT built on [TileBase]. Every other AAPS tile is an *action* tile whose job is
 * to lay out a grid of tappable buttons, and [TileBase] is structured entirely around that (it takes a
 * `TileSource` supplying a list of `Action`s and arranges 1-4 circular buttons). A glanceable readout
 * has no actions to arrange and needs a completely different layout, so reusing that base class would
 * have meant bending it out of shape for both use cases.
 *
 * Data comes from the same [ComplicationDataRepository]-backed DataStore the complications read, so
 * the tile can never disagree with the watch face about the current reading.
 */
class GlucoseTileService : TileService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> =
        // NOTE: anything thrown in here surfaces as a silently blank tile with no logcat entry of its
        // own, so the body is wrapped and logged explicitly. Debugging a blank tile without this is
        // guesswork.
        serviceScope.future {
            aapsLogger.debug(LTag.WEAR, "GlucoseTileService.onTileRequest entered")
            val data = try {
                complicationDataRepository.complicationData.first()
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "GlucoseTileService: DataStore read failed", e)
                null
            }

            val layout = layout(data, requestParams.deviceConfiguration)
            aapsLogger.debug(
                LTag.WEAR,
                "GlucoseTileService built layout: data=${data != null} sgv=${data?.bgData?.sgvString} " +
                    "screen=${requestParams.deviceConfiguration.screenWidthDp}x${requestParams.deviceConfiguration.screenHeightDp}dp"
            )

            Tile.Builder()
                .setResourcesVersion(RESOURCE_VERSION)
                .setTileTimeline(Timeline.fromLayoutElement(layout))
                // Matches the 5 minute CGM cadence; there is no point refreshing faster than data
                // can possibly arrive.
                .setFreshnessIntervalMillis(REFRESH_MS)
                .build()
        }

    @Deprecated("Deprecated in TileService but still required for now")
    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        serviceScope.future {
            // Text-only tile: no image resources to map.
            ResourceBuilders.Resources.Builder().setVersion(RESOURCE_VERSION).build()
        }

    private fun layout(data: app.aaps.wear.data.ComplicationData?, device: DeviceParameters): LayoutElement {
        val bg = data?.bgData
        val status = data?.statusData

        val hasReading = bg != null && bg.sgv > 0.0
        // "---" rather than a fabricated number: an unavailable reading must never be presentable as
        // a real one on a device used for insulin dosing.
        val valueText = if (hasReading) bg.sgvString else NO_DATA_TEXT
        val arrowText = if (hasReading) bg.slopeArrow + VARIATION_SELECTOR_TEXT else ""
        val unitText = if (hasReading && bg.glucoseUnits.isNotBlank() && bg.glucoseUnits != "-") bg.glucoseUnits else ""

        val accent = when {
            !hasReading                          -> COLOR_NO_DATA
            bg.low > 0.0 && bg.sgv < bg.low      -> COLOR_LOW
            bg.high > 0.0 && bg.sgv > bg.high    -> COLOR_HIGH
            else                                 -> COLOR_IN_RANGE
        }

        // These arrive pre-formatted for display and already carry their own label or unit (observed
        // on-device: iobSum == "IOB", cob == "--g"), so adding our own prefix produced "IOB IOB".
        // Show them as-is and drop the bare placeholders rather than presenting them as readings.
        val supporting = listOf(bg?.delta, status?.iobSum, status?.cob)
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in PLACEHOLDER_STRINGS }
            .joinToString("   ")

        val valueSize = if (isLargeScreen(device)) VALUE_SP_LARGE else VALUE_SP

        val column = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setWidth(expand())
            .addContent(
                Text.Builder()
                    .setText(TITLE_TEXT)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(LABEL_SP))
                            .setWeight(FONT_WEIGHT_MEDIUM)
                            .setColor(argb(COLOR_LABEL))
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(GAP_TITLE)).build())
            .addContent(
                // Value and arrow on one row so the arrow hangs off the number like the HR tile's
                // bpm caption does, rather than being centred underneath it.
                Row.Builder()
                    .addContent(
                        Text.Builder()
                            .setText(valueText)
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(valueSize))
                                    .setWeight(FONT_WEIGHT_BOLD)
                                    .setColor(argb(accent))
                                    .build()
                            )
                            .build()
                    )
                    .apply {
                        if (arrowText.isNotEmpty()) {
                            addContent(Spacer.Builder().setWidth(dp(GAP_ARROW)).build())
                            addContent(
                                Text.Builder()
                                    .setText(arrowText)
                                    .setFontStyle(
                                        FontStyle.Builder()
                                            .setSize(sp(ARROW_SP))
                                            .setWeight(FONT_WEIGHT_MEDIUM)
                                            .setColor(argb(accent))
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    .build()
            )

        if (unitText.isNotEmpty()) {
            column.addContent(
                Text.Builder()
                    .setText(unitText)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(UNIT_SP))
                            .setWeight(FONT_WEIGHT_MEDIUM)
                            .setColor(argb(COLOR_LABEL))
                            .build()
                    )
                    .build()
            )
        }

        if (supporting.isNotEmpty()) {
            column.addContent(Spacer.Builder().setHeight(dp(GAP_SUPPORTING)).build())
            column.addContent(
                Text.Builder()
                    .setText(supporting)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(SUPPORTING_SP))
                            .setWeight(FONT_WEIGHT_MEDIUM)
                            .setColor(argb(COLOR_SUPPORTING))
                            .build()
                    )
                    .build()
            )
        }

        // The root is the Column itself, deliberately. An earlier version wrapped this in a
        // Box(expand, expand) carrying Background/Corner/Padding/Semantics/Clickable, and that
        // rendered a completely blank tile with no error anywhere: onTileRequest ran, the layout was
        // built, the renderer inflated nothing. Bisecting proved the content tree here is fine and the
        // wrapper was at fault. The background it provided was 0xFF11121A against a black tile
        // surface, i.e. invisible in practice, so nothing of value is lost by dropping it.
        return column
            .setModifiers(
                Modifiers.Builder()
                    .setSemantics(
                        Semantics.Builder()
                            .setContentDescription(
                                if (hasReading) "Glucose $valueText $unitText $supporting" else "Glucose unavailable"
                            )
                            .build()
                    )
                    // Tapping opens the AAPS menu, consistent with the complications' tap action.
                    .setClickable(
                        Clickable.Builder()
                            .setId(CLICK_ID)
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainMenuActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun isLargeScreen(device: DeviceParameters): Boolean = device.screenWidthDp >= LARGE_SCREEN_DP

    companion object {

        private const val RESOURCE_VERSION = "GlucoseTileService"
        private const val REFRESH_MS = 5 * 60 * 1000L
        private const val CLICK_ID = "aaps_glucose_tile"

        // Values the wear Status/SingleBg strings use to mean "nothing to show". Rendering these
        // verbatim would dress up absent data as a reading.
        private val PLACEHOLDER_STRINGS = setOf("--", "---", "-", "IOB", "COB", "?", "??")

        private const val TITLE_TEXT = "Glucose"
        private const val NO_DATA_TEXT = "---"

        // Keeps the trend arrow rendering as text rather than being substituted with a colour emoji,
        // same trick SgvComplication uses.
        private const val VARIATION_SELECTOR_TEXT = "︎"

        private const val LARGE_SCREEN_DP = 210

        private const val VALUE_SP = 44f
        private const val VALUE_SP_LARGE = 54f
        private const val ARROW_SP = 26f
        private const val UNIT_SP = 14f
        private const val LABEL_SP = 14f
        private const val SUPPORTING_SP = 13f

        private const val GAP_TITLE = 4f
        private const val GAP_ARROW = 4f
        private const val GAP_SUPPORTING = 6f

        private const val COLOR_LABEL = 0xFF9AA0AE.toInt()
        private const val COLOR_SUPPORTING = 0xFFC9CEDA.toInt()
        private const val COLOR_IN_RANGE = 0xFF8FE3B0.toInt()
        private const val COLOR_HIGH = 0xFFF0C674.toInt()
        private const val COLOR_LOW = 0xFFF08A7F.toInt()
        private const val COLOR_NO_DATA = 0xFF6E6678.toInt()
    }
}
