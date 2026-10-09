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

    /** Minutes without contact before the pod beeps. 20 at least, so a normal gap between loop runs never sets it off. */
    OutOfRangeBeepMinutes(
        "AAPS.Omnipod5.out_of_range_beep_minutes", min = 20, max = 120, defaultValue = 30,
        titleResId = R.string.omnipod_5_preferences_out_of_range_beep_minutes,
        dependency = O5BooleanPreferenceKey.OutOfRangeBeep
    );

    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD
    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val entries: Map<Int, TextRef> = emptyMap()
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
