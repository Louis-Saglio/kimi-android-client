package fr.louissaglio.kimi.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import fr.louissaglio.kimi.android.data.chat.BashTimeout
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.asCardModel
import fr.louissaglio.kimi.android.data.chat.bashCommandOf
import fr.louissaglio.kimi.android.data.chat.bashDescriptionOf
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSubagent: (sessionId: String, subagentId: String) -> Unit = { _, _ -> },
    onNavigateToJob: (
        sessionId: String,
        taskId: String,
        budgetSeconds: Int?,
        budgetIsDefault: Boolean,
        timedOut: Boolean,
        description: String?,
        command: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
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
                is ChatViewModel.Effect.SendFailed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Retry",
                        duration = SnackbarDuration.Short,
                    )
                    // The typed text was preserved in the UiState; Retry re-sends it as-is.
                    if (result == SnackbarResult.ActionPerformed) viewModel.onSend()
                }
                is ChatViewModel.Effect.OpenFile ->
                    if (!openWithOtherApp(context, effect.contentUri, effect.mimeType)) {
                        viewModel.onOpenAttachmentFailed(effect.name)
                    }
                // Every other effect is a plain message snackbar (ticket 071).
                else -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }
    // ON_START/ON_STOP feed the socket gate (ticket 045): the session socket stays
    // collected while this screen is visible OR a sub-agent page (or the job screen,
    // ticket 097 — same page count) of it is open.
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
    // Linked background `Bash` rows (ticket 096) open their job screen. The timeout
    // budget (ticket 097) resolves from the matching timeline row — the call input is
    // the only wire location of the timeout — and the timed-out flag from the jobs
    // list; an unknown row navigates budget-less (plain elapsed, the 096 behavior).
    // The call input's description/command seed the job screen's title and Command
    // section; a jobs-sheet entry can outlive its timeline row, so the description
    // falls back to the jobs-store Job's — null leaves either to the entry fetch.
    val timelineItems = uiState.items
    val jobs = uiState.jobs
    val onOpenJob = remember(onNavigateToJob, sessionId, timelineItems, jobs) {
        { taskId: String ->
            val row = timelineItems?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.firstOrNull { it.bashTaskId == taskId }
            val timeout = row?.let { BashTimeout(it.timeoutSeconds, it.timeoutDisabled) }
            val job = jobs.firstOrNull { it.taskId == taskId }
            onNavigateToJob(
                sessionId,
                taskId,
                timeout?.effectiveSeconds,
                timeout?.isDefault ?: false,
                job?.timedOut == true,
                row?.let { bashDescriptionOf(it.name, it.input) } ?: job?.description,
                row?.let { bashCommandOf(it.name, it.input) },
            )
        }
    }
    // Scroll-to-top loads the next older history page (ticket 043).
    val onHistoryTopReached = remember(viewModel) { viewModel::onHistoryTopReached }
    // AskUserQuestion rows (088/089): pending row toggles the live card, resolved
    // row opens/closes its read-only view — decided by the ViewModel.
    val onQuestionRowTapped = remember(viewModel) { viewModel::onQuestionRowTapped }
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
        onQuestionOptionToggled = viewModel::onQuestionOptionToggled,
        onQuestionOtherTextChange = viewModel::onQuestionOtherTextChange,
        onQuestionNext = viewModel::onQuestionNext,
        onQuestionBack = viewModel::onQuestionBack,
        onSubmitQuestion = viewModel::onSubmitQuestion,
        onDismissQuestion = viewModel::onDismissQuestion,
        onMinimizeQuestion = viewModel::onMinimizeQuestion,
        onCloseResolvedQuestion = viewModel::onCloseResolvedQuestion,
        onResolvedQuestionNext = viewModel::onResolvedQuestionNext,
        onResolvedQuestionBack = viewModel::onResolvedQuestionBack,
        onQuestionRowTapped = onQuestionRowTapped,
        onModelSelected = viewModel::onModelSelected,
        onEffortSelected = viewModel::onEffortSelected,
        onAttach = onAttach,
        onAttachmentRemoved = viewModel::onAttachmentRemoved,
        onImageClick = onImageClick,
        onOpenAttachment = onOpenAttachment,
        loadImageBytes = loadImageBytes,
        onOpenSubagent = onOpenSubagent,
        onOpenJob = onOpenJob,
        onHistoryTopReached = onHistoryTopReached,
        onCompact = viewModel::onCompact,
        onCancelJob = viewModel::onCancelJob,
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

