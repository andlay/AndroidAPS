package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.rx.events.EventUpdateSelectedWatchface
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.wear.R
import app.aaps.wear.databinding.ActivityPixelBinding
import app.aaps.wear.watchfaces.utils.BaseWatchFace
import app.aaps.wear.watchfaces.utils.WatchMode
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace
import app.aaps.wear.watchfaces.views.GlucosePoint
import kotlin.math.abs

/**
 * Native-looking digital watch face modeled on the stock Pixel Watch 2 style (v8 layout): a
 * centered time, a continuous glucose-history line wrapped around the bezel, and four quadrant
 * gauge complications (BG hero, delta, IOB, COB) drawn by [app.aaps.wear.watchfaces.views.RingComplicationView]
 * and [app.aaps.wear.watchfaces.views.BezelHistoryView].
 */
@SuppressLint("Deprecated")
class PixelWatchface : BaseWatchFace() {

    private lateinit var binding: ActivityPixelBinding

    override fun inflateLayout(inflater: LayoutInflater): ActivityPixelBinding {
        binding = ActivityPixelBinding.inflate(inflater)
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.PIXEL.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())
        configureRings()
        return binding
    }

    /** One-time ring geometry, ported from the approved mockup's 200x200 logical-unit space. */
    private fun configureRings() {
        val trackHero = ContextCompat.getColor(this, R.color.pixel_track_hero)
        val track = ContextCompat.getColor(this, R.color.pixel_track)

        binding.ringBg.apply {
            startAngle = 284f; endAngle = 356f; ringRadius = 50f; ringStrokeWidth = 4.5f
            trackColor = trackHero; hero = true
        }
        binding.ringDelta.apply {
            startAngle = 4f; endAngle = 76f; ringRadius = 46f; ringStrokeWidth = 3.2f
            trackColor = track; hero = false
        }
        binding.ringIob.apply {
            startAngle = 194f; endAngle = 266f; ringRadius = 46f; ringStrokeWidth = 3.2f
            trackColor = track; hero = false
        }
        binding.ringCob.apply {
            startAngle = 104f; endAngle = 176f; ringRadius = 46f; ringStrokeWidth = 3.2f
            trackColor = track; hero = false
        }
        binding.bezelHistory.setThresholds(LOW_MMOL, HIGH_MMOL, TARGET_MMOL)
    }

    private fun accentColorRes(): Int =
        when (singleBg[0].sgvLevel) {
            1L   -> R.color.dark_highColor
            -1L  -> R.color.dark_lowColor
            0L   -> R.color.pixel_in_range
            else -> R.color.pixel_no_data
        }

    override fun setDataFields() {
        super.setDataFields()
        if (!::binding.isInitialized) return

        // The date chip is integral to this design, not an optional element gated by the generic
        // key_show_date preference (which defaults off and isn't exposed on Pixel's config screen).
        binding.dateTime.visibility = View.VISIBLE

        val bg = singleBg[0]
        val st = status[0]
        val lavender = ContextCompat.getColor(this, R.color.pixel_lavender)
        val accent = ContextCompat.getColor(this, accentColorRes())

        binding.valBg.text = bg.sgvString
        binding.valDelta.text = bg.delta
        binding.valIob.text = st.iobSum
        binding.valCob.text = st.cob

        val bgMmol = bg.sgv * MGDL_TO_MMOL
        binding.ringBg.setValue(normalize(bgMmol).toFloat(), accent)

        val deltaFraction = (abs(bg.deltaMgdl ?: 0.0) / DELTA_MAX_MGDL).coerceIn(0.0, 1.0)
        binding.ringDelta.setValue(deltaFraction.toFloat(), lavender)

        val iobFraction = (parseLeadingNumber(st.iobSum) / IOB_MAX_UNITS).coerceIn(0.0, 1.0)
        binding.ringIob.setValue(iobFraction.toFloat(), lavender)

        val cobFraction = (parseLeadingNumber(st.cob) / COB_MAX_GRAMS).coerceIn(0.0, 1.0)
        binding.ringCob.setValue(cobFraction.toFloat(), lavender)

        val historyPoints = graphData.entries
            .sortedBy { it.timeStamp }
            .map { GlucosePoint(it.timeStamp, it.sgv * MGDL_TO_MMOL) }
        binding.bezelHistory.setHistory(historyPoints)

        updateLoopDot()
    }

    // Tracks the mode we were in before this callback, since the framework already updates
    // currentWatchMode to the new value before invoking onWatchModeChanged.
    private var previousWatchMode: WatchMode? = null

    override fun onWatchModeChanged(watchMode: WatchMode) {
        super.onWatchModeChanged(watchMode)
        if (!::binding.isInitialized) return
        val wokeUp = watchMode == WatchMode.INTERACTIVE && previousWatchMode != null && previousWatchMode != WatchMode.INTERACTIVE
        previousWatchMode = watchMode
        if (wokeUp && canPlayRevealAnimation()) {
            binding.bezelHistory.playRevealAnimation()
        }
    }

    // Defers to the system's own Battery Saver setting rather than a hardcoded percentage -- if the
    // user (or the OS) has decided the device should conserve power, skip this purely cosmetic
    // animation instead of guessing at a threshold ourselves.
    private fun canPlayRevealAnimation(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return !powerManager.isPowerSaveMode
    }

    private fun updateLoopDot() {
        val colorRes = when (loopLevel) {
            1    -> R.color.pixel_loop_ok
            0    -> R.color.pixel_loop_warn
            else -> R.color.pixel_loop_none
        }
        binding.loopDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun setAmbientMode(ambient: Boolean) {
        binding.bezelHistory.setAmbientMode(ambient)
        binding.ringBg.setAmbientMode(ambient)
        binding.ringDelta.setAmbientMode(ambient)
        binding.ringIob.setAmbientMode(ambient)
        binding.ringCob.setAmbientMode(ambient)
    }

    override fun setColorDark() {
        applyTheme(backgroundColorRes = R.color.black, primaryColorRes = R.color.white, secondaryColorRes = R.color.light_grey)
    }

    override fun setColorBright() {
        applyTheme(backgroundColorRes = R.color.white, primaryColorRes = R.color.black, secondaryColorRes = R.color.gray_700)
    }

    override fun setColorLowRes() {
        // Ambient mode: monochrome only, no anti-aliased accent colors, thinner strokes, to respect
        // Wear OS burn-in guidelines.
        val white = ContextCompat.getColor(this, R.color.white)
        binding.mainLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.black))
        binding.time.setTextColor(white)
        binding.valBg.setTextColor(white)
        binding.valDelta.setTextColor(white)
        binding.valIob.setTextColor(white)
        binding.valCob.setTextColor(white)
        binding.dayName.setTextColor(ContextCompat.getColor(this, R.color.grey_500))
        binding.timestamp.setTextColor(ContextCompat.getColor(this, R.color.grey_500))
        binding.loopDot.backgroundTintList = ColorStateList.valueOf(white)
        setAmbientMode(true)
        highColor = white
        lowColor = white
        midColor = white
        gridColor = ContextCompat.getColor(this, R.color.grey_500)
    }

    private fun applyTheme(backgroundColorRes: Int, primaryColorRes: Int, secondaryColorRes: Int) {
        val background = ContextCompat.getColor(this, backgroundColorRes)
        val primary = ContextCompat.getColor(this, primaryColorRes)
        val secondary = ContextCompat.getColor(this, secondaryColorRes)
        val accent = ContextCompat.getColor(this, accentColorRes())

        binding.mainLayout.setBackgroundColor(background)
        binding.time.setTextColor(primary)
        binding.valBg.setTextColor(accent)
        binding.valDelta.setTextColor(primary)
        binding.valIob.setTextColor(primary)
        binding.valCob.setTextColor(primary)
        binding.dayName.setTextColor(secondary)

        val timestampColor = if (ageLevel() == 1) secondary else ContextCompat.getColor(this, R.color.dark_TimestampOld)
        binding.timestamp.setTextColor(timestampColor)

        setAmbientMode(false)
        updateLoopDot()

        highColor = ContextCompat.getColor(this, R.color.dark_highColor)
        lowColor = ContextCompat.getColor(this, R.color.dark_lowColor)
        midColor = ContextCompat.getColor(this, R.color.pixel_in_range)
        gridColor = secondary
    }

    companion object {

        private const val MGDL_TO_MMOL = 0.0555
        private const val GRAPH_V_MIN = 2.2
        private const val GRAPH_V_MAX = 13.3
        private const val LOW_MMOL = 4.0
        private const val HIGH_MMOL = 9.0
        private const val TARGET_MMOL = 6.0

        // Placeholder full-scale references for the IOB/COB/delta gauges, ported from the approved
        // mockup. Not yet calibrated against this user's real typical ranges (plan's open question
        // #3) -- revisit if the gauges routinely peg at 100%.
        private const val IOB_MAX_UNITS = 5.0
        private const val COB_MAX_GRAMS = 60.0
        private const val DELTA_MAX_MGDL = 15.0

        private fun normalize(mmol: Double): Double =
            ((mmol - GRAPH_V_MIN) / (GRAPH_V_MAX - GRAPH_V_MIN)).coerceIn(0.0, 1.0)

        // status[0].iobSum/cob are pre-formatted display strings (e.g. "0.85", "12g", "--g"), not
        // raw numbers -- the wire format (EventData.Status) only carries formatted text. Strip any
        // trailing unit suffix before parsing.
        private fun parseLeadingNumber(s: String): Double {
            val match = Regex("-?[0-9]+([.,][0-9]+)?").find(s) ?: return 0.0
            return SafeParse.stringToDouble(match.value)
        }
    }
}
