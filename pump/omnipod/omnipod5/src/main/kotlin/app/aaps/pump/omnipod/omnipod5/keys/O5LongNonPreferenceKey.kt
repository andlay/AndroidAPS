package app.aaps.pump.omnipod.omnipod5.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class O5LongNonPreferenceKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    /** The pod that has the out-of-range alert set (0 = none), so turning the feature off also
     *  clears it after an app restart. Pod state of this phone, so never exported. */
    OutOfRangeAlertPodId("AAPS.Omnipod5.out_of_range_alert_pod_id", 0L, exportable = false),
}
