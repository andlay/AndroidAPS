package app.aaps.plugins.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.ai.AiAssistant
import app.aaps.core.interfaces.ai.AiTopic
import app.aaps.core.interfaces.ai.AiTurn
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
        .shortName(AssistantStrings.assistant_short)
        .description(AssistantStrings.assistant_description),
    ownPreferences = AssistantStringKey.entries,
    aapsLogger, rh, preferences, notificationManager
), AiAssistant {

    private val client = OpenAiChatClient()

    override fun isConfigured(): Boolean = preferences.get(AssistantStringKey.ApiKey).isNotBlank()

    override suspend fun contextFor(topic: AiTopic, timestamp: Long): String = contextBuilder.build(topic, timestamp)

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
        client.stream(preferences.get(AssistantStringKey.BaseUrl), apiKey, preferences.get(AssistantStringKey.Model), messages)
            .collect { emit(it) }
    }

    private fun firstQuestion(topic: AiTopic, t: Long): String {
        val at = dateUtil.timeString(t)
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
            AssistantStringKey.BaseUrl,
            AssistantStringKey.ExtraInstructions
        ),
        icon = pluginDescription.icon
    )
}
