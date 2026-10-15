package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

/**
 * The ISF factors of one AutoISF run. 1.0 is no change, above 1.0 is a stronger ISF (more insulin).
 *
 * @param acce    from the acceleration of BG (the fitted parabola), braking when below 1.0
 * @param bg      from how far BG is from the target
 * @param pp      from a rise after a meal (post-prandial)
 * @param dura    from BG being stuck high
 * @param applied the factor that was used, after the limits and autosens
 */
@Serializable
data class AutoIsfFactors(
    val acce: Double,
    val bg: Double,
    val pp: Double,
    val dura: Double,
    val applied: Double
)
