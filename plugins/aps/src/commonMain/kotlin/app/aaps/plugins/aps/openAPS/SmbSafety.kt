package app.aaps.plugins.aps.openAPS

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

/**
 * The lowest current BG (mg/dL) at which an SMB is allowed: the higher of the fixed minimum and the
 * percentage of the loop's current target. 0 when both settings are off (0).
 */
internal fun smbMinBgMgdl(minBgMgdl: Double, minPercentOfTarget: Int, targetMgdl: Double): Double =
    max(minBgMgdl, targetMgdl * minPercentOfTarget / 100.0)

/**
 * No SMB while the current BG is below the SMB minimum (SMB settings).
 * Temp basals are not affected. The reason is added to [inputConstraints], so it shows with the result.
 * Shared by OpenAPS SMB and AutoISF, so switching algorithm does not drop these limits.
 */
internal fun applySmbSafetyLimits(
    allowed: Boolean,
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
    val minBg = smbMinBgMgdl(
        minBgMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsSmbMinBg)),
        minPercentOfTarget = preferences.get(IntKey.ApsSmbMinPercentOfTarget),
        targetMgdl = targetMgdl
    )
    if (bgMgdl < minBg) {
        val reason = rh.gs(ApsStrings.smb_disabled_below_min_bg, profileUtil.fromMgdlToStringWithUnits(bgMgdl), profileUtil.fromMgdlToStringWithUnits(minBg))
        inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, reason, from) })
        return false
    }
    return true
}
