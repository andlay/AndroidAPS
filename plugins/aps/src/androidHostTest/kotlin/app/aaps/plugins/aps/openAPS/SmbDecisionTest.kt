package app.aaps.plugins.aps.openAPS

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbDecisionTest {

    @Test
    fun `the last SMB line of all logs wins`() {
        val autoIsfLog = listOf("start AutoISF", "SMB disabled by Full Loop logic: iob 9.3 is above effective iobTH 7.5", "Loop power level temporarily capped")
        val console = listOf("SMB enabled due to enableSMB_always", "minGuardBG 3.0 < 3.6")
        assertThat(smbDecisionLine(autoIsfLog, console)).isEqualTo("SMB enabled due to enableSMB_always")
        assertThat(smbDecisionLine(autoIsfLog)).isEqualTo("SMB disabled by Full Loop logic: iob 9.3 is above effective iobTH 7.5")
    }

    @Test
    fun `no SMB line gives null`() {
        assertThat(smbDecisionLine(listOf("maxDelta 1.2 > 20% of BG 6.0: SMB disabled; "), emptyList())).isNull()
        assertThat(smbDecisionLine()).isNull()
    }
}
