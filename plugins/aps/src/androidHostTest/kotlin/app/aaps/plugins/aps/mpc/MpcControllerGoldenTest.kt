package app.aaps.plugins.aps.mpc

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The Kotlin controller must decide exactly like the closed-loop replay it was ported from.
 * The fixture holds 8 hours of replay steps (with meals, 55 SMBs, zero temps and a high temp): the
 * inputs each step saw and what the replay decided. The state (impact being fitted, kept tails) builds
 * up inside the controller from the inputs, so a mistake in that part shows here too.
 */
class MpcControllerGoldenTest {

    private fun JSONArray.toDoubles() = DoubleArray(length()) { if (isNull(it)) Double.NaN else getDouble(it) }

    @Test
    fun decisionsMatchTheReplay() {
        val steps = JSONObject(File("src/androidHostTest/res/mpc/replay_steps.json").readText()).getJSONArray("steps")
        assertThat(steps.length()).isEqualTo(96)
        val controller = MpcController()
        val settings = MpcController.Settings()
        val mismatches = mutableListOf<String>()
        for (k in 0 until steps.length()) {
            val s = steps.getJSONObject(k)
            val view = s.getJSONArray("v").toDoubles()
            val target = s.getDouble("target")
            val d = controller.step(
                step = s.getLong("i"),
                view = view,
                pastActivity = s.getJSONArray("pastAct").toDoubles(),
                futureActivity = s.getJSONArray("futAct").toDoubles(),
                iob = s.getDouble("iob"),
                basal = s.getDouble("basal"),
                target = target,
                isfProfile = 46.8,
                settings = settings
            )
            val isf = 46.8 / MpcModel.aisfFactor(MpcModel.aisfStatus(view), target)
            val mealSum = d.futures.meal.take(MpcController.HORIZON).sum()
            val nonSum = d.futures.non.take(MpcController.HORIZON).sum()
            val step = s.getLong("i")
            if (abs(isf - s.getDouble("isf")) > 1e-9) mismatches += "step $step isf ${isf} != ${s.getDouble("isf")}"
            if (abs(d.mealChance - s.getDouble("P")) > 1e-9) mismatches += "step $step meal chance ${d.mealChance} != ${s.getDouble("P")}"
            if (abs(mealSum - s.getDouble("mealSum")) > 1e-6) mismatches += "step $step meal future $mealSum != ${s.getDouble("mealSum")}"
            if (abs(nonSum - s.getDouble("nonSum")) > 1e-6) mismatches += "step $step false start future $nonSum != ${s.getDouble("nonSum")}"
            if (abs(d.units - s.getDouble("units")) > 1e-6) mismatches += "step $step SMB ${d.units} != ${s.getDouble("units")}"
            if (abs(d.rate - s.getDouble("rate")) > 1e-6) mismatches += "step $step rate ${d.rate} != ${s.getDouble("rate")}"
        }
        assertThat(mismatches).isEmpty()
    }
}
