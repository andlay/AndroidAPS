package app.aaps.ui.compose.overview

import kotlin.test.Test
import kotlin.test.assertEquals

class ShadowPlanTest {

    private val min = 60_000L
    private val profile = { _: Long -> 1.0 }
    private val real = { _: Long -> 2.0 }

    private fun rateAt(plan: ShadowPlan, t: Long) = plan.basal.last { it.first <= t }.second

    @Test
    fun aRequestedTempRunsForItsDurationThenProfileBasal() {
        val plan = shadowPlan(listOf(ShadowDecision(0, true, 0.0, 30, 0.0)), 0, 40 * min, profile, real, emptyList(), gapMs = 60 * min)
        assertEquals(0.0, rateAt(plan, 10 * min))
        assertEquals(1.0, rateAt(plan, 35 * min))
    }

    @Test
    fun noTempRequestKeepsTheRealTemp() {
        val plan = shadowPlan(listOf(ShadowDecision(0, false, -1.0, -1, 0.0)), 0, 10 * min, profile, real, emptyList())
        assertEquals(2.0, rateAt(plan, 5 * min))
    }

    @Test
    fun cancelTempIsProfileBasal() {
        val plan = shadowPlan(listOf(ShadowDecision(0, true, 0.0, 0, 0.0)), 0, 10 * min, profile, real, emptyList())
        assertEquals(1.0, rateAt(plan, 5 * min))
    }

    @Test
    fun theNextDecisionTakesOver() {
        val decisions = listOf(ShadowDecision(0, true, 0.0, 30, 0.0), ShadowDecision(5 * min, true, 3.0, 30, 0.0))
        val plan = shadowPlan(decisions, 0, 20 * min, profile, real, emptyList())
        assertEquals(0.0, rateAt(plan, 2 * min))
        assertEquals(3.0, rateAt(plan, 10 * min))
    }

    @Test
    fun shadowSmbsAreGivenAndRealSmbsOnlyInGaps() {
        val decisions = listOf(ShadowDecision(0, false, -1.0, -1, 0.5), ShadowDecision(60 * min, false, -1.0, -1, 0.0))
        val realSmbs = listOf(2 * min to 0.3, 30 * min to 0.4)
        val plan = shadowPlan(decisions, 0, 70 * min, profile, real, realSmbs)
        // the real SMB 2 min after a decision is replaced by the shadow one; the one at 30 min is in a gap
        assertEquals(listOf(0L to 0.5, 30 * min to 0.4), plan.smbs)
        // in the gap the real basal is used
        assertEquals(2.0, rateAt(plan, 30 * min))
    }

    @Test
    fun netPartsAreTheBasalAgainstTheProfile() {
        val plan = shadowPlan(listOf(ShadowDecision(0, true, 0.0, 30, 0.0)), 0, 10 * min, profile, real, emptyList())
        // two 5 min parts of -1 U/h
        assertEquals(listOf(150_000L to -5.0 / 60, 450_000L to -5.0 / 60), plan.netParts)
    }
}
