package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SmbSafetyTest {

    @Test
    fun `both settings off gives no minimum`() {
        assertEquals(0.0, smbMinBgMgdl(minBgMgdl = 0.0, minPercentOfTarget = 0, targetMgdl = 100.0))
    }

    @Test
    fun `fixed minimum is used alone`() {
        assertEquals(126.0, smbMinBgMgdl(minBgMgdl = 126.0, minPercentOfTarget = 0, targetMgdl = 100.0))
    }

    @Test
    fun `percent of target is used alone`() {
        assertEquals(120.0, smbMinBgMgdl(minBgMgdl = 0.0, minPercentOfTarget = 120, targetMgdl = 100.0))
    }

    @Test
    fun `higher of the two wins`() {
        assertEquals(150.0, smbMinBgMgdl(minBgMgdl = 108.0, minPercentOfTarget = 150, targetMgdl = 100.0))
        assertEquals(180.0, smbMinBgMgdl(minBgMgdl = 180.0, minPercentOfTarget = 150, targetMgdl = 100.0))
    }

    @Test
    fun `percent follows a temp target`() {
        assertEquals(168.0, smbMinBgMgdl(minBgMgdl = 0.0, minPercentOfTarget = 120, targetMgdl = 140.0))
    }

    @Test
    fun `below the cap nothing changes`() {
        val status = GlucoseStatusSMB(glucose = 140.0, delta = -3.0, shortAvgDelta = -2.0, longAvgDelta = 1.0, date = 5L)
        assertSame(status, status.cappedAt(150.0))
    }

    @Test
    fun `above the cap BG is capped and a rise counts as flat`() {
        val status = GlucoseStatusSMB(glucose = 170.0, noise = 1.0, delta = 6.0, shortAvgDelta = 4.0, longAvgDelta = -1.0, date = 5L)
        assertEquals(
            GlucoseStatusSMB(glucose = 150.0, noise = 1.0, delta = 0.0, shortAvgDelta = 0.0, longAvgDelta = -1.0, date = 5L),
            status.cappedAt(150.0)
        )
    }

    @Test
    fun `autoIsf parabola rise counts as flat above the cap`() {
        val status = GlucoseStatusAutoIsf(glucose = 170.0, delta = 6.0, shortAvgDelta = 4.0, longAvgDelta = 2.0, deltaPl = 5.0, deltaPn = 7.0, bgAcceleration = 1.5)
        val capped = status.cappedAt(150.0) as GlucoseStatusAutoIsf
        assertEquals(150.0, capped.glucose)
        assertEquals(0.0, capped.delta)
        assertEquals(0.0, capped.deltaPl)
        assertEquals(0.0, capped.deltaPn)
        assertEquals(0.0, capped.bgAcceleration)
    }
}
