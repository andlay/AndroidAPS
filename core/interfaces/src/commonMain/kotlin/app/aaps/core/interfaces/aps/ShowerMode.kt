package app.aaps.core.interfaces.aps

import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Shower mode: a hot shower warms the sensor and makes BG read higher than it is. For a short time
 * the loop does not see BG rise above the value it had when shower mode started, and gives no SMB.
 * A real fall is always seen at once. Only the loop's input is changed - the graph, alarms and
 * widgets keep showing the real sensor value.
 */
interface ShowerMode {

    /** Start shower mode for [minutes], capping the loop at the current BG. Returns false when there is no recent BG. */
    suspend fun start(minutes: Int): Boolean

    /** End shower mode now. */
    fun stop()
}

/**
 * The BG (mg/dL) the loop is capped at, or null when shower mode is not running at [now].
 * A plain read of the stored state, so the algorithm needs nothing injected for it.
 */
fun Preferences.activeShowerCapMgdl(now: Long): Double? {
    val endsAt = get(LongNonKey.ShowerModeEndsAt)
    val cap = get(DoubleNonKey.ShowerModeCapMgdl)
    return if (now < endsAt && cap > 0.0) cap else null
}