/** The secondary-container status line above the timeline (ticket 071): reconnect and
 * transient LLM-retry notices — one line, ellipsized (a retry error message can be long). */
@Composable
private fun StatusBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
    onQuestionOptionToggled: (itemId: String, optionId: String) -> Unit,
    onQuestionOtherTextChange: (itemId: String, text: String) -> Unit,
    onQuestionNext: () -> Unit,
    onQuestionBack: () -> Unit,
    onSubmitQuestion: () -> Unit,
    onDismissQuestion: () -> Unit,
    onMinimizeQuestion: () -> Unit,
    onCloseResolvedQuestion: () -> Unit,
    onResolvedQuestionNext: () -> Unit,
    onResolvedQuestionBack: () -> Unit,
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onAttach: () -> Unit,
    onAttachmentRemoved: (String) -> Unit,
    onImageClick: (ChatMessage.Part.Image) -> Unit,
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onHistoryTopReached: () -> Unit = {},
    onCompact: () -> Unit = {},
    onCancelJob: (String) -> Unit = {},
) {
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    // Ticket 089: system back closes the read-only resolved view before navigating.
    BackHandler(enabled = uiState.resolvedQuestionView != null) { onCloseResolvedQuestion() }
    // Explicit "go to the newest" intent (ticket 051): the send wrapper below
    // bumps the tick that MessageList watches. Bumping is a plain state write —
    // the scroll itself runs inside MessageList, which owns the LazyListState
    // (keeping its open-at-the-newest-row initial index intact).
    var scrollToBottomTick by remember { mutableIntStateOf(0) }
    // The one compact enabled rule (056/057): a running turn (40910 server-side) or a
    // compaction in progress (silent server no-op) disables every compact affordance —
    // the overflow menu item and the context ring's dialog button share this flag.
    val canCompact = !uiState.isWorking && !uiState.isCompacting
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
                actions = {
                    // Session-level actions (ticket 056): the overflow menu hosts
                    // infrequent session management — "Compact session" first, built
                    // to grow (rename/delete/export… later). The context ring, working
                    // spinner and stop button stay in the composer (ticket 053).
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Session options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Compact session") },
                                enabled = canCompact,
                                onClick = {
                                    menuExpanded = false
                                    onCompact()
                                },
                            )
                        }
                    }
                },
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
                // Ticket 089: a resolved question viewed read-only from its timeline
                // row occupies the dock — a live pending card waits behind it.
                uiState.resolvedQuestionView?.let { view ->
                    val card = view.asCardModel()
                    QuestionCard(
                        question = card.question,
                        queueSize = 1,
                        step = uiState.resolvedQuestionStep,
                        selections = card.selections,
                        otherTexts = card.otherTexts,
                        isStepAnswered = true,
                        isSubmittable = false,
                        onOptionPicked = { _, _ -> },
                        onOptionToggled = { _, _ -> },
                        onOtherTextChange = { _, _ -> },
                        onNext = onResolvedQuestionNext,
                        onBack = onResolvedQuestionBack,
                        onSubmit = {},
                        onDismiss = {},
                        onMinimize = {},
                        readOnly = true,
                        note = view.note,
                        onClose = onCloseResolvedQuestion,
                    )
                }
                // Questions are input, not a yes/no guard: the card collects the
                // answers and stays until the matching question.answered/dismissed event.
                // Minimized (ticket 088): nothing renders — the pending AskUserQuestion
                // tool row in the timeline reopens the card.
                if (uiState.resolvedQuestionView == null && !uiState.questionCardMinimized) {
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
                            onOptionToggled = onQuestionOptionToggled,
                            onOtherTextChange = onQuestionOtherTextChange,
                            onNext = onQuestionNext,
                            onBack = onQuestionBack,
                            onSubmit = onSubmitQuestion,
                            onDismiss = onDismissQuestion,
                            onMinimize = onMinimizeQuestion,
                        )
                    }
                }
                // The agent's todo list (ticket 042) and the background-jobs counter
                // (ticket 092) sit directly above the composer — the shared dock owns
                // the pills, the sheets and their open state (TodoJobsDock.kt).
                TodoJobsDock(
                    todos = uiState.todos,
                    jobs = uiState.jobs,
                    items = uiState.items,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                    onCancelJob = onCancelJob,
                )
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
                    compactEnabled = canCompact,
                    onCompact = onCompact,
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
                StatusBanner("Connection lost — reconnecting…")
            }
            // Transient LLM-retry status (ticket 058): the same status-line affordance
            // as the reconnect banner — while the server backs off and retries the
            // step's model call, the chat would otherwise look stalled.
            uiState.stepRetry?.let { retry ->
                StatusBanner(
                    "Model call failed — retrying (attempt ${retry.nextAttempt} of " +
                        "${retry.maxAttempts})" +
                        if (retry.errorMessage.isNotBlank()) ": ${retry.errorMessage}" else "",
                )
            }
            val items = uiState.items
            when {
                items.isNullOrEmpty() && uiState.error != null ->
                    ErrorState(uiState.error, onRetry)
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
                    onOpenJob = onOpenJob,
                    // Tickets 088/089: AskUserQuestion rows never expand — the pending
                    // row toggles the live card, a resolved row opens its read-only view.
                    onQuestionRowTapped = onQuestionRowTapped,
                    isLoadingMoreHistory = uiState.isLoadingMoreHistory,
                    onHistoryTopReached = onHistoryTopReached,
                    scrollToBottomTick = scrollToBottomTick,
                )
            }
        }
    }
    if (showModelPicker) {
        ModelPickerSheet(
            availableModels = uiState.availableModels,
            currentModelId = uiState.sessionModel ?: uiState.serverDefaultModel,
            serverDefaultModel = uiState.serverDefaultModel,
            supportedEfforts = uiState.supportedEfforts,
            selectedEffort = uiState.selectedEffort,
            defaultEffort = uiState.defaultEffort,
            onModelSelected = onModelSelected,
            onEffortSelected = onEffortSelected,
            onDismiss = { showModelPicker = false },
        )
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
                    ChatItem.CompactionDivider(
                        "msg_compact",
                        pending = false,
                        tokensBefore = 26_500,
                        tokensAfter = 386,
                        summary = "The session pinned the retry fix and verified it with the test suite.",
                    ),
                    ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true),
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
                questionSelections = mapOf("q_0" to setOf("opt_0_1")),
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
            onQuestionOptionToggled = { _, _ -> },
            onQuestionOtherTextChange = { _, _ -> },
            onQuestionNext = {},
            onQuestionBack = {},
            onSubmitQuestion = {},
            onDismissQuestion = {},
            onMinimizeQuestion = {},
            onCloseResolvedQuestion = {},
            onResolvedQuestionNext = {},
            onResolvedQuestionBack = {},
            onQuestionRowTapped = { _, _, _ -> },
            onModelSelected = {},
            onEffortSelected = {},
            onAttach = {},
            onAttachmentRemoved = {},
            onImageClick = {},
            onOpenAttachment = {},
            loadImageBytes = { null },
            onCompact = {},
        )
    }
}
