package app.aaps.wear.complications

// Bitmap sizing note: ComplicationData crosses a Binder boundary, whose per-process transaction
// buffer is ~1MB and is shared with everything else in flight. At this device's native 480x480 an
// ARGB_8888 bitmap is 480*480*4 = 921,600 bytes -- close enough to the ceiling to be a real
// TransactionTooLargeException risk, so `size` is capped at MAX_BITMAP_DIMENSION_PX below.
//
// Deliberately NOT switching to RGB_565 to save bytes, despite that being the obvious halving: 565
// has no alpha channel, so the bitmap would arrive as an opaque rectangle covering the whole face.
// That happens to look identical *today* only because this slot is drawn first and the scene
// background is solid black -- it would silently break the moment either of those changes. Capping
// the dimension keeps the alpha channel and is robust to that.
//
// The cap costs almost nothing visually: the graphic is a thin ring near the bezel, and the face
// scales it back up by 480/400 = 1.2x. The actual encoded size is logged on every render so the real
// headroom can be measured rather than guessed at.

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.view.WindowManager
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.watchfaces.views.BezelHistoryRenderer
import app.aaps.wear.watchfaces.views.GlucosePoint
import dagger.android.AndroidInjection
import kotlin.math.sin

/**
 * Renders the same glucose-history-around-the-bezel graphic as
 * [app.aaps.wear.watchfaces.views.BezelHistoryView] (used live by the classic Canvas-based watch
 * faces), but off-screen to a [Bitmap] via the shared [BezelHistoryRenderer], exposed as a
 * PHOTO_IMAGE complication so a Watch Face Format face -- which can't run arbitrary Canvas code
 * itself -- can display it through a `<Complication type="PHOTO_IMAGE">` element bound to this data
 * source.
 *
 * Data comes from the same [app.aaps.wear.data.ComplicationDataRepository]-backed DataStore that
 * [app.aaps.wear.watchfaces.utils.BaseWatchFace.graphData] reads from, via the request-handling
 * already implemented in [ModernBaseComplicationProviderService.onComplicationRequest].
 */
class BezelHistoryComplicationService : ModernBaseComplicationProviderService() {

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
        return when (type) {
            ComplicationType.PHOTO_IMAGE -> buildPhotoImageComplication(data, complicationPendingIntent)
            else                         -> {
                aapsLogger.warn(LTag.WEAR, "BezelHistoryComplicationService unexpected type: $type")
                null
            }
        }
    }

    private fun buildPhotoImageComplication(
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): PhotoImageComplicationData {
        // Same ordering/conversion PixelWatchface.setDataFields() applies to graphData.entries
        // before handing them to BezelHistoryView.setHistory().
        val points = data.graphData.entries
            .sortedBy { it.timeStamp }
            .map { GlucosePoint(it.timeStamp, it.sgv * MGDL_TO_MMOL) }

        val bitmap = renderBitmap(points)
        // byteCount is what actually has to cross the Binder boundary; log it so the headroom against
        // the ~1MB transaction limit is a measured number rather than an assumption (see file header).
        aapsLogger.debug(
            LTag.WEAR,
            "BezelHistoryComplicationService rendered ${bitmap.width}x${bitmap.height} bitmap " +
                "(${bitmap.byteCount} bytes) from ${points.size} history points"
        )

        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithBitmap(bitmap),
            contentDescription = PlainComplicationText.Builder(text = "Glucose history").build()
        )
            .setTapAction(complicationPendingIntent)
            .build()
    }

    private fun renderBitmap(points: List<GlucosePoint>): Bitmap {
        // Screen-bounds-derived size, same approach WallpaperComplication uses below for its
        // LARGE_IMAGE/PHOTO_IMAGE complications, rather than a hardcoded constant -- this project's
        // exact target-device resolution isn't nailed down elsewhere in the repo (see the "Confirm
        // exact screen size" note in PIXEL_WATCHFACE_PLAN.md).
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.currentWindowMetrics.bounds
        val size = minOf(bounds.width(), bounds.height())
            .coerceAtLeast(1)
            .coerceAtMost(MAX_BITMAP_DIMENSION_PX)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        BezelHistoryRenderer.draw(
            canvas = Canvas(bitmap),
            widthPx = size,
            heightPx = size,
            points = points,
            lowThreshold = BezelHistoryRenderer.DEFAULT_LOW,
            highThreshold = BezelHistoryRenderer.DEFAULT_HIGH,
            targetValue = BezelHistoryRenderer.DEFAULT_TARGET,
            ambient = false,
            revealFraction = 1f,
            paints = BezelHistoryRenderer.Paints()
        )
        return bitmap
    }

    // ModernBaseComplicationProviderService.getPreviewData() already delegates to
    // buildComplicationData() using whatever this returns -- overriding getPreviewData() itself, as
    // suggested by the generic "implement getPreviewData" complication-data-source checklist, would
    // just duplicate that dispatch. This is the intended customization point instead. The base
    // sample's graphData is empty (no synthetic history), which would render an empty bezel graph in
    // the watch-face picker/config screen, so synthesize a plausible-looking wave here.
    override fun getPreviewComplicationData(): app.aaps.wear.data.ComplicationData {
        val now = System.currentTimeMillis()
        val entries = ArrayList<EventData.SingleBg>()
        for (i in 0 until PREVIEW_POINT_COUNT) {
            val timeStamp = now - (PREVIEW_POINT_COUNT - 1 - i) * PREVIEW_INTERVAL_MS
            val sgvMgdl = PREVIEW_SGV_CENTER + PREVIEW_SGV_AMPLITUDE * sin(i * PREVIEW_WAVE_STEP)
            entries.add(EventData.SingleBg(dataset = 0, timeStamp = timeStamp, sgv = sgvMgdl, high = 180.0, low = 70.0))
        }
        return super.getPreviewComplicationData().copy(graphData = EventData.GraphData(entries = entries))
    }

    override fun getProviderCanonicalName(): String = BezelHistoryComplicationService::class.java.canonicalName!!

    companion object {

        private const val MGDL_TO_MMOL = 0.0555

        // See the bitmap-sizing note at the top of this file. 400x400 ARGB_8888 is 640,000 bytes,
        // which leaves comfortable room under the ~1MB Binder transaction buffer.
        private const val MAX_BITMAP_DIMENSION_PX = 400

        private const val PREVIEW_POINT_COUNT = 24
        private const val PREVIEW_INTERVAL_MS = 5 * 60 * 1000L
        private const val PREVIEW_SGV_CENTER = 120.0
        private const val PREVIEW_SGV_AMPLITUDE = 40.0
        private const val PREVIEW_WAVE_STEP = 0.5
    }
}
