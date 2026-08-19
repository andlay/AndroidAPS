package app.aaps.wear.tile

import android.graphics.Bitmap
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_MEDIUM
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Semantics
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.interaction.menus.MainMenuActivity
import com.google.common.util.concurrent.ListenableFuture
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Glanceable glucose tile, laid out like the platform's own heart rate tile: icon, title, a rounded
 * pill holding a smooth time series with its min and max labelled on the trace, the period covered,
 * then the current reading.
 *
 * Deliberately not built on [TileBase]: that class exists to arrange 1-4 circular action buttons from
 * a `TileSource`, which a glanceable readout has no use for.
 *
 * Data comes from the same [ComplicationDataRepository] DataStore the complications read, so the tile
 * and the watch face can never disagree about the current reading.
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
        serviceScope.future {
            val data = readData()
            val entries = data?.graphData?.entries.orEmpty().sortedBy { it.timeStamp }

            Tile.Builder()
                // The sparkline is a resource, and resources are cached per version string. A fixed
                // version would pin the chart to whatever data happened to be present on first render,
                // so the version tracks the newest reading.
                .setResourcesVersion(resourceVersion(entries.lastOrNull()?.timeStamp ?: 0L))
                .setTileTimeline(Timeline.fromLayoutElement(layout(data, requestParams.deviceConfiguration)))
                .setFreshnessIntervalMillis(REFRESH_MS)
                .build()
        }

    @Deprecated("Deprecated in TileService but still required for now")
    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        serviceScope.future {
            val data = readData()
            val entries = data?.graphData?.entries.orEmpty().sortedBy { it.timeStamp }
            val builder = ResourceBuilders.Resources.Builder()
                .setVersion(resourceVersion(entries.lastOrNull()?.timeStamp ?: 0L))
                .addIdToImageMapping(
                    ID_ICON,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_sgv)
                                .build()
                        )
                        .build()
                )

            if (entries.size >= 2) {
                val values = entries.map { it.sgv * MGDL_TO_MMOL }
                val bg = data?.bgData
                val low = if (bg != null && bg.low > 0.0) bg.low * MGDL_TO_MMOL else FALLBACK_LOW
                val high = if (bg != null && bg.high > 0.0) bg.high * MGDL_TO_MMOL else FALLBACK_HIGH
                val bitmap = GlucoseSparkRenderer.render(values, low, high) { formatValue(it) }
                builder.addIdToImageMapping(ID_SPARK, inlineImage(bitmap))
            }
            builder.build()
        }

    private suspend fun readData(): app.aaps.wear.data.ComplicationData? = try {
        complicationDataRepository.complicationData.first()
    } catch (e: Exception) {
        aapsLogger.error(LTag.WEAR, "GlucoseTileService: DataStore read failed", e)
        null
    }

    /** protolayout wants raw ARGB_8888 bytes rather than an encoded image. */
    private fun inlineImage(bitmap: Bitmap): ResourceBuilders.ImageResource {
        val buffer = ByteArrayOutputStream(bitmap.byteCount)
        val pixels = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(pixels)
        buffer.write(pixels.array())
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(buffer.toByteArray())
                    .setWidthPx(bitmap.width)
                    .setHeightPx(bitmap.height)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                    .build()
            )
            .build()
    }

    private fun layout(data: app.aaps.wear.data.ComplicationData?, device: DeviceParameters): LayoutElement {
        val bg = data?.bgData
        val entries = data?.graphData?.entries.orEmpty().sortedBy { it.timeStamp }
        val hasReading = bg != null && bg.sgv > 0.0
        val hasSeries = entries.size >= 2

        // "---" rather than a fabricated number: an unavailable reading must never be presentable as
        // a real one on a device used for insulin dosing.
        val valueText = if (hasReading) bg.sgvString else NO_DATA_TEXT
        val arrowText = if (hasReading && bg.slopeArrow.isNotBlank() && bg.slopeArrow != "--") bg.slopeArrow + VARIATION_SELECTOR else ""
        val accent = when {
            !hasReading                       -> COLOR_NO_DATA
            bg.low > 0.0 && bg.sgv < bg.low   -> COLOR_LOW
            bg.high > 0.0 && bg.sgv > bg.high -> COLOR_HIGH
            else                              -> COLOR_IN_RANGE
        }
        val period = if (hasSeries) {
            "${clock(entries.first().timeStamp)}–${clock(entries.last().timeStamp)}"
        } else ""

        val column = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setWidth(expand())

        // Icon in a soft circle, matching the platform tile's header treatment.
        column.addContent(
            Box.Builder()
                .setWidth(dp(ICON_CIRCLE_DP))
                .setHeight(dp(ICON_CIRCLE_DP))
                .setModifiers(
                    Modifiers.Builder()
                        .setBackground(
                            Background.Builder()
                                .setColor(argb(COLOR_ICON_BG))
                                .setCorner(Corner.Builder().setRadius(dp(ICON_CIRCLE_DP / 2f)).build())
                                .build()
                        )
                        .build()
                )
                .addContent(
                    Image.Builder()
                        .setResourceId(ID_ICON)
                        .setWidth(dp(ICON_DP))
                        .setHeight(dp(ICON_DP))
                        .build()
                )
                .build()
        )
        column.addContent(Spacer.Builder().setHeight(dp(GAP_S)).build())
        column.addContent(text(TITLE_TEXT, TITLE_SP, FONT_WEIGHT_MEDIUM, COLOR_TITLE))

        if (hasSeries) {
            column.addContent(Spacer.Builder().setHeight(dp(GAP_M)).build())
            column.addContent(
                // The pill surface is painted into the bitmap (see GlucoseSparkRenderer), so this is
                // just the image at its final size.
                Image.Builder()
                    .setResourceId(ID_SPARK)
                    .setWidth(dp(PILL_W))
                    .setHeight(dp(PILL_H))
                    .build()
            )
            if (period.isNotEmpty()) {
                column.addContent(Spacer.Builder().setHeight(dp(GAP_S)).build())
                column.addContent(text(period, CAPTION_SP, FONT_WEIGHT_MEDIUM, COLOR_CAPTION))
            }
        }

        column.addContent(Spacer.Builder().setHeight(dp(GAP_M)).build())
        column.addContent(text(CURRENT_LABEL, CAPTION_SP, FONT_WEIGHT_MEDIUM, COLOR_TITLE))
        column.addContent(
            Row.Builder()
                .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .addContent(text(valueText, VALUE_SP, FONT_WEIGHT_BOLD, accent))
                .apply {
                    if (arrowText.isNotEmpty()) {
                        addContent(Spacer.Builder().setWidth(dp(GAP_XS)).build())
                        addContent(text(arrowText, ARROW_SP, FONT_WEIGHT_MEDIUM, accent))
                    }
                }
                .build()
        )

        // The root is the Column itself. An earlier version wrapped this in a Box(expand, expand)
        // carrying Background/Corner/Padding/Clickable and rendered a completely blank tile with no
        // error anywhere; bisecting proved the wrapper was at fault, not the content.
        return column
            .setModifiers(
                Modifiers.Builder()
                    .setSemantics(
                        Semantics.Builder()
                            .setContentDescription(
                                if (hasReading) "Glucose $valueText" else "Glucose unavailable"
                            )
                            .build()
                    )
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

    private fun text(value: String, size: Float, weight: Int, color: Int) =
        Text.Builder()
            .setText(value)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setWeight(weight)
                    .setColor(argb(color))
                    .build()
            )
            .build()

    private fun clock(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun formatValue(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

    private fun resourceVersion(latest: Long) = "$RESOURCE_PREFIX$latest"

    companion object {

        private const val RESOURCE_PREFIX = "glucose-"
        private const val REFRESH_MS = 5 * 60 * 1000L
        private const val CLICK_ID = "aaps_glucose_tile"
        private const val ID_ICON = "aaps_glucose_icon"
        private const val ID_SPARK = "aaps_glucose_spark"

        private const val MGDL_TO_MMOL = 0.0555

        private const val TITLE_TEXT = "Glucose"
        private const val CURRENT_LABEL = "Current glucose"
        private const val NO_DATA_TEXT = "---"
        private const val VARIATION_SELECTOR = "︎"

        private const val ICON_CIRCLE_DP = 30f
        private const val ICON_DP = 16f

        // The chart is the point of the tile, so it takes the space. Roughly 78% of a 240dp screen,
        // matching how far the platform heart rate tile's chart runs.
        private const val PILL_W = 188f
        private const val PILL_H = 76f

        private const val TITLE_SP = 15f
        private const val CAPTION_SP = 12f
        private const val VALUE_SP = 30f
        private const val ARROW_SP = 21f

        private const val GAP_XS = 4f
        private const val GAP_S = 4f
        private const val GAP_M = 6f

        private const val FALLBACK_LOW = 4.0
        private const val FALLBACK_HIGH = 9.0

        private const val COLOR_TITLE = 0xFFECE8F7.toInt()
        private const val COLOR_CAPTION = 0xFF8F89A3.toInt()
        private const val COLOR_ICON_BG = 0x24B8A6F5
        private const val COLOR_PILL_BG = 0x14B8A6F5
        private const val COLOR_IN_RANGE = 0xFF8FE3B0.toInt()
        private const val COLOR_HIGH = 0xFFF0C674.toInt()
        private const val COLOR_LOW = 0xFFF08A7F.toInt()
        private const val COLOR_NO_DATA = 0xFF6E6678.toInt()
    }
}
