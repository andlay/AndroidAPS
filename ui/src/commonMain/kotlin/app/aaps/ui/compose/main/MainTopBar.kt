package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.ai.AiTopic
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalAiAssistant
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.icons.IcSettingsOff
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.ai.AiExplainSheet
import app.aaps.ui.search.M3SearchBar
import app.aaps.ui.search.SearchUiState

/**
 * Main top bar with M3-style search bar.
 * Layout: [Menu] [----Search Bar----] [SimpleMode?] [Settings]
 *
 * @param searchUiState Current search UI state
 * @param onMenuClick Called when menu button is clicked
 * @param onPreferencesClick Called when preferences button is clicked
 * @param onSearchQueryChange Called when search query changes
 * @param onSearchClear Called when search query is cleared
 * @param onSearchActiveChange Called when search active state changes
 * @param isSimpleMode When true, shows a non-interactive simple-mode indicator next to Settings
 * @param modifier Modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    searchUiState: SearchUiState,
    onMenuClick: () -> Unit,
    onPreferencesClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClear: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    isSimpleMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    // AI question about the docs and recent data; the wand is shown only when the assistant is set up
    val aiAssistant = LocalAiAssistant.current
    val dateUtil = LocalDateUtil.current
    var askingAi by remember { mutableStateOf(false) }
    val aiIcon: (@Composable () -> Unit)? = if (aiAssistant() != null) {
        {
            IconButton(onClick = { askingAi = true }) {
                Icon(
                    imageVector = Icons.Filled.AutoFixHigh,
                    contentDescription = stringResource(UiStrings.ai_ask_title),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else null
    if (askingAi) {
        aiAssistant()?.let { assistant ->
            AiExplainSheet(
                assistant = assistant,
                topic = AiTopic.GENERAL,
                timestamp = remember { dateUtil.now() },
                title = stringResource(UiStrings.ai_ask_title),
                autoExplain = false,
                onDismiss = { askingAi = false }
            )
        }
    }
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                M3SearchBar(
                    query = searchUiState.query,
                    isActive = searchUiState.isSearchActive,
                    onQueryChange = onSearchQueryChange,
                    onClearClick = onSearchClear,
                    onActiveChange = onSearchActiveChange,
                    modifier = Modifier.weight(1f),
                    idleTrailing = aiIcon
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(CoreUiStrings.open_navigation)
                )
            }
        },
        actions = {
            IconButton(onClick = onPreferencesClick) {
                // In simple mode the gear shows "crossed" (IcSettingsOff) to signal the mode;
                // the button action (open settings) is unchanged.
                Icon(
                    imageVector = if (isSimpleMode) IcSettingsOff else Icons.Default.Settings,
                    contentDescription = stringResource(CoreUiStrings.settings)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        windowInsets = WindowInsets(0),
        modifier = modifier
    )
}
