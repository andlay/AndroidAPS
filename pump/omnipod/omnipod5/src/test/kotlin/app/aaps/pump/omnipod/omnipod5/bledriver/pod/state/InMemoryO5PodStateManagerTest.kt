package app.aaps.pump.omnipod.omnipod5.bledriver.pod.state

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoActivationTimeResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoTriggeredAlertsResponse
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [O5PodStateManager.updateFromActivationTimeResponse]/[O5PodStateManager
 * .updateFromTriggeredAlertsResponse] - status pages 5/1's update methods, fetched
 * on-demand by [app.aaps.pump.omnipod.omnipod5.O5PumpPlugin] (see its
 * `fetchActivationTimeIfNeeded`/`fetchTriggeredAlertsIfNeeded`).
 */
class InMemoryO5PodStateManagerTest {

    @Test
    fun `above 50 reservoir response does not replace a measured value`() {
        val measured = DefaultStatusResponse(hexToBytes("1D1800A02800000463E8"))
        val above50 = DefaultStatusResponse(hexToBytes("1D1800A02800000463FF"))
        val state = InMemoryO5PodStateManager()

        state.updateFromDefaultStatusResponse(above50)
        assertThat(state.reservoirPulsesRemaining).isNull()

        state.updateFromDefaultStatusResponse(measured)
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())

        state.updateFromDefaultStatusResponse(above50)
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())

        state.updateFromAlarmStatusResponse(AlarmStatusResponse(hexToBytes("021602080100000501BD00000003FF01950000000000670A")))
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())
    }

    @Test
    fun `alarm status keeps the PDM-style Ref code until reset`() {
        // Same message as AlarmStatusResponseTest in the Dash module, with a flash failure fault.
        val encoded = hexToBytes("021602080100000501BD00000003FF01950000000000670A")
        encoded[10] = AlarmType.ALARM_FLASH_FAILURE.value
        encoded[11] = 0
        encoded[12] = 180.toByte()
        encoded[15] = (900 shr 8).toByte()
        encoded[16] = 900.toByte()
        val state = InMemoryO5PodStateManager()

        state.updateFromAlarmStatusResponse(AlarmStatusResponse(encoded))
        assertThat(state.pdmRef).isEqualTo("19-00003-02251-062")

        state.reset()
        assertThat(state.pdmRef).isNull()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `updateFromActivationTimeResponse sets podActivatedAt and reuses alarmType-alarmTime`() {
        val bytes = byteArrayOf(
            0x02, 0x11, 0x05,
            0x14,
            0x00, 0x7D,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07,
            0x0F,
            0x1A,
            0x09,
            0x1E
        )
        val state = InMemoryO5PodStateManager()

        state.updateFromActivationTimeResponse(PodInfoActivationTimeResponse(bytes))

        assertThat(state.alarmType).isEqualTo(AlarmType.ALARM_OCCLUDED)
        assertThat(state.alarmTime).isEqualTo(125.toShort())
        val activatedAt = requireNotNull(state.podActivatedAt)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = activatedAt
        assertThat(calendar[Calendar.YEAR]).isEqualTo(2026)
        assertThat(calendar[Calendar.MONTH]).isEqualTo(Calendar.JULY)
        assertThat(calendar[Calendar.DAY_OF_MONTH]).isEqualTo(15)
        assertThat(calendar[Calendar.HOUR_OF_DAY]).isEqualTo(9)
        assertThat(calendar[Calendar.MINUTE]).isEqualTo(30)
    }

    @Test
    fun `updateFromTriggeredAlertsResponse keeps only non-zero slots`() {
        val bytes = byteArrayOf(
            0x02, 0x13, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x0A,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x78,
            0x00, 0x00,
            0x00, 0x00,
            0x01, 0x2C
        )
        val state = InMemoryO5PodStateManager()

        state.updateFromTriggeredAlertsResponse(PodInfoTriggeredAlertsResponse(bytes))

        val triggered = requireNotNull(state.triggeredAlertTimes)
        assertThat(triggered).containsExactly(
            AlertType.MULTI_COMMAND, 10.toShort(),
            AlertType.LOW_RESERVOIR, 120.toShort(),
            AlertType.EXPIRATION, 300.toShort()
        )
    }

    @Test
    fun `sameTimeZone is true while the pod time zone is unknown`() {
        val state = InMemoryO5PodStateManager()

        assertThat(state.timeZoneOffset).isNull()
        assertThat(state.sameTimeZone).isTrue()
    }

    @Test
    fun `sameTimeZone follows the phone time zone after the pod time zone is stored`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"))
            val state = InMemoryO5PodStateManager()
            state.updateTimeZone()
            assertThat(state.sameTimeZone).isTrue()

            TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
            assertThat(state.sameTimeZone).isFalse()

            state.reset()
            assertThat(state.timeZoneOffset).isNull()
            assertThat(state.sameTimeZone).isTrue()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
