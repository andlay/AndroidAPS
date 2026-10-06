package app.aaps.persistentNotification

import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The notification is updated at once, and once more after a burst of the frequent events, see `frequentUpdates`. */
class PersistentNotificationPluginTest : TestBase() {

    private lateinit var sut: PersistentNotificationPlugin

    @BeforeEach
    fun prepare() {
        sut = PersistentNotificationPlugin(
            aapsLogger, mock(), mock(), mock(), mock(), mock(), mock(), mock(), rxBus, mock(), mock(), mock(),
            mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock()
        )
    }

    /** Collects the updates in virtual time and counts them. */
    private fun TestScope.countUpdates(): () -> Int {
        var updates = 0
        val flow = sut.frequentUpdates(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { updates++ } }
        runCurrent()
        return { updates }
    }

    /**
     * An event sent right after the start, before anything reads the flow, is not lost: the sources are
     * subscribed when `frequentUpdates` is called. With `merge` they were subscribed only when read.
     */
    @Test
    fun `an event right after the start is not lost`() = runTest {
        var updates = 0
        val flow = sut.frequentUpdates(backgroundScope)
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))

        backgroundScope.launch { flow.collect { updates++ } }
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(updates).isEqualTo(1)
    }

    @Test
    fun `a burst after a BG updates at once and once more at the end`() = runTest {
        val updates = countUpdates()

        // The calculation finishes twice and the overview is refreshed, within a second
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))
        runCurrent()
        assertThat(updates()).isEqualTo(1)
        advanceTimeBy(300.milliseconds)
        rxBus.send(EventRefreshOverview("test"))
        advanceTimeBy(300.milliseconds)
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = false))
        runCurrent()
        assertThat(updates()).isEqualTo(1)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(updates()).isEqualTo(2)
    }

    /**
     * The first update must not wait: with the screen off the phone can sleep right after the BG is
     * handled, and a delayed update then waited for the next wake-up, showing the previous BG.
     */
    @Test
    fun `one event updates at once and only once`() = runTest {
        val updates = countUpdates()

        rxBus.send(EventRefreshOverview("test"))
        runCurrent()
        assertThat(updates()).isEqualTo(1)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertThat(updates()).isEqualTo(1)

        // The next BG, minutes later, is a new update, again at once
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))
        runCurrent()
        assertThat(updates()).isEqualTo(2)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertThat(updates()).isEqualTo(2)
    }
}
