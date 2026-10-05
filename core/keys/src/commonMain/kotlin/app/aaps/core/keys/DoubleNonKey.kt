package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
    override val exportable: Boolean = true
) : DoubleNonPreferenceKey {

    // Shower mode: the BG (mg/dL) the loop is capped at while it runs. Not exported, like ShowerModeEndsAt.
    ShowerModeCapMgdl("shower_mode_cap_mgdl", 0.0, exportable = false),
}