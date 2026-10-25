package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.overview.graph.TbrDisplayData
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A loop run older than this no longer tells whether SMBs are on, see `buildDosing`. */
private const val SMB_STATUS_MAX_AGE_MS = 15 * 60 * 1000L

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val sensitivityOverview: SensitivityOverview,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val rxBus: RxBus
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(150_000L) // 2.5 minutes
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        val valueText = cobText
        var carbsReqText = ""
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText = rh.gs(CoreUiStrings.value_with_unit, cobText, rh.gs(CoreUiStrings.value_with_unit, constraintsProcessed.carbsReq.toString(), rh.gs(CoreUiStrings.required)))
                    carbsReqText = rh.gs(CoreUiStrings.carbs_required_value, constraintsProcessed.carbsReq)
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0, valueText = valueText, carbsReqText = carbsReqText)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    // Predictions are published right after each loop run (see sensitivityUiState), the TBR and
    // the treatments when the database changes
    val dosingUiState: StateFlow<DosingUiState> = combine(cache.tbrFlow, cache.predictionsFlow, cache.treatmentGraphFlow) { tbr, _, _ ->
        buildDosing(tbr)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DosingUiState()
    )

    private suspend fun buildDosing(tbr: TbrDisplayData?): DosingUiState {
        val lastRun = if (config.APS) loop.lastRun else null
        val request = lastRun?.request
        // What was really asked of the pump, after the limits
        val result = lastRun?.constraintsProcessed ?: request
        val decisionTemp = when {
            result == null                                     -> ""
            !result.isTempBasalRequested                       -> rh.gs(CoreUiStrings.loop_decision_keep_temp)
            result.duration == 0                               -> rh.gs(CoreUiStrings.loop_decision_cancel_temp)
            result.usePercent                                  -> rh.gs(CoreUiStrings.loop_decision_temp, rh.gs(CoreUiStrings.format_percent, result.percent), result.duration)
            else                                               -> rh.gs(CoreUiStrings.loop_decision_temp, rh.gs(CoreUiStrings.pump_base_basal_rate, result.rate), result.duration)
        }
        val decisionSmb = when {
            result == null    -> ""
            result.smb > 0.0  -> rh.gs(InterfacesStrings.format_insulin_units, result.smb)
            else              -> rh.gs(CoreUiStrings.loop_decision_no_smb)
        }
        val lastRunAt = lastRun?.lastAPSRun ?: 0L
        val rt = request?.rawData() as? RT
        return DosingUiState(
            // Without "/h", so the widget stays narrow next to IOB and COB
            basalText = tbr?.rate?.let { rh.gs(InterfacesStrings.format_insulin_units, it) }.orEmpty(),
            smbText = result?.smb?.takeIf { it > 0.0 }?.let { rh.gs(InterfacesStrings.format_insulin_units, it) }.orEmpty(),
            // Same rule as the SMB icon in the status bar: closed loop, SMBs on in the last run, and that run is recent
            smbOn = loop.runningMode() == RM.Mode.CLOSED_LOOP && rt?.smbEnabled == true &&
                Clock.System.now().toEpochMilliseconds() - lastRunAt <= SMB_STATUS_MAX_AGE_MS,
            lastRunAt = lastRunAt,
            decisionTemp = decisionTemp,
            decisionSmb = decisionSmb,
            reason = request?.reason.orEmpty(),
            smbReason = rt?.smbReason
        )
    }

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            isfStep = data.isfStep,
            factorText = data.factorText,
            factorStep = data.factorStep,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData
        )
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message = listOf(
                CoreUiStrings.bolus_iob_label to bolusIob.iob,
                CoreUiStrings.treatments_wizard_basaliob_label to basalIob.basaliob,
                CoreUiStrings.iob to total
            ).joinToString("\n") { (label, insulin) ->
                rh.gs(InterfacesStrings.confirmation_line, rh.gs(label), rh.gs(InterfacesStrings.format_insulin_units, insulin))
            }
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}
