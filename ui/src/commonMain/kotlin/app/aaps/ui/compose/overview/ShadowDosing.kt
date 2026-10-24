package app.aaps.ui.compose.overview

/** One stored decision of the alpha shadow loop. [rate] U/h and [durationMinutes] count only when [tempRequested]. */
internal class ShadowDecision(val time: Long, val tempRequested: Boolean, val rate: Double, val durationMinutes: Int, val smb: Double)

/**
 * The shadow insulin plan between [ShadowPlan.from] and [ShadowPlan.to]: SMBs and the basal rate the shadow loop chose.
 * [netParts]: the basal against the profile in 5 min parts (middle of the part, units), as the IOB calculation uses it.
 */
internal class ShadowPlan(
    val smbs: List<Pair<Long, Double>>,
    val basal: List<Pair<Long, Double>>,
    val netParts: List<Pair<Long, Double>>
)

/**
 * Builds what the alpha loop would have given, from its stored decisions:
 * - a decision with a temp request sets that rate for its duration (rate 0 for 0 min is "cancel temp": profile basal),
 *   until the next decision;
 * - a decision without a temp request lets the temp that was really running go on, because that is the temp the loop
 *   looked at;
 * - each decision's SMB is given at its time;
 * - where there is no decision for longer than [gapMs] (the shadow loop did not run), the real basal and the real SMBs
 *   are used, so the IOB does not fall away.
 * [decisions] sorted by time.
 */
internal fun shadowPlan(
    decisions: List<ShadowDecision>,
    from: Long,
    to: Long,
    profileBasalAt: (Long) -> Double,
    realBasalAt: (Long) -> Double,
    realSmbs: List<Pair<Long, Double>>,
    gapMs: Long = 11 * 60_000L
): ShadowPlan {
    val times = LongArray(decisions.size) { decisions[it].time }
    fun lastDecision(t: Long): ShadowDecision? {
        var lo = 0
        var hi = times.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                found = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return if (found < 0) null else decisions[found]
    }

    fun rateAt(t: Long): Double {
        val d = lastDecision(t)
        return when {
            d == null || t - d.time > gapMs                -> realBasalAt(t)
            !d.tempRequested                               -> realBasalAt(t)
            d.durationMinutes <= 0                         -> profileBasalAt(t)
            t < d.time + d.durationMinutes * 60_000L       -> d.rate
            else                                           -> profileBasalAt(t)
        }
    }

    val smbs = decisions.filter { it.smb > 0 && it.time in from..to }.map { it.time to it.smb } +
        realSmbs.filter { (t, _) -> t in from..to && lastDecision(t).let { d -> d == null || t - d.time > gapMs } }

    val basal = mutableListOf<Pair<Long, Double>>()
    var last = -1.0
    var t = from
    while (t < to) {
        val r = rateAt(t)
        if (r != last) {
            basal += t to r; last = r
        }
        t += 60_000L
    }
    if (last >= 0) basal += to to last

    val netParts = mutableListOf<Pair<Long, Double>>()
    t = from
    while (t < to) {
        val mid = t + 150_000L
        val net = (rateAt(mid) - profileBasalAt(mid)) * 5.0 / 60.0
        if (net != 0.0) netParts += mid to net
        t += 300_000L
    }
    return ShadowPlan(smbs.sortedBy { it.first }, basal, netParts)
}
