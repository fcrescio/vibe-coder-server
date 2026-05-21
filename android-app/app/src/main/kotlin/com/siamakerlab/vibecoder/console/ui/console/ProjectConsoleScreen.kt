package com.siamakerlab.vibecoder.console.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.ui.console.input.QuickActionChips
import com.siamakerlab.vibecoder.console.ui.console.input.VoiceButton
import com.siamakerlab.vibecoder.console.ui.console.messages.MessageRow
import com.siamakerlab.vibecoder.console.ui.console.scroll.rememberAutoScrollState
import com.siamakerlab.vibecoder.console.ui.console.status.StatusPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectConsoleScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenBuild: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenArtifacts: () -> Unit = {},
    onDeleteProject: () -> Unit = {},
    vm: ConsoleViewModel,
) {
    LaunchedEffect(projectId) { vm.bind(projectId) }
    val state by vm.state.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val scrollState = rememberAutoScrollState(itemCount = state.messages.size)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && scrollState.isAtBottom()) {
            scrollState.listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    vm.startNewSession()
                }) { Text(stringResource(R.string.console_new_session_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNew = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.console_new_session_title)) },
            text = { Text(stringResource(R.string.console_new_session_body)) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.console_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = buildSubtitle(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.console_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.console_menu_open),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_new_session)) },
                            onClick = { menuOpen = false; confirmNew = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_build)) },
                            onClick = { menuOpen = false; onOpenBuild() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_git)) },
                            onClick = { menuOpen = false; onOpenGit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_files)) },
                            onClick = { menuOpen = false; onOpenFiles() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_artifacts)) },
                            onClick = { menuOpen = false; onOpenArtifacts() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_delete)) },
                            onClick = { menuOpen = false; onDeleteProject() },
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding(),
        ) {
            StatusPanel(
                status = state.status,
                onRefresh = { vm.requestStatusRefresh() },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = scrollState.listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Top,
                ) {
                    items(state.messages, key = { it.seq }) { msg ->
                        MessageRow(message = msg)
                    }
                }
                if (scrollState.hasUnreadBelow() && state.messages.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                scrollState.listState.animateScrollToItem(state.messages.size - 1)
                                scrollState.clearUnread()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.console_jump_to_latest),
                        )
                    }
                }
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            err,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.dismissError() }) {
                            Text(stringResource(R.string.console_error_dismiss))
                        }
                    }
                }
            }

            QuickActionChips(
                tree = state.actions,
                onInvoke = { id -> vm.invokeAction(id) },
                onInsertText = { text ->
                    input = if (input.isBlank()) text else "$input\n$text"
                },
            )

            PromptInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendPrompt(input)
                        input = ""
                    }
                },
                onVoiceResult = { transcript ->
                    input = if (input.isBlank()) transcript else "$input $transcript"
                },
                sending = state.sending,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun buildSubtitle(state: ConsoleUiState): String {
    val conn = when (state.connection) {
        ConnectionState.Disconnected -> stringResource(R.string.console_conn_disconnected)
        ConnectionState.Connecting -> stringResource(R.string.console_conn_connecting)
        ConnectionState.Connected -> stringResource(R.string.console_conn_live)
        ConnectionState.Reconnecting -> stringResource(R.string.console_conn_reconnecting)
        ConnectionState.Failed -> stringResource(R.string.console_conn_failed)
    }
    val proc = if (state.processAlive) {
        stringResource(R.string.console_proc_alive)
    } else {
        stringResource(R.string.console_proc_idle)
    }
    val id = state.sessionId?.take(8)?.let { " · $it…" } ?: ""
    return "$conn · $proc$id"
}

@Composable
private fun PromptInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceResult: (String) -> Unit,
    sending: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            VoiceButton(
                onPartial = { /* preview-only; we wait for final result to commit */ },
                onResult = onVoiceResult,
                modifier = Modifier.padding(end = 4.dp).align(Alignment.Bottom),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.console_input_hint)) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 200.dp),
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Default,
                ),
            )
            IconButton(
                onClick = onSend,
                enabled = !sending && value.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).align(Alignment.Bottom),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.console_send),
                )
            }
        }
    }
}
