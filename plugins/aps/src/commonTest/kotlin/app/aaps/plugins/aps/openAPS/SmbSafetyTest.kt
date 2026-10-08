package app.aaps.plugins.aps.openAPS

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
