package app.aaps.workflow

/** The 5 minute step of the IOB, COB, BGI and DEV graphs. */
internal const val GRAPH_STEP_MS = 5 * 60 * 1000L

/**
 * The first time of the 5 minute graph grid, chosen so that the grid passes exactly through the
 * newest reading. Each grid time shows the newest reading at or before it, and a time after now
 * shows nothing, so a grid that misses the newest reading shows it up to 5 minutes late, and every
 * point up to 5 minutes right of its reading. Without a reading in the range the grid starts at
 * [fromTime].
 */
internal fun gridStartThrough(fromTime: Long, endTime: Long, newestReading: Long?): Long {
    if (newestReading == null || newestReading < fromTime || newestReading > endTime) return fromTime
    return newestReading - (newestReading - fromTime) / GRAPH_STEP_MS * GRAPH_STEP_MS
}
