package app.aaps.core.interfaces.ai

import kotlinx.coroutines.flow.Flow

/**
 * An AI that explains what the loop did and why. It only reads: it never doses, never changes a
 * setting and never feeds anything back into the loop.
 *
 * Screens find it through the plugin list, so a build without the assistant module simply shows no
 * AI buttons.
 */
interface AiAssistant {

    /** True when the plugin is enabled and an API key is set. */
    fun isConfigured(): Boolean

    /**
     * Streams an answer as text pieces, in order. A failure (no network, bad key, unknown model)
     * ends the flow with an exception whose message can be shown to the user.
     *
     * @param topic what the user asked about, so the right data is collected
     * @param timestamp the moment on the graph the question is about; now for a general question
     * @param conversation the questions and answers so far; the last one is the new question, or the
     *   list is empty for a first "explain this" without a typed question
     */
    fun ask(topic: AiTopic, timestamp: Long, conversation: List<AiTurn>): Flow<String>

    /**
     * The data the assistant would send for [topic] at [timestamp], as text. Lets the user see what
     * leaves the phone, and paste it into another AI by hand.
     */
    suspend fun contextFor(topic: AiTopic, timestamp: Long): String
}

/** What a question is about. Decides which data is collected and which facts are worked out first. */
enum class AiTopic {

    /** BG graph: readings and the prediction lines (IOB, COB, UAM, ZT) */
    BG_PREDICTIONS,

    /** IOB / basal graph: the dosing decision - SMB, temp basal, zero temp */
    INSULIN,

    /** COB graph: carb absorption, minimum absorption */
    COB,

    /** DEV / BGI graph: deviations, BG impact, UAM */
    DEVIATIONS,

    /** Sensitivity: autosens ratio, Dynamic ISF */
    SENSITIVITY,

    /** A free question from the search bar */
    GENERAL
}

/** One message in a conversation with the assistant. */
data class AiTurn(val fromUser: Boolean, val text: String)
