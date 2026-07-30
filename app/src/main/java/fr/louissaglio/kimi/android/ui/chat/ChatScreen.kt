package fr.louissaglio.kimi.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSubagent: (sessionId: String, subagentId: String) -> Unit = { _, _ -> },
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Document/photo picker for composer attachments (ticket 033): the ViewModel
    // resolves each Uri via the AttachmentStore (size-capped) into a staged chip or
    // an error effect. GetMultipleContents (not GetContent) so multi-selected files
    // all come back via ClipData instead of being silently dropped down to the last one.
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.onAttachmentsPicked(uris)
    }
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
                is ChatViewModel.Effect.ApprovalFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.QuestionFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.AbortFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.AttachmentPickFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.OpenAttachmentFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ChatViewModel.Effect.OpenFile ->
                    if (!openWithOtherApp(context, effect.contentUri, effect.mimeType)) {
                        viewModel.onOpenAttachmentFailed(effect.name)
                    }
            }
        }
    }
    // The socket is held only while the screen is visible (ON_START/ON_STOP pair).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    // History attachments (ticket 034): images resolve their bytes inline (base64) or
    // via /files; tapping a thumbnail opens the full-screen viewer, tapping a file
    // chip routes through the ViewModel, which downloads, caches and emits OpenFile.
    //
    // The callbacks flowing into the lazy list are hoisted to stable references:
    // recreated on every recomposition (fresh lambda, fresh callable reference), they
    // would defeat item-level skipping — the compiler auto-remembers only captures it
    // proves stable, and the ViewModel/launcher/state delegate don't qualify.
    val fullScreenImageState = remember { mutableStateOf<ChatMessage.Part.Image?>(null) }
    var fullScreenImage by fullScreenImageState
    val onImageClick = remember { { image: ChatMessage.Part.Image -> fullScreenImageState.value = image } }
    val onAttach = remember(attachLauncher) { { attachLauncher.launch("*/*") } }
    val onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit =
        remember(viewModel) { viewModel::onOpenAttachment }
    val loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? =
        remember(viewModel) { viewModel::imageBytes }
    // Linked `Agent` rows (ticket 045) open their sub-agent's page.
    val sessionId = uiState.sessionId
    val onOpenSubagent = remember(onNavigateToSubagent, sessionId) {
        { subagentId: String -> onNavigateToSubagent(sessionId, subagentId) }
    }
    // Scroll-to-top loads the next older history page (ticket 043).
    val onHistoryTopReached = remember(viewModel) { viewModel::onHistoryTopReached }
    ChatContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::onRetry,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onAllow = viewModel::onAllow,
        onDeny = viewModel::onDeny,
        onQuestionOptionPicked = viewModel::onQuestionOptionPicked,
        onQuestionOtherTextChange = viewModel::onQuestionOtherTextChange,
        onQuestionNext = viewModel::onQuestionNext,
        onQuestionBack = viewModel::onQuestionBack,
        onSubmitQuestion = viewModel::onSubmitQuestion,
        onDismissQuestion = viewModel::onDismissQuestion,
        onModelSelected = viewModel::onModelSelected,
        onEffortSelected = viewModel::onEffortSelected,
        onAttach = onAttach,
        onAttachmentRemoved = viewModel::onAttachmentRemoved,
        onImageClick = onImageClick,
        onOpenAttachment = onOpenAttachment,
        loadImageBytes = loadImageBytes,
        onOpenSubagent = onOpenSubagent,
        onHistoryTopReached = onHistoryTopReached,
    )
    fullScreenImage?.let { image ->
        FullScreenImageViewer(
            image = image,
            loadImageBytes = loadImageBytes,
            onDismiss = { fullScreenImage = null },
        )
    }
}

