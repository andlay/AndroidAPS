package app.aaps.plugins.assistant

import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef

enum class AssistantStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    // The API key must never leave the phone in a settings export
    override val exportable: Boolean = true
) : StringPreferenceKey {

    ApiKey("ai_api_key", "", AssistantStrings.ai_api_key_title, AssistantStrings.ai_api_key_summary, isPassword = true, exportable = false),
    Model("ai_model", "gpt-6-luna", AssistantStrings.ai_model_title, AssistantStrings.ai_model_summary),
    BaseUrl("ai_base_url", "https://api.openai.com/v1", AssistantStrings.ai_base_url_title, AssistantStrings.ai_base_url_summary),
    ExtraInstructions("ai_extra_instructions", "", AssistantStrings.ai_system_prompt_title, AssistantStrings.ai_system_prompt_summary)
}
