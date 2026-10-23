package app.aaps.plugins.aps.mpc

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

/** Settings of the MPC controller. Defaults are the values tuned on the closed-loop replay. */
enum class MpcIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val title: TextRef,
    override val summary: TextRef?,
    override val min: Int,
    override val max: Int,
    override val unitType: UnitType = UnitType.NONE
) : IntPreferenceKey {

    LowLine("aps_mpc_low_line", 115, ApsStrings.mpc_low_line_title, ApsStrings.mpc_low_line_summary, 90, 160, UnitType.MGDL),
    HighLine("aps_mpc_high_line", 145, ApsStrings.mpc_high_line_title, ApsStrings.mpc_high_line_summary, 110, 250, UnitType.MGDL),
    LowWeight("aps_mpc_low_weight", 1000, ApsStrings.mpc_low_weight_title, ApsStrings.mpc_low_weight_summary, 100, 10000),
    RoomBoost("aps_mpc_room_boost", 35, ApsStrings.mpc_room_boost_title, ApsStrings.mpc_room_boost_summary, 0, 100, UnitType.PERCENT),
    TrendRoom("aps_mpc_trend_room", 15, ApsStrings.mpc_trend_room_title, ApsStrings.mpc_trend_room_summary, 0, 60, UnitType.MIN),
    SecondStage("aps_mpc_second_stage", 20, ApsStrings.mpc_second_stage_title, ApsStrings.mpc_second_stage_summary, 0, 60, UnitType.MIN),
    MealReadiness("aps_mpc_meal_readiness", 100, ApsStrings.mpc_meal_readiness_title, ApsStrings.mpc_meal_readiness_summary, 25, 400, UnitType.PERCENT),
    /** 0 = off. 27 was the best tested value on the replay. */
    StepDownMax("aps_mpc_step_down", 0, ApsStrings.mpc_step_down_title, ApsStrings.mpc_step_down_summary, 0, 36, UnitType.MGDL)
}

/** Doses, and the meal shape in readable form (see [MpcShapeSettings]). Shape defaults are the values fitted to the user's data. */
enum class MpcDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val title: TextRef,
    override val summary: TextRef?,
    override val min: Double,
    override val max: Double,
    override val unitType: UnitType
) : DoublePreferenceKey {

    MaxSmb("aps_mpc_max_smb", 3.0, ApsStrings.mpc_max_smb_title, ApsStrings.mpc_max_smb_summary, 0.1, 10.0, UnitType.INSULIN),
    MaxIob("aps_mpc_max_iob", 5.0, ApsStrings.mpc_max_iob_title, ApsStrings.mpc_max_iob_summary, 0.0, 25.0, UnitType.INSULIN),
    TypicalMeal("aps_mpc_typical_meal", 34.220958423007964, ApsStrings.mpc_typical_meal_title, ApsStrings.mpc_typical_meal_summary, 5.0, 200.0, UnitType.GRAMS),
    MealSizeSpread("aps_mpc_meal_size_spread", 44.66631518562105, ApsStrings.mpc_meal_size_spread_title, ApsStrings.mpc_meal_size_spread_summary, 5.0, 200.0, UnitType.PERCENT),
    RiseLength("aps_mpc_rise_length", 94.2876571094416, ApsStrings.mpc_rise_length_title, ApsStrings.mpc_rise_length_summary, 30.0, 300.0, UnitType.MIN),
    RiseLengthSpread("aps_mpc_rise_length_spread", 57.67959616000833, ApsStrings.mpc_rise_length_spread_title, ApsStrings.mpc_rise_length_spread_summary, 5.0, 200.0, UnitType.PERCENT),
    PeakPosition("aps_mpc_peak_position", 0.37560639252131145, ApsStrings.mpc_peak_position_title, ApsStrings.mpc_peak_position_summary, 0.1, 0.9, UnitType.DOUBLE_2),
    HalfWidth("aps_mpc_half_width", 0.4861028150748753, ApsStrings.mpc_half_width_title, ApsStrings.mpc_half_width_summary, 0.1, 0.95, UnitType.DOUBLE_2),
    TailHalfLife("aps_mpc_tail_half_life", 20.79441541679836, ApsStrings.mpc_tail_half_life_title, ApsStrings.mpc_tail_half_life_summary, 5.0, 120.0, UnitType.MIN),
    /** 0 = off: the low line stays as set */
    TargetHbA1c("aps_mpc_target_hba1c", 0.0, ApsStrings.mpc_target_hba1c_title, ApsStrings.mpc_target_hba1c_summary, 0.0, 10.0, UnitType.DOUBLE);

    override val calculatedBySM: Boolean = false
}

/** State the plugin keeps between runs */
enum class MpcDoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
) : DoubleNonPreferenceKey {

    /** The low line the HbA1c aim has moved to, mg/dL. 0 = not started yet. */
    AimLowLine("aps_mpc_aim_low_line", 0.0)
}
