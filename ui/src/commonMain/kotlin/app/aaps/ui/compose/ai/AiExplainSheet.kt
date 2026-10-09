package app.aaps.ui.compose.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.interfaces.ai.AiAssistant
import app.aaps.core.interfaces.ai.AiTopic
import app.aaps.core.interfaces.ai.AiTurn
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The AI conversation in a bottom sheet. Opens with an explanation of [topic] at [timestamp] when
 * [autoExplain] is set, otherwise waits for a question. Each answer appears while it is written.
 *
 * Nothing here acts on the answer: it is text for the user to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiExplainSheet(
    assistant: AiAssistant,
    topic: AiTopic,
    timestamp: Long,
    title: String,
    autoExplain: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<AiTurn>() }
    var streaming by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(question: String?) {
        if (question != null) turns.add(AiTurn(fromUser = true, text = question))
        streaming = ""
        error = null
        busy = true
        job = scope.launch {
            try {
                assistant.ask(topic, timestamp, turns.toList()).collect { streaming += it }
                turns.add(AiTurn(fromUser = false, text = streaming))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            } finally {
                streaming = ""
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { if (autoExplain) run(null) }

    ModalBottomSheet(
        onDismissRequest = { job?.cancel(); onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = AapsSpacing.medium).weight(1f)
                )
                TextButton(onClick = {
                    if (data == null) scope.launch { data = assistant.contextFor(topic, timestamp) } else data = null
                }) { Text(stringResource(if (data == null) UiStrings.ai_show_data else UiStrings.ai_hide_data)) }
            }
            Text(
                text = stringResource(UiStrings.ai_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Takes the room left in the sheet and scrolls; the question field stays visible
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = AapsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
            ) {
                data?.let {
                    SelectionContainer {
                        Text(text = it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                for (turn in turns) {
                    SelectionContainer {
                        if (turn.fromUser) Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        else AiAnswerText(turn.text)
                    }
                }
                if (busy) {
                    if (streaming.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(AapsSpacing.extraLarge), strokeWidth = AapsSpacing.extraSmall)
                            Text(stringResource(UiStrings.ai_thinking), modifier = Modifier.padding(start = AapsSpacing.medium))
                        }
                    } else AiAnswerText(streaming)
                }
                error?.let {
                    Text(text = stringResource(UiStrings.ai_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(UiStrings.ai_ask_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                IconButton(
                    enabled = input.isNotBlank() && !busy,
                    onClick = { val q = input.trim(); input = ""; run(q) }
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(UiStrings.ai_send)) }
            }
        }
    }
}

/**
 * An answer, with the loop's log lines that the model quotes ("> " lines) drawn as log text: monospace,
 * on their own background, so they read as the app's own output and not as the model's words.
 */
@Composable
private fun AiAnswerText(text: String) {
    // Consecutive lines of the same kind stay together, so normal text keeps its line breaks
    val blocks = mutableListOf<Pair<Boolean, MutableList<String>>>()
    for (line in text.lines()) {
        val isLog = line.startsWith(">")
        val content = if (isLog) line.removePrefix(">").trimStart() else line
        if (blocks.lastOrNull()?.first == isLog) blocks.last().second.add(content)
        else blocks.add(isLog to mutableListOf(content))
    }
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        for ((isLog, lines) in blocks) {
            val joined = lines.joinToString("\n").trim('\n')
            if (joined.isEmpty()) continue
            if (isLog) Text(
                text = joined,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            )
            else Text(text = joined, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
