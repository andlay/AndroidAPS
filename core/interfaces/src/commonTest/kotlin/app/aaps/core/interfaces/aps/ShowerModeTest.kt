package app.aaps.core.interfaces.aps

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShowerModeTest {

    private val min = 60_000L

    /** Readings every 5 minutes from minute 0, values in mg/dL. */
    private fun readings(vararg values: Double): List<InMemoryGlucoseValue> =
        values.mapIndexed { i, v -> InMemoryGlucoseValue(timestamp = i * 5 * min, value = v) }

    private fun List<InMemoryGlucoseValue>.loopValues() = map { it.recalculated }

    @Test
    fun `no episodes leaves the data as it is`() {
        val data = readings(120.0, 140.0)
        assertSame(data, data.applyShowerCaps(emptyList()))
    }

    @Test
    fun `values above the cap are capped inside the episode, falls pass`() {
        val data = readings(150.0, 170.0, 140.0, 190.0)
        val episode = ShowerEpisode(start = 0, end = 20 * min, capMgdl = 150.0)
        assertEquals(listOf(150.0, 150.0, 140.0, 150.0), data.applyShowerCaps(listOf(episode)).loopValues())
    }

    @Test
    fun `raw value is kept, only the smoothed value is capped`() {
        val capped = readings(170.0).applyShowerCaps(listOf(ShowerEpisode(0, 10 * min, 150.0)))
        assertEquals(170.0, capped[0].value)
        assertEquals(150.0, capped[0].smoothed)
    }

    @Test
    fun `after the end the cap holds while the reading is still above it`() {
        // Episode ends at minute 10; readings at 10 and 15 are still warm, 20 is back below.
        val data = readings(150.0, 160.0, 175.0, 165.0, 140.0, 190.0)
        val episode = ShowerEpisode(start = 0, end = 10 * min, capMgdl = 150.0)
        assertEquals(listOf(150.0, 150.0, 150.0, 150.0, 140.0, 190.0), data.applyShowerCaps(listOf(episode)).loopValues())
    }

    @Test
    fun `the tail ends after 30 minutes even if the reading stays high`() {
        // End at minute 0: tail covers minutes 0..30, minute 35 is real again.
        val data = readings(170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0)
        val episode = ShowerEpisode(start = -5 * min, end = 0, capMgdl = 150.0)
        assertEquals(listOf(150.0, 150.0, 150.0, 150.0, 150.0, 150.0, 150.0, 170.0), data.applyShowerCaps(listOf(episode)).loopValues())
    }

    @Test
    fun `readings before the episode are not touched`() {
        val data = readings(200.0, 200.0, 200.0)
        val episode = ShowerEpisode(start = 10 * min, end = 20 * min, capMgdl = 150.0)
        val capped = data.applyShowerCaps(listOf(episode))
        assertNull(capped[0].smoothed)
        assertEquals(listOf(200.0, 200.0, 150.0), capped.loopValues())
    }

    @Test
    fun `episodes survive storing and reading back, broken entries are skipped`() {
        val episodes = listOf(ShowerEpisode(1_000, 2_000, 151.5), ShowerEpisode(3_000, 4_000, 99.0))
        assertEquals(episodes, decodeShowerEpisodes(encodeShowerEpisodes(episodes)))
        assertEquals(listOf(ShowerEpisode(1, 2, 3.0)), decodeShowerEpisodes("1,2,3.0;garbage;;4,x,5"))
        assertEquals(emptyList(), decodeShowerEpisodes(""))
    }
}
