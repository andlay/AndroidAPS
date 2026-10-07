package app.aaps.core.interfaces.aps

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Shower mode: a hot shower warms the sensor and makes BG read higher than it is. For a short time
 * the loop does not see BG rise above the value it had when shower mode started, and gives no SMB.
 * A real fall is always seen at once. Only the loop's input is changed - the alarms and widgets keep
 * showing the real sensor value, and the graph shows both.
 *
 * Each shower is kept as a [ShowerEpisode], and the BG history the loop works from stays capped for
 * it (see [applyShowerCaps]), so autosens, COB and the deltas never count the heat rise. Autosens also
 * leaves out every deviation in the shower window (see [isInShowerWindow]), falls included.
 */
interface ShowerMode {

    /** Start shower mode for [minutes], capping the loop at the current BG. Returns false when there is no recent BG. */
    suspend fun start(minutes: Int): Boolean

    /** End shower mode now. */
    fun stop()
}

/** One shower: from [start] to [end] (planned, or when it was ended early), BG capped at [capMgdl]. */
data class ShowerEpisode(val start: Long, val end: Long, val capMgdl: Double)

/** After a shower ends, the cap holds at most this long while the sensor still reads above it. */
const val SHOWER_CAP_TAIL_MS = 30L * 60 * 1000

/** Episodes are kept this long: more than the 24 h + insulin action the calculations look back. */
const val SHOWER_HISTORY_KEEP_MS = 48L * 60 * 60 * 1000

/** Stored as `start,end,cap` entries separated by `;`. A broken entry is skipped. */
fun decodeShowerEpisodes(stored: String): List<ShowerEpisode> =
    stored.split(';').mapNotNull { entry ->
        val parts = entry.split(',')
        if (parts.size != 3) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        val cap = parts[2].toDoubleOrNull() ?: return@mapNotNull null
        ShowerEpisode(start, end, cap)
    }

fun encodeShowerEpisodes(episodes: List<ShowerEpisode>): String =
    episodes.joinToString(";") { "${it.start},${it.end},${it.capMgdl}" }

fun Preferences.showerEpisodes(): List<ShowerEpisode> = decodeShowerEpisodes(get(StringNonKey.ShowerModeEpisodes))

/**
 * True when [time] is inside a shower or the [SHOWER_CAP_TAIL_MS] after it. Autosens leaves these
 * deviations out, the same way it leaves out meals: the cap hides rises, and heat makes insulin
 * absorb faster and can make the sensor read low, so a fall here says nothing about sensitivity.
 */
fun List<ShowerEpisode>.isInShowerWindow(time: Long): Boolean =
    any { time >= it.start && time <= it.end + SHOWER_CAP_TAIL_MS }

/**
 * Caps the BG the loop works from (the bucketed, smoothed data) for every shower episode. Only
 * [InMemoryGlucoseValue.smoothed] is changed, so the raw reading stays as it was. A value below the
 * cap is never changed: a fall is always real.
 *
 * Inside the episode every value is capped. After its end, the cap holds while the reading is still
 * above it, for at most [SHOWER_CAP_TAIL_MS]: the first reading at or below the cap ends it. Without
 * this a sensor still warm when the timer ran out showed as a sudden jump from the cap to the real
 * value, which the loop would read as a fast rise.
 *
 * Returns a new list in the same order. Values that are not capped are the same objects.
 */
fun List<InMemoryGlucoseValue>.applyShowerCaps(episodes: List<ShowerEpisode>): List<InMemoryGlucoseValue> {
    if (episodes.isEmpty() || isEmpty()) return this
    val capped = HashMap<Long, Double>()
    val byTime = sortedBy { it.timestamp }
    for (episode in episodes) {
        var tailRunning = true
        for (gv in byTime) {
            val t = gv.timestamp
            if (t < episode.start) continue
            if (t > episode.end + SHOWER_CAP_TAIL_MS) break
            val current = capped[t] ?: gv.recalculated
            when {
                t < episode.end                    -> if (current > episode.capMgdl) capped[t] = episode.capMgdl
                !tailRunning                       -> Unit
                current <= episode.capMgdl         -> tailRunning = false
                else                               -> capped[t] = episode.capMgdl
            }
        }
    }
    if (capped.isEmpty()) return this
    return map { gv -> capped[gv.timestamp]?.let { gv.copy(smoothed = it) } ?: gv }
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