/** Hands a downloaded attachment to whatever app can view it; false when none can. */
private fun openWithOtherApp(context: Context, uri: Uri, mimeType: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    uiState: ChatViewModel.UiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onQuestionOptionPicked: (itemId: String, optionId: String) -> Unit,
    onQuestionOtherTextChange: (itemId: String, text: String) -> Unit,
    onQuestionNext: () -> Unit,
    onQuestionBack: () -> Unit,
    onSubmitQuestion: () -> Unit,
    onDismissQuestion: () -> Unit,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onAttach: () -> Unit,
    onAttachmentRemoved: (String) -> Unit,
    onImageClick: (ChatMessage.Part.Image) -> Unit,
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onOpenSubagent: (String) -> Unit = {},
    onHistoryTopReached: () -> Unit = {},
) {
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showTodos by rememberSaveable { mutableStateOf(false) }
    // Explicit "go to the newest" intent (ticket 051): the send wrapper below
    // bumps the tick that MessageList watches. Bumping is a plain state write —
    // the scroll itself runs inside MessageList, which owns the LazyListState
    // (keeping its open-at-the-newest-row initial index intact).
    var scrollToBottomTick by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.sessionCwd?.let { cwd ->
                            Text(
                                text = cwd,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // No actions (ticket 052): the context ring, working spinner and stop
                // button moved into the composer — the top bar is navigation-only.
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                // The card shows the head of the approval queue and stays until the
                // matching approval.resolved event, however the decision was made.
                uiState.pendingApprovals.firstOrNull()?.let { approval ->
                    ApprovalCard(
                        approval = approval,
                        queueSize = uiState.pendingApprovals.size,
                        onAllow = onAllow,
                        onDeny = onDeny,
                    )
                }
                // Questions are input, not a yes/no guard: the card collects the
                // answers and stays until the matching question.answered/dismissed event.
                uiState.pendingQuestions.firstOrNull()?.let { question ->
                    QuestionCard(
                        question = question,
                        queueSize = uiState.pendingQuestions.size,
                        step = uiState.questionStep,
                        selections = uiState.questionSelections,
                        otherTexts = uiState.questionOtherTexts,
                        isStepAnswered = uiState.isQuestionStepAnswered,
                        isSubmittable = uiState.isQuestionSubmittable,
                        onOptionPicked = onQuestionOptionPicked,
                        onOtherTextChange = onQuestionOtherTextChange,
                        onNext = onQuestionNext,
                        onBack = onQuestionBack,
                        onSubmit = onSubmitQuestion,
                        onDismiss = onDismissQuestion,
                    )
                }
                // The agent's todo list (ticket 042) sits directly above the composer;
                // its TodoList tool calls no longer appear as timeline rows.
                AnimatedVisibility(
                    visible = !uiState.todos.isNullOrEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TodosSummaryRow(
                        todos = uiState.todos.orEmpty(),
                        onClick = { showTodos = true },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                ChatInputBar(
                    text = uiState.inputText,
                    isSending = uiState.isSending,
                    isWorking = uiState.isWorking,
                    attachments = uiState.attachments,
                    // "K3 · high": the effective effort trails the model name like the
                    // web composer's label; hidden entirely until the models fetch lands.
                    modelLabel = uiState.currentModelLabel?.let { label ->
                        val effort = uiState.selectedEffort ?: uiState.defaultEffort
                        if (effort != null) "$label · $effort" else label
                    },
                    contextUsage = uiState.contextUsage,
                    onModelClick = { showModelPicker = true },
                    onTextChange = onInputChange,
                    // Send/Steer both funnel here; bump first so the jump rides
                    // the same recomposition as the ViewModel's echo append.
                    onSend = { scrollToBottomTick++; onSend() },
                    onStop = onStop,
                    onAttach = onAttach,
                    onAttachmentRemoved = onAttachmentRemoved,
                )
            }
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
            val items = uiState.items
            when {
                items.isNullOrEmpty() && uiState.error != null ->
                    ErrorState(uiState.error, onRetry, enabled = true)
                items == null ->
                    LoadingState()
                items.isEmpty() ->
                    EmptyState("No messages yet")
                else -> MessageList(
                    items,
                    Modifier.weight(1f),
                    onImageClick = onImageClick,
                    onOpenAttachment = onOpenAttachment,
                    loadImageBytes = loadImageBytes,
                    onOpenSubagent = onOpenSubagent,
                    isLoadingMoreHistory = uiState.isLoadingMoreHistory,
                    onHistoryTopReached = onHistoryTopReached,
                    scrollToBottomTick = scrollToBottomTick,
                )
            }
        }
    }
    if (showModelPicker) {
        ModelPickerSheet(
            uiState = uiState,
            onModelSelected = onModelSelected,
            onEffortSelected = onEffortSelected,
            onDismiss = { showModelPicker = false },
        )
    }
    // Re-derived from the state at open time: a clearing TodoList call while the sheet
    // is up simply closes it (there is nothing left to show). The open flag resets on
    // that transition too — otherwise the next non-empty list would pop the sheet back
    // with no tap.
    val todos = uiState.todos
    LaunchedEffect(todos.isNullOrEmpty()) {
        if (todos.isNullOrEmpty()) showTodos = false
    }
    if (showTodos && !todos.isNullOrEmpty()) {
        TodosSheet(todos = todos, onDismiss = { showTodos = false })
    }
}

@Preview
@Composable
private fun ChatContentPreview() {
    KimiTheme {
        ChatContent(
            uiState = ChatViewModel.UiState(
                sessionId = "session_86df049a-e22b-480e-842e-488b77c5469b",
                items = listOf(
                    ChatItem.UserMessage(
                        "msg_1",
                        "Why did the build fail? Reply **briefly**.",
                        createdAt = Instant.parse("2026-07-26T06:30:00Z"),
                    ),
                    ChatItem.AssistantMessage(
                        "msg_2",
                        "### Root cause\n\nMissing `Content-Type` header in `PostRequest`.\n\n" +
                            "- Add the header\n- Retry the call\n\n" +
                            "1. First check logs\n2. Then retry\n\n" +
                            "See [the docs](https://example.com/docs) for details.\n\n" +
                            "```\ncurl -X POST /retry\n```",
                        thinking = "Let me look at the failure output.",
                    ),
                    ChatItem.ToolGroup(
                        "tool_1",
                        listOf(
                            ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, preview = "curl -X POST /retry"),
                            ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Done, preview = "app/build.gradle.kts"),
                        ),
                    ),
                    ChatItem.ToolGroup(
                        "tool_3",
                        listOf(
                            ChatItem.ToolActivity("tool_3", "Bash", ToolStatus.Done, preview = "./gradlew test"),
                            ChatItem.ToolActivity("tool_4", "Bash", ToolStatus.Error, preview = "./gradlew lint"),
                        ),
                    ),
                    ChatItem.AssistantMessage("live_0", "Streaming reply…", thinking = ""),
                    ChatItem.ToolGroup(
                        "tool_5",
                        listOf(
                            ChatItem.ToolActivity("tool_5", "Read", ToolStatus.Done, preview = "docs/server-api.md"),
                            ChatItem.ToolActivity(
                                "tool_6",
                                "Bash",
                                ToolStatus.Running,
                                preview = "./gradlew assembleRelease",
                            ),
                        ),
                    ),
                ),
                isWorking = true,
                isReconnecting = true,
                inputText = "Draft message",
                contextUsage = ContextUsage(usedTokens = 205_000, maxTokens = 262_144),
                pendingApprovals = listOf(
                    ChatViewModel.PendingApproval("ap_1", "Bash", "echo hello-from-spike"),
                    ChatViewModel.PendingApproval("ap_2", "Write", "Writing /tmp/spike/notes.txt"),
                ),
                pendingQuestions = listOf(
                    PendingQuestion(
                        questionId = "tool_q1",
                        items = listOf(
                            PendingQuestion.Item(
                                id = "q_0",
                                header = "Color",
                                text = "Red or blue?",
                                options = listOf(
                                    PendingQuestion.Option("opt_0_0", "red", "warm color"),
                                    PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                                ),
                                allowOther = true,
                            ),
                            PendingQuestion.Item(
                                id = "q_1",
                                header = "Mode",
                                text = "Fast or safe?",
                                options = listOf(
                                    PendingQuestion.Option("opt_1_0", "fast", "skip checks"),
                                    PendingQuestion.Option("opt_1_1", "safe", "run checks"),
                                ),
                                allowOther = false,
                            ),
                            PendingQuestion.Item(
                                id = "q_2",
                                header = "Confirm",
                                text = "Yes or no?",
                                options = listOf(
                                    PendingQuestion.Option("opt_2_0", "yes", "go ahead"),
                                    PendingQuestion.Option("opt_2_1", "no", "stop"),
                                ),
                                allowOther = false,
                            ),
                        ),
                    ),
                ),
                questionSelections = mapOf("q_0" to "opt_0_1"),
                questionStep = 1,
                availableModels = listOf(
                    ServerModel("managed:kimi-code", "kimi-code/kimi-for-coding", "K2.7 Coding", 262_144),
                    ServerModel(
                        "managed:kimi-code",
                        "kimi-code/k3",
                        "K3",
                        1_048_576,
                        supportEfforts = listOf("low", "high", "max"),
                        defaultEffort = "high",
                    ),
                ),
                modelsAvailable = true,
                serverDefaultModel = "kimi-code/k3",
                sessionEffort = "low",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onRetry = {},
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllow = {},
            onDeny = {},
            onQuestionOptionPicked = { _, _ -> },
            onQuestionOtherTextChange = { _, _ -> },
            onQuestionNext = {},
            onQuestionBack = {},
            onSubmitQuestion = {},
            onDismissQuestion = {},
            onModelSelected = {},
            onEffortSelected = {},
            onAttach = {},
            onAttachmentRemoved = {},
            onImageClick = {},
            onOpenAttachment = {},
            loadImageBytes = { null },
        )
    }
}
