package app.aaps.pump.omnipod.omnipod5.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.common.R

enum class O5IntPreferenceKey(
    override val key: String,
    override val min: Int,
    override val max: Int,
    override val defaultValue: Int,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val dependency: BooleanPreferenceKey? = null,
) : IntPreferenceKey {

    /**
     * Minutes without contact before the pod beeps. AAPS sets the alert again about every 5
     * minutes (at half this time for a shorter setting), so 10 means "beep after one missed
     * update". Down to 1 for testing.
     */
    OutOfReachBeepMinutes(
        "AAPS.Omnipod5.out_of_reach_beep_minutes", min = 1, max = 120, defaultValue = 10,
        titleResId = R.string.omnipod_5_preferences_out_of_reach_beep_minutes,
        dependency = O5BooleanPreferenceKey.OutOfReachBeep
    ),

    /**
     * Minutes without a new BG before the pod beeps, through the same alert. 0 = off. Works only
     * with the out-of-reach beep on, because AAPS has to keep setting the alert.
     */
    StaleBgBeepMinutes(
        "AAPS.Omnipod5.stale_bg_beep_minutes", min = 0, max = 120, defaultValue = 0,
        titleResId = R.string.omnipod_5_preferences_stale_bg_beep_minutes,
        summaryResId = R.string.omnipod_5_preferences_stale_bg_beep_minutes_summary,
        dependency = O5BooleanPreferenceKey.OutOfReachBeep
    );

    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD
    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val entries: Map<Int, TextRef> = emptyMap()
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
