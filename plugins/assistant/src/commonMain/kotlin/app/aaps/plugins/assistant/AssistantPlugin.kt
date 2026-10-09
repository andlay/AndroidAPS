package app.aaps.plugins.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.ai.AiAssistant
import app.aaps.core.interfaces.ai.AiTopic
import app.aaps.core.interfaces.ai.AiTurn
import app.aaps.core.interfaces.concurrent.aapsBackgroundDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * AI explanations of loop decisions. Reads data only; answers are text for the user and nothing else
 * reads them. Your data goes to the configured AI provider each time you ask, never on its own.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(870)
@SingleIn(AppScope::class)
@Inject
class AssistantPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    notificationManager: NotificationManager,
    private val contextBuilder: AiContextBuilder,
    private val dateUtil: DateUtil
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(Icons.Filled.AutoFixHigh)
        .pluginName(AssistantStrings.assistant_name)
        .description(AssistantStrings.assistant_description),
    ownPreferences = AssistantStringKey.entries,
    aapsLogger, rh, preferences, notificationManager
), AiAssistant {

    // Made when first used, not at app start
    private val client by lazy { OpenAiChatClient() }

    /**
     * All AI work runs here: building the data, the request and reading the answer. One task at a
     * time, on a low priority thread. Never on the main thread (the sheet starts it from the UI), and
     * never on the default pool, where the calculation and the loop run, so the AI cannot take a
     * thread or CPU time from them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val aiDispatcher: CoroutineDispatcher = aapsBackgroundDispatcher.limitedParallelism(1)

    override fun isConfigured(): Boolean = preferences.get(AssistantStringKey.ApiKey).isNotBlank()

    override suspend fun contextFor(topic: AiTopic, timestamp: Long): String =
        withContext(aiDispatcher) { contextBuilder.build(topic, timestamp) }

    override fun ask(topic: AiTopic, timestamp: Long, conversation: List<AiTurn>): Flow<String> = flow {
        val apiKey = preferences.get(AssistantStringKey.ApiKey)
        if (apiKey.isBlank()) throw AiException("No API key set (AI assistant settings)")
        val extra = preferences.get(AssistantStringKey.ExtraInstructions).trim()
        val messages = buildList {
            // Fixed text first, data next, conversation last: the stable part stays a cacheable prefix
            add(ChatMessage("system", AlgorithmGuide.INSTRUCTIONS + if (extra.isNotEmpty()) "\nExtra instructions from the user:\n$extra" else ""))
            add(ChatMessage("system", AlgorithmGuide.GUIDE))
            add(ChatMessage("user", "DATA (JSON):\n" + contextBuilder.build(topic, timestamp)))
            // An explanation opened from a graph starts with the model's answer: put the question it answered back in front
            if (conversation.firstOrNull()?.fromUser != true) add(ChatMessage("user", firstQuestion(topic, timestamp)))
            conversation.forEach { add(ChatMessage(if (it.fromUser) "user" else "assistant", it.text)) }
        }
        val baseUrl = preferences.get(AssistantStringKey.BaseUrl)
        val model = preferences.get(AssistantStringKey.Model)
        val temperature = temperatureFor(model)
        try {
            client.stream(baseUrl, apiKey, model, temperature, messages).collect { emit(it) }
        } catch (e: TemperatureNotSupportedException) {
            // Remembered, so the next questions to this model do not try again
            temperatureRefusedBy = model
            emit(rh.gs(AssistantStrings.ai_temperature_not_supported) + "\n\n")
            client.stream(baseUrl, apiKey, model, null, messages).collect { emit(it) }
        }
    }.flowOn(aiDispatcher)

    /** The model that refused the temperature in this app session, if any. */
    @Volatile private var temperatureRefusedBy: String? = null

    /** The temperature setting (0 to 2), or null for the model's default or when this model refused it. */
    private fun temperatureFor(model: String): Double? {
        if (model == temperatureRefusedBy) return null
        // A number typed with a decimal comma is still a number
        return preferences.get(AssistantStringKey.Temperature).trim().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 2.0)
    }

    private fun firstQuestion(topic: AiTopic, t: Long): String {
        val at = dateUtil.timeString(t)
        val minutesAhead = (t - dateUtil.now()) / 60_000
        // A tap on the future part of a graph asks about a forecast, not about a loop decision
        if (minutesAhead > 0) return when (topic) {
            AiTopic.BG_PREDICTIONS -> "Explain the predicted BG at $at ($minutesAhead min from now): why each prediction line (IOB, COB, UAM, ZT) is where it is at that time."
            AiTopic.INSULIN        -> "Explain the projected insulin at $at ($minutesAhead min from now): what insulin is still acting then and what the newest loop run expects."
            AiTopic.COB            -> "Explain the expected COB at $at ($minutesAhead min from now): how the carbs are expected to be absorbed by then."
            AiTopic.DEVIATIONS     -> "Explain the projected BGI at $at ($minutesAhead min from now): how much the insulin still acting is expected to lower BG then."
            AiTopic.SENSITIVITY    -> "Explain the sensitivity the newest loop run uses for its predictions up to $at ($minutesAhead min from now)."
            AiTopic.GENERAL        -> "Summarise what the newest loop run expects up to $at ($minutesAhead min from now) and why."
        }
        return when (topic) {
            AiTopic.BG_PREDICTIONS -> "Explain the BG graph at $at: why the prediction lines (IOB, COB, UAM, ZT) and eventual BG are what they are."
            AiTopic.INSULIN        -> "Explain the dosing decision at $at: why the loop gave this SMB or temp basal (or zero temp, or nothing)."
            AiTopic.COB            -> "Explain COB at $at: how the carbs are being absorbed, and whether minimum absorption was used and why."
            AiTopic.DEVIATIONS     -> "Explain the deviation and BGI at $at: what they mean here, and whether this counted as UAM and why."
            AiTopic.SENSITIVITY    -> "Explain the sensitivity at $at: why the ratio or ISF is what it is."
            AiTopic.GENERAL        -> "Summarise what the loop is doing at $at and why."
        }
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "ai_assistant_settings",
        title = AssistantStrings.assistant_name,
        items = listOf(
            AssistantStringKey.ApiKey,
            AssistantStringKey.Model,
            AssistantStringKey.Temperature,
            AssistantStringKey.BaseUrl,
            AssistantStringKey.ExtraInstructions
        ),
        icon = pluginDescription.icon
    )
}
