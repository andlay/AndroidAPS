package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.aps.ApsStrings
import kotlin.math.max
import kotlin.math.min

/**
 * The lowest current BG (mg/dL) at which an SMB is allowed: the higher of the fixed minimum and the
 * percentage of the loop's current target. 0 when both settings are off (0).
 */
internal fun smbMinBgMgdl(minBgMgdl: Double, minPercentOfTarget: Int, targetMgdl: Double): Double =
    max(minBgMgdl, targetMgdl * minPercentOfTarget / 100.0)

/**
 * The glucose status the loop sees during shower mode: BG no higher than [capMgdl], and a rise
 * above it counts as flat. Below the cap nothing changes, so a real fall is seen at once.
 */
internal fun GlucoseStatus.cappedAt(capMgdl: Double): GlucoseStatus {
    if (glucose <= capMgdl) return this
    val delta = min(delta, 0.0)
    val shortAvgDelta = min(shortAvgDelta, 0.0)
    val longAvgDelta = min(longAvgDelta, 0.0)
    return when (this) {
        // AutoISF also reads the rise from its parabola fit: a rise counts as flat there too
        is GlucoseStatusAutoIsf -> copy(
            glucose = capMgdl, delta = delta, shortAvgDelta = shortAvgDelta, longAvgDelta = longAvgDelta,
            deltaPl = min(deltaPl, 0.0), deltaPn = min(deltaPn, 0.0), bgAcceleration = min(bgAcceleration, 0.0)
        )
        is GlucoseStatusSMB     -> copy(glucose = capMgdl, delta = delta, shortAvgDelta = shortAvgDelta, longAvgDelta = longAvgDelta)
        else                    -> GlucoseStatusSMB(capMgdl, noise, delta, shortAvgDelta, longAvgDelta, date)
    }
}

/**
 * No SMB while shower mode runs, or while the current BG is below the SMB minimum (Safety settings).
 * Temp basals are not affected. The reason is added to [inputConstraints], so it shows with the result.
 * Shared by OpenAPS SMB and AutoISF, so switching algorithm does not drop these limits.
 */
internal fun applySmbSafetyLimits(
    allowed: Boolean,
    showerMode: Boolean,
    bgMgdl: Double,
    targetMgdl: Double,
    preferences: Preferences,
    profileUtil: ProfileUtil,
    rh: TextResolver,
    aapsLogger: AAPSLogger,
    inputConstraints: Constraint<Double>,
    from: Any
): Boolean {
    if (!allowed) return false
    if (showerMode) {
        inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, rh.gs(ApsStrings.smb_disabled_shower_mode), from) })
        return false
    }
    val minBg = smbMinBgMgdl(
        minBgMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.SafetySmbMinBg)),
        minPercentOfTarget = preferences.get(IntKey.SafetySmbMinPercentOfTarget),
        targetMgdl = targetMgdl
    )
    if (bgMgdl < minBg) {
        val reason = rh.gs(ApsStrings.smb_disabled_below_min_bg, profileUtil.fromMgdlToStringWithUnits(bgMgdl), profileUtil.fromMgdlToStringWithUnits(minBg))
        inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, reason, from) })
        return false
    }
    return true
}
