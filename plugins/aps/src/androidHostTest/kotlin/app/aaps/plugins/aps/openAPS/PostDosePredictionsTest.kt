package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PostDosePredictionsTest {

    // 1 U acting evenly over the first hour: 1/60 U per minute
    private val flatActivity = { units: Double, minutesAgo: Double -> if (minutesAgo < 60.0) units / 60.0 else 0.0 }
    private val noTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = 0)

    @Test
    fun `an SMB is extra insulin now, no temp change adds nothing else`() {
        assertThat(PostDosePredictions.extraInsulin(0.5, noTemp, null, null, basal = 1.0)).containsExactly(0.0 to 0.5)
    }

    @Test
    fun `a new temp counts against the running temp, then against basal`() {
        // Running 2 U/h for 10 more minutes; the run sets 0 U/h for 15 minutes; basal 1 U/h
        val running = CurrentTemp(duration = 10, rate = 2.0, minutesrunning = 20)
        val extra = PostDosePredictions.extraInsulin(0.0, running, newRate = 0.0, newDuration = 15, basal = 1.0)
        assertThat(extra.map { it.first }).containsExactly(2.5, 7.5, 12.5).inOrder()
        // 0 - 2 U/h for two slots, then 0 - 1 U/h, each over 5 minutes
        assertThat(extra[0].second).isWithin(1e-9).of(-2.0 * 5 / 60)
        assertThat(extra[1].second).isWithin(1e-9).of(-2.0 * 5 / 60)
        assertThat(extra[2].second).isWithin(1e-9).of(-1.0 * 5 / 60)
    }

    @Test
    fun `the curves fall by activity times sens per step, from the step after the dose`() {
        val curves = Predictions(IOB = listOf(150, 150, 150, 150), UAM = listOf(150, 152, 154))
        val shifted = PostDosePredictions.shift(curves, listOf(0.0 to 1.0), smb = 1.0, sens = 60.0, activity = flatActivity)
        // A bolus given now has no activity yet in step 0 (as in the insulin curve), so point 1 does
        // not move; each 5 minute step after that drops 1/60 * 60 * 5 = 5 mg/dL
        assertThat(shifted.IOB).containsExactly(150, 150, 145, 140).inOrder()
        assertThat(shifted.UAM).containsExactly(150, 152, 149).inOrder()
        assertThat(shifted.COB).isNull()
    }

    @Test
    fun `the zero temp curve moves only with the SMB`() {
        val curves = Predictions(ZT = listOf(150, 150, 150))
        val tempOnly = listOf(2.5 to -0.1)
        assertThat(PostDosePredictions.shift(curves, tempOnly, smb = 0.0, sens = 60.0, activity = flatActivity).ZT).containsExactly(150, 150, 150).inOrder()
    }

    @Test
    fun `no curves or no dose gives no after dose curves`() {
        val rt = RT(runningDynamicIsf = false, predBGs = Predictions(IOB = listOf(150, 150)))
        assertThat(PostDosePredictions.afterDose(rt, noTemp, 1.0, 60.0, flatActivity)).isNull()
        val noCurves = RT(runningDynamicIsf = false, units = 0.5)
        assertThat(PostDosePredictions.afterDose(noCurves, noTemp, 1.0, 60.0, flatActivity)).isNull()
        val withSmb = RT(runningDynamicIsf = false, units = 0.5, predBGs = Predictions(IOB = listOf(150, 150, 150)))
        assertThat(PostDosePredictions.afterDose(withSmb, noTemp, 1.0, 60.0, flatActivity)?.IOB).containsExactly(150, 150, 148).inOrder()
    }
}
