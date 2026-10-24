package app.aaps.plugins.aps.mpc

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The Kotlin meal SMB controller must decide exactly like the closed-loop replay it was ported from.
 * The fixture holds 12 hours of replay steps (meals, 30 SMBs, steps below the SMB minimum BG, profile and
 * temp targets): the inputs each step saw, vanilla's temp, and the SMB the replay chose. The state (impact
 * being fitted, kept tails, when a meal was last seen) builds up inside the controller from the inputs, so a
 * mistake in that part shows here too.
 */
class MpcControllerGoldenTest {

    private fun JSONArray.toDoubles() = DoubleArray(length()) { if (isNull(it)) Double.NaN else getDouble(it) }

    /** The meal shape the replay ran with: the fitted one, 20 % higher and 20 % longer */
    private val shape = MpcModel.Shape(
        a = 1.80333984375, b = 2.997802734375,
        height = 15.09630338988304, heightSd = 0.3692596298487712,
        length = 22.62903770626598, lengthSd = 0.45539491571666757
    )

    @Test
    fun decisionsMatchTheReplay() {
        val steps = JSONObject(File("src/androidHostTest/fixtures/mpc/replay_steps.json").readText()).getJSONArray("steps")
        assertThat(steps.length()).isEqualTo(144)
        val controller = MpcController()
        val mismatches = mutableListOf<String>()
        for (k in 0 until steps.length()) {
            val s = steps.getJSONObject(k)
            val step = s.getLong("i")
            val basal = s.getDouble("basal")
            val settings = MpcController.MealSmbSettings(
                target = s.getDouble("target"),
                maxSmb = Math.round(basal * 120 / 60 * 20) / 20.0,
                maxIob = 5.0,
                shape = shape
            )
            val d = controller.mealSmb(
                step = step,
                view = s.getJSONArray("v").toDoubles(),
                pastActivity = s.getJSONArray("pastAct").toDoubles(),
                futureActivity = s.getJSONArray("futAct").toDoubles(),
                iob = s.getDouble("iob"),
                basal = basal,
                tempRate = s.getDouble("vRate"),
                isf = 46.8,
                smbAllowed = s.getBoolean("smbOk"),
                settings = settings
            )
            val mealSum = d.futures.meal.take(MpcController.HORIZON).sum()
            val nonSum = d.futures.non.take(MpcController.HORIZON).sum()
            if (abs(d.mealChance - s.getDouble("P")) > 1e-9) mismatches += "step $step meal chance ${d.mealChance} != ${s.getDouble("P")}"
            if (abs(mealSum - s.getDouble("mealSum")) > 1e-6) mismatches += "step $step meal future $mealSum != ${s.getDouble("mealSum")}"
            if (abs(nonSum - s.getDouble("nonSum")) > 1e-6) mismatches += "step $step false start future $nonSum != ${s.getDouble("nonSum")}"
            if (abs(d.units - s.getDouble("units")) > 1e-6) mismatches += "step $step SMB ${d.units} != ${s.getDouble("units")}"
        }
        assertThat(mismatches).isEmpty()
    }
}
