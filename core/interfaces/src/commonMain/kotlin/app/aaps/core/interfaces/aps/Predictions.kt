package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Serializable
data class Predictions(
    var IOB: List<Int>? = null,
    var ZT: List<Int>? = null,
    var COB: List<Int>? = null,
    var aCOB: List<Int>? = null, // AMA only
    var UAM: List<Int>? = null,
    /** MPC only: BG if this rise is a meal, if it fades (false start), and the two weighted by the meal chance, all with the chosen dose */
    var mpcMeal: List<Int>? = null,
    var mpcFalseStart: List<Int>? = null,
    var mpcExpected: List<Int>? = null
)