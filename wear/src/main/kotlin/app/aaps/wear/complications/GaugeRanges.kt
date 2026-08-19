package app.aaps.wear.complications

import androidx.wear.watchface.complications.data.RangedValueComplicationData

/**
 * Full-scale ranges for the RANGED_VALUE form of the AAPS complications that drive the Watch Face
 * Format gauge rings.
 *
 * These live here, in Kotlin, rather than in the watch face's XML on purpose. The WFF face normalises
 * whatever the provider reports via [COMPLICATION.RANGED_VALUE_MIN]/[..._MAX], so the face needs no
 * clinical knowledge at all; putting the numbers here keeps them unit-testable and lets glucose derive
 * its range from the user's own profile thresholds instead of a constant.
 *
 * NOTE: the IOB and COB full scales below are still the mockup-era placeholders flagged as an open
 * question in PIXEL_WATCHFACE_PLAN.md. They have never been confirmed against this user's actual
 * typical ranges, and a gauge with a wrong full scale is misleading rather than merely ugly, so they
 * should be made user-configurable (or profile-derived, like glucose already is) before this is
 * offered to anyone else.
 */
object GaugeRanges {

    /** Fallback glucose display range in mg/dL, used only when the profile reports no thresholds. */
    const val BG_FALLBACK_LOW_MGDL = 70.0
    const val BG_FALLBACK_HIGH_MGDL = 180.0

    /** Glucose gauge is padded either side of the in-range band so in-range sits mid-sweep. */
    const val BG_RANGE_PADDING_MGDL = 40.0

    /** Delta gauge is symmetric about zero: a flat trend rests at the middle of the quadrant. */
    const val DELTA_HALF_RANGE_MGDL = 15.0

    /** PLACEHOLDER, see class doc. */
    const val IOB_MAX_UNITS = 5.0

    /** PLACEHOLDER, see class doc. */
    const val COB_MAX_GRAMS = 60.0

    /**
     * Parse the leading number out of one of the wear Status strings (iobSum, cob), which arrive
     * pre-formatted for display and may carry units or suffixes such as "1.25U" or "12g".
     * Returns null when there is no parseable number, so callers can distinguish "no data" from zero
     * rather than silently rendering an empty gauge as a real zero reading.
     */
    fun leadingNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("-?\\d+(\\.\\d+)?").find(raw.replace(',', '.')) ?: return null
        return m.value.toDoubleOrNull()
    }

    /** Build a RANGED_VALUE, clamping value into [min, max] as the androidx builder requires. */
    fun ranged(value: Double, min: Double, max: Double): Triple<Float, Float, Float> {
        val lo = min.toFloat()
        val hi = if (max > min) max.toFloat() else (min + 1).toFloat()
        return Triple(value.toFloat().coerceIn(lo, hi), lo, hi)
    }
}
