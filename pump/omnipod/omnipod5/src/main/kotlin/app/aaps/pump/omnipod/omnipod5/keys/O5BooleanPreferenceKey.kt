package app.aaps.pump.omnipod.omnipod5.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.common.R

enum class O5BooleanPreferenceKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
) : BooleanPreferenceKey {

    /** Experimental: the pod beeps when the phone has not reached it for a while. Off by default. */
    OutOfRangeBeep(
        "AAPS.Omnipod5.out_of_range_beep_enabled", false,
        titleResId = R.string.omnipod_5_preferences_out_of_range_beep,
        summaryResId = R.string.omnipod_5_preferences_out_of_range_beep_summary
    );

    override val preferenceType: PreferenceType = PreferenceType.SWITCH
    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
