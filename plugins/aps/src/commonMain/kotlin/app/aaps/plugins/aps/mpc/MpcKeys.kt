package app.aaps.plugins.aps.mpc

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

/** MPC meal SMBs, an option of the OpenAPS SMB plugin */
enum class MpcBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef?,
    override val dependency: BooleanPreferenceKey? = null
) : BooleanPreferenceKey {

    MealSmb("aps_mpc_meal_smb", false, ApsStrings.mpc_meal_smb_title, ApsStrings.mpc_meal_smb_summary),

    /** Even target (in the user's units: 5.0, 5.4, 6.0 mmol/L or 100, 108 mg/dL) allows meal SMBs, odd target stops them */
    EvenTargetSmb("aps_mpc_smb_even_target", true, ApsStrings.mpc_even_target_title, ApsStrings.mpc_even_target_summary, dependency = MealSmb)
}

/**
 * The two meal settings, both learned from the user's data. Defaults: the fitted typical meal and rise, each 20 % up in
 * height and length, which on the replay gives the same results with plain profile ISF as the AutoISF factor did.
 * The rest of the meal shape is fixed at the fitted values (see [MpcMealSmb.FIXED_SHAPE]).
 */
enum class MpcDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val title: TextRef,
    override val summary: TextRef?,
    override val min: Double,
    override val max: Double,
    override val unitType: UnitType,
    override val dependency: BooleanPreferenceKey? = MpcBooleanKey.MealSmb
) : DoublePreferenceKey {

    TypicalMeal("aps_mpc_typical_meal", 49.28, ApsStrings.mpc_typical_meal_title, ApsStrings.mpc_typical_meal_summary, 5.0, 200.0, UnitType.GRAMS),
    RiseLength("aps_mpc_rise_length", 113.15, ApsStrings.mpc_rise_length_title, ApsStrings.mpc_rise_length_summary, 30.0, 300.0, UnitType.MIN);

    override val calculatedBySM: Boolean = false
}
