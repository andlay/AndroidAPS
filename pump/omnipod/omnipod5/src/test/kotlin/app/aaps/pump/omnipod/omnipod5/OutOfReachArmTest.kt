package app.aaps.pump.omnipod.omnipod5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OutOfReachArmTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    @Test
    fun `stale beep off keeps the out-of-reach time`() {
        assertThat(outOfReachArmMinutes(now, 10, 0, now - 2 * minute, 0L)).isEqualTo(OutOfReachArm(10, false))
    }

    @Test
    fun `the alert goes off when the newest BG gets stale, if that is sooner`() {
        // BG 15 min old, stale after 20: 5 minutes left
        assertThat(outOfReachArmMinutes(now, 10, 20, now - 15 * minute, 0L)).isEqualTo(OutOfReachArm(5, true))
    }

    @Test
    fun `the time is rounded up, so the pod never beeps before the BG is stale`() {
        assertThat(outOfReachArmMinutes(now, 10, 20, now - 15 * minute - 30_000L, 0L)).isEqualTo(OutOfReachArm(5, true))
    }

    @Test
    fun `a fresh BG leaves the out-of-reach time`() {
        assertThat(outOfReachArmMinutes(now, 10, 20, now - 2 * minute, 0L)).isEqualTo(OutOfReachArm(10, false))
    }

    @Test
    fun `a BG that is stale already beeps in one minute`() {
        assertThat(outOfReachArmMinutes(now, 10, 20, now - 40 * minute, 0L)).isEqualTo(OutOfReachArm(1, true))
    }

    @Test
    fun `after the beep for a BG it does not beep again until a new BG comes`() {
        val bg = now - 40 * minute
        assertThat(outOfReachArmMinutes(now, 10, 20, bg, beepedForBg = bg)).isEqualTo(OutOfReachArm(10, false))
    }

    @Test
    fun `no BG at all does not beep`() {
        assertThat(outOfReachArmMinutes(now, 10, 20, 0L, 0L)).isEqualTo(OutOfReachArm(10, false))
    }
}
