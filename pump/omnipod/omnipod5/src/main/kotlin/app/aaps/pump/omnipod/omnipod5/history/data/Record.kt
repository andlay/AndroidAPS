package app.aaps.pump.omnipod.omnipod5.history.data

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.profile.Profile

sealed class Record

data class BolusRecord(val amount: Double, val bolusType: BolusType) : Record()

data class TempBasalRecord(val duration: Int, val rate: Double) : Record()

data class BasalValuesRecord(val segments: List<Profile.ProfileValue>) : Record()

/**
 * Stored in the pod history by name, so the names must not change. There is on purpose no way back to
 * an AAPS bolus type: a basal drift compensation is basal insulin and must never be synced as a bolus.
 */
enum class BolusType {
    DEFAULT, SMB, BASAL_DRIFT_COMPENSATION;

    companion object {
        fun fromBolusInfoBolusType(type: BS.Type): BolusType = when (type) {
            BS.Type.SMB -> SMB
            else -> DEFAULT
        }
    }
}
