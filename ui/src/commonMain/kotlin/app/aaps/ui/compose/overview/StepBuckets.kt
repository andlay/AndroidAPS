package app.aaps.ui.compose.overview

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Length of one step bucket, the same as the 5 minute grid of the other graphs. */
internal const val STEP_BUCKET_MS = 5 * 60_000L

/**
 * Start of the 5 minutes a watch step record counts.
 *
 * The AAPS watch app (`StepCountListener`) keeps steps per 5 minute block, numbered
 * `round(time / 5 min)`, so a block runs from 2.5 minutes before its number to 2.5 minutes after.
 * Every 40 seconds it sends the count of the last finished block, the one before the current one.
 */
internal fun watchStepBlockStart(sentAt: Long): Long {
    val current = (sentAt / STEP_BUCKET_MS.toDouble()).roundToLong()
    return (current - 1) * STEP_BUCKET_MS - STEP_BUCKET_MS / 2
}

/**
 * Steps per 5 minutes on the grid of the other graphs.
 *
 * The watch sends the same block many times (6 records every 40 seconds), so each block is counted
 * once, from its newest record. The watch blocks start 2.5 minutes off the grid, so each block is
 * shared between the two grid buckets it covers, by how much of it falls in each (steps are taken
 * as even within the block). Each point is the start of its bucket and covers the next 5 minutes,
 * like a DEV bar.
 *
 * @param records (time sent, steps in the block) pairs
 * @param anchor any time on the grid, e.g. a bucketed BG reading. The grid is every 5 minutes from it.
 */
internal fun stepsOnGrid(records: List<Pair<Long, Int>>, anchor: Long): List<GraphDataPoint> {
    val perBlock = HashMap<Long, Pair<Long, Int>>()
    for ((sentAt, steps) in records) {
        val block = watchStepBlockStart(sentAt)
        val known = perBlock[block]
        if (known == null || sentAt >= known.first) perBlock[block] = sentAt to steps
    }
    val grid = HashMap<Long, Double>()
    for ((blockStart, newest) in perBlock) {
        val blockEnd = blockStart + STEP_BUCKET_MS
        var bucket = blockStart - ((blockStart - anchor) % STEP_BUCKET_MS + STEP_BUCKET_MS) % STEP_BUCKET_MS
        while (bucket < blockEnd) {
            val overlap = min(blockEnd, bucket + STEP_BUCKET_MS) - max(blockStart, bucket)
            if (overlap > 0) grid[bucket] = (grid[bucket] ?: 0.0) + newest.second * overlap / STEP_BUCKET_MS.toDouble()
            bucket += STEP_BUCKET_MS
        }
    }
    return grid.entries.sortedBy { it.key }.map { GraphDataPoint(it.key, it.value) }
}
