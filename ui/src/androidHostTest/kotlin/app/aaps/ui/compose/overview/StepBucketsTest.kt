package app.aaps.ui.compose.overview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StepBucketsTest {

    private val min = 60_000L

    @Test
    fun `a record counts the watch block before the current one`() {
        // Sent at 50 min 10 s: the current block is number 10 (47.5 to 52.5 min), the finished one 42.5 to 47.5
        assertThat(watchStepBlockStart(50 * min + 10_000)).isEqualTo(42 * min + 30_000)
    }

    @Test
    fun `each watch block is counted once and split over the two grid buckets it covers`() {
        val records = listOf(
            // The same block sent again and again, the newest count wins
            50 * min + 10_000 to 90,
            50 * min + 50_000 to 100,
            51 * min + 30_000 to 100,
            // The next block
            55 * min + 10_000 to 40
        )

        val points = stepsOnGrid(records, anchor = 0L)

        // Grid on the clock: 40-45 gets half of block 42.5-47.5, 45-50 the other half plus half of 47.5-52.5
        assertThat(points.map { it.timestamp }).containsExactly(40 * min, 45 * min, 50 * min).inOrder()
        assertThat(points.map { it.value }).containsExactly(50.0, 70.0, 20.0).inOrder()
        assertThat(points.sumOf { it.value }).isEqualTo(140.0)
    }

    @Test
    fun `the grid follows the anchor`() {
        // A grid that starts 2.5 minutes off the clock matches the watch blocks exactly
        val points = stepsOnGrid(listOf(50 * min + 10_000 to 100), anchor = 2 * min + 30_000)

        assertThat(points).hasSize(1)
        assertThat(points.single().timestamp).isEqualTo(42 * min + 30_000)
        assertThat(points.single().value).isEqualTo(100.0)
    }
}
