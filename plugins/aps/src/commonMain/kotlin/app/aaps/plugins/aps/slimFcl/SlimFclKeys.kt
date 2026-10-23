package app.aaps.plugins.aps.slimFcl

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

/** Settings of the Slim FCL controller. Defaults are the values tuned on the closed-loop replay. */
enum class SlimFclIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val title: TextRef,
    override val summary: TextRef?,
    override val min: Int,
    override val max: Int,
    override val unitType: UnitType = UnitType.NONE
) : IntPreferenceKey {

    LowLine("aps_slim_low_line", 115, ApsStrings.slim_fcl_low_line_title, ApsStrings.slim_fcl_low_line_summary, 90, 160, UnitType.MGDL),
    HighLine("aps_slim_high_line", 145, ApsStrings.slim_fcl_high_line_title, ApsStrings.slim_fcl_high_line_summary, 110, 250, UnitType.MGDL),
    LowWeight("aps_slim_low_weight", 1000, ApsStrings.slim_fcl_low_weight_title, ApsStrings.slim_fcl_low_weight_summary, 100, 10000),
    RoomBoost("aps_slim_room_boost", 35, ApsStrings.slim_fcl_room_boost_title, ApsStrings.slim_fcl_room_boost_summary, 0, 100, UnitType.PERCENT),
    TrendRoom("aps_slim_trend_room", 15, ApsStrings.slim_fcl_trend_room_title, ApsStrings.slim_fcl_trend_room_summary, 0, 60, UnitType.MIN),
    SecondStage("aps_slim_second_stage", 20, ApsStrings.slim_fcl_second_stage_title, ApsStrings.slim_fcl_second_stage_summary, 0, 60, UnitType.MIN)
}

enum class SlimFclDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val title: TextRef,
    override val summary: TextRef?,
    override val min: Double,
    override val max: Double,
    override val unitType: UnitType = UnitType.INSULIN
) : DoublePreferenceKey {

    MaxSmb("aps_slim_max_smb", 3.0, ApsStrings.slim_fcl_max_smb_title, ApsStrings.slim_fcl_max_smb_summary, 0.1, 10.0),
    MaxIob("aps_slim_max_iob", 5.0, ApsStrings.slim_fcl_max_iob_title, ApsStrings.slim_fcl_max_iob_summary, 0.0, 25.0);

    override val calculatedBySM: Boolean = false
}
