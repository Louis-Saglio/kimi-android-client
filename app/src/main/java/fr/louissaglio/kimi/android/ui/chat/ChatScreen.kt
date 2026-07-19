package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.ui.theme.KimiTheme

@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ChatViewModel.Effect.RefreshFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.SendFailed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Retry",
                        duration = SnackbarDuration.Short,
                    )
                    // The typed text was preserved in the UiState; Retry re-sends it as-is.
                    if (result == SnackbarResult.ActionPerformed) viewModel.onSend()
                }
            }
        }
    }
    // The socket is held only while the screen is visible (ON_START/ON_STOP pair).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    ChatContent(
        uiState = uiState,
        sessionId = sessionId,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::onRetry,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    uiState: ChatViewModel.UiState,
    sessionId: String,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = "Agent working" },
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                text = uiState.inputText,
                isSending = uiState.isSending,
                isWorking = uiState.isWorking,
                onTextChange = onInputChange,
                onSend = onSend,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.isReconnecting) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = "Connection lost — reconnecting…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            when {
                uiState.items.isEmpty() && uiState.error != null ->
                    ErrorState(uiState.error, onRetry, enabled = !uiState.isLoading)
                uiState.items.isEmpty() && uiState.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No messages yet", style = MaterialTheme.typography.bodyLarge)
                    }
                else -> MessageList(uiState.items, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MessageList(items: List<ChatItem>, modifier: Modifier = Modifier) {
    // reverseLayout pins the viewport to the newest row: new content auto-scrolls into
    // view only when already at the bottom, and never yanks a scrolled-up reader.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.asReversed(), key = ChatItem::id) { item ->
            when (item) {
                is ChatItem.UserMessage -> UserBubble(item)
                is ChatItem.AssistantMessage -> AssistantBubble(item)
                is ChatItem.ToolActivity -> ToolRow(item)
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    isSending: Boolean,
    isWorking: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    // Sending is gated on an active turn (turn.started/turn.ended drive isWorking) and on
    // blank input; the field itself stays editable so a message can be drafted mid-turn.
    val canSend = text.isNotBlank() && !isSending && !isWorking
    // imePadding docks the bar above the keyboard (the manifest sets adjustResize).
    Surface(modifier = Modifier.imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Message input" },
                placeholder = { Text("Message Kimi…") },
                // Grows with content up to 5 lines, then scrolls internally.
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.semantics { contentDescription = "Send" },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatItem.UserMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatItem.AssistantMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.thinking.isNotBlank()) {
                    Text(
                        text = message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.text.isNotBlank()) Spacer(Modifier.size(6.dp))
                }
                if (message.text.isNotBlank()) {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ToolRow(activity: ChatItem.ToolActivity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (activity.status == ToolStatus.Running) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        Text(
            text = buildString {
                append(activity.name.ifBlank { "tool" })
                append(
                    when (activity.status) {
                        ToolStatus.Running -> " · running"
                        ToolStatus.Done -> " · done"
                        ToolStatus.Error -> " · error"
                    },
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (activity.status == ToolStatus.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, enabled: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, enabled = enabled) {
            Text("Retry")
        }
    }
}

@Preview
@Composable
private fun ChatContentPreview() {
    KimiTheme {
        ChatContent(
            uiState = ChatViewModel.UiState(
                isLoading = false,
                items = listOf(
                    ChatItem.UserMessage("msg_1", "Reply with exactly: ok"),
                    ChatItem.AssistantMessage("msg_2", "ok", thinking = "Short reply requested."),
                    ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
                    ChatItem.AssistantMessage("live_0", "Streaming reply…", thinking = ""),
                    ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Running),
                ),
                isWorking = true,
                isReconnecting = true,
                inputText = "Draft message",
            ),
            sessionId = "session_86df049a-e22b-480e-842e-488b77c5469b",
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onRetry = {},
            onInputChange = {},
            onSend = {},
        )
    }
}
