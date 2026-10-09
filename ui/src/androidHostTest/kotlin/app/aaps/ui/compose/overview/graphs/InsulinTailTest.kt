package app.aaps.ui.compose.overview.graphs

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BgiGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

internal class InsulinTailTest {

    private val now = 1_700_000_000_000L
    private val step = 5 * 60_000L

    private fun point(t: Long, v: Double = 0.1) = GraphDataPoint(t, v)

    @Test
    fun `tail is added only to the data it was made for`() {
        val data = ActivityGraphData(activity = listOf(point(now)), activityPrediction = listOf(point(now), point(now + step)), maxActivity = 0.1)
        val tail = InsulinTail(from = now + step, activity = listOf(point(now + 2 * step, 0.3)), bgi = emptyList(), maxActivity = 0.3)

        val merged = data.withTail(tail)
        assertThat(merged.activityPrediction.map { it.timestamp }).containsExactly(now, now + step, now + 2 * step).inOrder()
        assertThat(merged.maxActivity).isEqualTo(0.3)

        // A tail made for data that ended somewhere else (an older calculation) is not drawn
        val stale = InsulinTail(from = now, activity = listOf(point(now + step)), bgi = emptyList(), maxActivity = 0.3)
        assertThat(data.withTail(stale)).isSameInstanceAs(data)
        assertThat(data.withTail(null)).isSameInstanceAs(data)
    }

    @Test
    fun `tail starts at the last past point when there is no projected line yet`() {
        val data = ActivityGraphData(activity = listOf(point(now - step), point(now)), activityPrediction = emptyList())
        val tail = InsulinTail(from = now, activity = listOf(point(now + step)), bgi = emptyList(), maxActivity = 0.1)

        assertThat(data.withTail(tail).activityPrediction.map { it.timestamp }).containsExactly(now, now + step).inOrder()
    }

    @Test
    fun `bgi tail is added when it was made for the same data`() {
        val data = BgiGraphData(bgi = listOf(point(now)), bgiPrediction = listOf(point(now + step)))
        val tail = InsulinTail(from = now + step, activity = listOf(point(now + 2 * step)), bgi = listOf(point(now + 2 * step)), maxActivity = 0.1)

        assertThat(data.withTail(tail).bgiPrediction.map { it.timestamp }).containsExactly(now + step, now + 2 * step).inOrder()
        assertThat(data.withTail(InsulinTail(from = now, activity = listOf(point(now + step)), bgi = listOf(point(now + step)), maxActivity = 0.1))).isSameInstanceAs(data)
    }

    @Test
    fun `calculator projects activity and bgi on to now plus DIA`() = runTest {
        val dia = 60 * 60_000L
        val profile = mock<EffectiveProfile> { on { iCfg } doReturn ICfg(insulinLabel = "test", insulinEndTime = dia, insulinPeakTime = 75 * 60_000L) }
        val profileFunction = mock<ProfileFunction>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profileFunction.getProfile(any())).thenReturn(profile)
        val iobCobCalculator = mock<IobCobCalculator>()
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), anyOrNull())).thenReturn(IobTotal(time = 0, activity = 0.01))
        val profileUtil = mock<ProfileUtil>()
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), eq(GlucoseUnit.MGDL))).thenAnswer { it.getArgument<Double>(0) }
        val dateUtil = mock<DateUtil> { on { now() } doReturn now }
        val sut = InsulinTailCalculator(iobCobCalculator, profileFunction, profileUtil, dateUtil)

        val activity = ActivityGraphData(activity = listOf(point(now)), activityPrediction = listOf(point(now), point(now + step)))
        val bgi = BgiGraphData(bgi = listOf(point(now)), bgiPrediction = listOf(point(now + step)), lastIsfMgdl = 50.0)
        val tail = sut.calculate(activity, bgi)!!

        assertThat(tail.from).isEqualTo(now + step)
        assertThat(tail.activity.first().timestamp).isEqualTo(now + 2 * step)
        assertThat(tail.end).isEqualTo(now + dia)
        // Activity per 5 minutes, BGI = activity per minute * ISF * 5
        assertThat(tail.activity.first().value).isWithin(1e-9).of(0.05)
        assertThat(tail.bgi.first().value).isWithin(1e-9).of(2.5)
    }

    @Test
    fun `calculator makes no tail for a past day`() = runTest {
        val iobCobCalculator = mock<IobCobCalculator>()
        val dateUtil = mock<DateUtil> { on { now() } doReturn now }
        val sut = InsulinTailCalculator(iobCobCalculator, mock(), mock(), dateUtil)

        val pastDay = ActivityGraphData(activity = listOf(point(now - 24 * 60 * 60_000L)), activityPrediction = emptyList())
        assertThat(sut.calculate(pastDay, BgiGraphData(emptyList(), emptyList()))).isNull()
        verifyNoInteractions(iobCobCalculator)
    }
}
