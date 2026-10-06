package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.ShowerMode
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val showerMode: ShowerMode,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil
) : ViewModel() {

    // =========================================================================
    // Shower mode (see ShowerMode). Lives here because every overview layout already gets this
    // view model, and the Treatments sheet reaches it through the main screen.
    // =========================================================================

    /** Shower mode for its overview chip, or null when it is not running. */
    val showerState: StateFlow<ShowerChipState?> = flow {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(15_000L)
            }
        }
        emitAll(preferences.observe(LongNonKey.ShowerModeEndsAt).combine(ticker) { endsAt, _ ->
            val now = dateUtil.now()
            val left = endsAt - now
            if (left <= 0) return@combine null
            val startedAt = preferences.get(LongNonKey.ShowerModeStartedAt)
            ShowerChipState(
                capText = profileUtil.fromMgdlToStringInUnits(preferences.get(DoubleNonKey.ShowerModeCapMgdl)),
                remainingText = dateUtil.untilString(endsAt, rh),
                minutesLeft = ((left + 59_999) / 60_000).toInt(),
                progress = if (startedAt in 1 until endsAt) ((now - startedAt).toFloat() / (endsAt - startedAt)).coerceIn(0f, 1f) else 0f
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The length offered when starting shower mode (Safety settings). */
    val defaultShowerMinutes: Int get() = preferences.get(IntKey.SafetyShowerModeMinutes)

    /** Starts shower mode. [onResult] gets false when there is no recent BG to cap at. */
    fun startShower(minutes: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(showerMode.start(minutes)) }
    }

    fun endShower() = showerMode.stop()

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
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " ${constraintsProcessed.carbsReq}${rh.gs(CoreUiStrings.required)}"
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
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

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
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
            val message =
                rh.gs(CoreUiStrings.bolus_iob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob) + "\n" +
                    rh.gs(CoreUiStrings.treatments_wizard_basaliob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, basalIob.basaliob) + "\n" +
                    rh.gs(CoreUiStrings.iob) + ": " + rh.gs(InterfacesStrings.format_insulin_units, total)
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}
