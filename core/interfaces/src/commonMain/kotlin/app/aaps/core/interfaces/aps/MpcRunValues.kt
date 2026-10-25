package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

/**
 * What one MPC meal SMB run saw, kept with the loop result so it can be drawn over time.
 *
 * @param mealChance         chance that the current rise is a meal, from the rise alone (0..1)
 * @param weightedMealChance the same after the room above the target is taken into account (0..1); the plan uses this one
 * @param carbsIfMeal        carbs still to come (g) if the rise is a meal, from the carb effect the model expects
 * @param carbsIfNoMeal      carbs still to come (g) if the rise fades (false start)
 * @param carbsExpected      carbs still to come (g), the two mixed by [weightedMealChance]
 * @param impactAgeMinutes   how long the current carb rise has been running, or -1 when none runs
 */
@Serializable
data class MpcRunValues(
    val mealChance: Double,
    val weightedMealChance: Double,
    val carbsIfMeal: Double,
    val carbsIfNoMeal: Double,
    val carbsExpected: Double,
    val impactAgeMinutes: Int
)
