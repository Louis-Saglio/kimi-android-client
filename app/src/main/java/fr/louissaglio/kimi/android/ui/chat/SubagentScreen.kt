package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.BashTimeout
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.bashCommandOf
import fr.louissaglio.kimi.android.data.chat.bashDescriptionOf
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.theme.warningColor

/**
 * Read-only sub-agent page (ticket 045), transcript-backed since ticket 046: the
 * sub-agent's committed history (fetched per-agent from the transcript endpoint) plus
 * its live stream — spliced prompts as user bubbles, assistant text and thinking,
 * tool rows — with no composer, no approval cards and no question wizard (a sub-agent
 * cannot be talked to; its questions and approvals stay in the main chat). The list
 * reuses the chat's [MessageList], auto-scroll and history pagination included.
 * The bottom bar hosts the shared todos/jobs dock (the chat composer's widgets):
 * the sub-agent's own todo list and its own background tasks. Background `Bash` rows
 * — timeline and jobs sheet alike — open the job screen (ticket 098), which runs
 * store-backed for these sub-agent-owned tasks; the sheet stays without a Stop
 * (the sub-agent's own task controls are not wired — no ticket owns them yet).
 * The top bar's overflow hosts "To background" (ticket 138) while the record is a
 * running FOREGROUND member with a known task id — the single home of the detach
 * affordance, covering plain agents and swarm members alike since both open this
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentScreen(
    onNavigateBack: () -> Unit,
    /** Nested sub-agents (ticket 071): a linked `Agent` row inside this transcript
     * navigates one page deeper. Invoked with the nested subagentId. */
    onOpenSubagent: (String) -> Unit = {},
    /** Background `Bash` rows (ticket 098) open their job screen. Same resolution as
     * the chat's (ChatScreen): the budget/description/command resolve from the tapped
     * row's call input, the timed-out flag from the jobs list. */
    onNavigateToJob: (
        sessionId: String,
        taskId: String,
        budgetSeconds: Int?,
        budgetIsDefault: Boolean,
        timedOut: Boolean,
        description: String?,
        command: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    /** Ticket 119: a parsed `Edit` row opens the unified diff screen, exactly like
     * the chat's rows — the payload rides along for the ToolContentStore hand-off. */
    onNavigateToEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    /** Ticket 120: a parsed `Read`/`Write` row opens the file content screen, exactly
     * like the chat's rows — payload and output snapshot ride along. */
    onNavigateToFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    /** Ticket 158: the "Next step" section's Continue — invoked with the composer
     * prefill (the hint's suggested `Agent(resume=…)` call text). The page has no
     * composer of its own, so the nav host pops back to the chat, where the
     * prefill lands in its input. */
    onContinueWithResume: (String) -> Unit = {},
    viewModel: SubagentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Registers the page while visible (ON_START/ON_STOP pair): the registration keeps
    // the store fed for as long as a page of the session is open — by the chat's socket
    // gate when it exists, by the store's own keep-alive collection otherwise (064).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect -> snackbarHostState.showSnackbar(effect.message) }
    }
    // Linked background `Bash` rows open their job screen (ticket 098) — the chat's
    // ticket-096 resolution: the timeout budget comes from the matching timeline row
    // (the call input is the only wire location of the timeout), the timed-out flag
    // from the jobs list, and the call input's description/command seed the job
    // screen's title and Command section; an unknown row navigates budget-less.
    val sessionId = uiState.sessionId
    val timelineItems = uiState.items
    val jobs = uiState.jobs
    val onOpenJob = remember(onNavigateToJob, sessionId, timelineItems, jobs) {
        { taskId: String ->
            val row = timelineItems.filterIsInstance<ChatItem.ToolGroup>()
                .flatMap { it.tools }
                .firstOrNull { it.bashTaskId == taskId }
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
    // Ticket 184: tool-result images — the chat's thumbnail tap → full-screen viewer,
    // bytes through the page's session-media fetch. Hoisted to stable references
    // (ChatScreen's rationale: fresh lambdas would defeat lazy-item skipping).
    val fullScreenImageState = remember { mutableStateOf<ChatMessage.Part.Image?>(null) }
    var fullScreenImage by fullScreenImageState
    val onImageClick = remember { { image: ChatMessage.Part.Image -> fullScreenImageState.value = image } }
    val loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? =
        remember(viewModel) { viewModel::imageBytes }
    SubagentContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenSubagent = onOpenSubagent,
        onOpenJob = onOpenJob,
        onOpenEditDiff = onNavigateToEditDiff,
        onOpenFileContent = onNavigateToFileContent,
        onContinueWithResume = onContinueWithResume,
        onDetachToBackground = viewModel::onDetachToBackground,
        onRetry = viewModel::onRetry,
        onHistoryTopReached = viewModel::onHistoryTopReached,
        snackbarHostState = snackbarHostState,
        onImageClick = onImageClick,
        loadImageBytes = loadImageBytes,
    )
    fullScreenImage?.let { image ->
        FullScreenImageViewer(
            image = image,
            loadImageBytes = loadImageBytes,
            onDismiss = { fullScreenImage = null },
        )
    }
}

/** The page body, split from the ViewModel for semantics tests (the SwarmContent
 * pattern): a plain [SubagentViewModel.UiState] plus intent callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentContent(
    uiState: SubagentViewModel.UiState,
    onNavigateBack: () -> Unit,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onOpenEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    onOpenFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    /** The "Next step" section's Continue (ticket 158), offered while the record
     *  is finished with continuation copy — invoked with the composer prefill. */
    onContinueWithResume: (String) -> Unit = {},
    /** The overflow's "To background" (ticket 138), offered while the record says
     * the member is detachable. */
    onDetachToBackground: () -> Unit = {},
    onRetry: () -> Unit = {},
    onHistoryTopReached: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onImageClick: (ChatMessage.Part.Image) -> Unit = {},
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { null },
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.description?.ifBlank { null } ?: uiState.subagentId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The subagent type, and the background badge (ticket 182:
                        // the spawn task frame's `detached:true`).
                        listOfNotNull(
                            uiState.name?.takeIf { it.isNotBlank() },
                            "Background".takeIf { uiState.runInBackground },
                        ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { subtitle ->
                            Text(
                                text = subtitle,
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
                    uiState.status?.let { status ->
                        SubagentStatusChip(status, Modifier.padding(end = 12.dp))
                    }
                    // Ticket 138: the detach affordance lives only here (not the
                    // timeline card, not the roster row) and only while the record
                    // is a running foreground member with a known task id — a
                    // background member and a terminal one never see it. The item
                    // disables while the `task.background` control is in flight;
                    // the replied `detached:true` state flips the record, which
                    // removes the whole affordance (web-parity label).
                    if (uiState.detachable) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Sub-agent options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("To background") },
                                    enabled = !uiState.detaching,
                                    onClick = {
                                        menuExpanded = false
                                        onDetachToBackground()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // Ticket 158: a finished run whose result carried continuation
                // copy shows the "Next step" section pinned above the dock —
                // the copy verbatim plus the Continue action that pre-fills
                // the chat's composer with the hint's suggested resume call.
                if (uiState.hasNextStep) {
                    AgentNextStepSection(
                        nextStep = uiState.nextStep,
                        resumeHint = uiState.resumeHint,
                        prefill = uiState.resumePrefill,
                        onContinue = onContinueWithResume,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                // The sub-agent's own todos/jobs — the chat's dock, minus the
                // cancel (the sub-agent's own task controls are not wired yet).
                TodoJobsDock(
                    todos = uiState.todos,
                    jobs = uiState.jobs,
                    items = uiState.items,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val error = uiState.error
            when {
                // The transcript fetch is the page's only data source here (no spawn,
                // no seed, no stream): its failure gets the full-screen retry.
                error != null && uiState.items.isEmpty() ->
                    ErrorState(error, onRetry = onRetry)
                uiState.status == null && uiState.isLoading -> LoadingState()
                // Neither a live stream nor a transcript entry exists.
                uiState.status == null -> EmptyState("No transcript for this sub-agent.")
                uiState.items.isEmpty() -> EmptyState("No activity yet")
                else -> MessageList(
                    uiState.items,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                    onOpenEditDiff = onOpenEditDiff,
                    onOpenFileContent = onOpenFileContent,
                    // Ticket 158: a nested spawn row's Continue takes the same
                    // pop-back-to-composer route as the page's own section.
                    onContinueSubagent = onContinueWithResume,
                    onImageClick = onImageClick,
                    loadImageBytes = loadImageBytes,
                    isLoadingMoreHistory = uiState.isLoadingMoreHistory,
                    onHistoryTopReached = onHistoryTopReached,
                )
            }
        }
    }
}

/** The lifecycle chip (sub-agent page header, swarm roster rows, swarm card's
 * aggregate). [label] overrides the status's own label — the swarm card's aggregate
 * chip says "Running" while reusing the Working style (ticket 135). Suspended reads
 * as transient (the warning amber, never an error — it means "provider rate limit,
 * requeued for retry"); Cancelled is grey with a close glyph, a deliberate stop. */
@Composable
internal fun SubagentStatusChip(status: SubagentStatus, modifier: Modifier = Modifier, label: String? = null) {
    val (text, color) = when (status) {
        SubagentStatus.Queued -> (label ?: "Queued") to MaterialTheme.colorScheme.surfaceVariant
        SubagentStatus.Working -> (label ?: "Working") to MaterialTheme.colorScheme.primaryContainer
        SubagentStatus.Suspended -> (label ?: "Suspended") to MaterialTheme.colorScheme.surfaceVariant
        SubagentStatus.Completed -> (label ?: "Completed") to MaterialTheme.colorScheme.secondaryContainer
        SubagentStatus.Failed -> (label ?: "Failed") to MaterialTheme.colorScheme.errorContainer
        SubagentStatus.Cancelled -> (label ?: "Cancelled") to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (status == SubagentStatus.Cancelled) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = when (status) {
                    SubagentStatus.Suspended -> MaterialTheme.warningColor
                    SubagentStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Color.Unspecified
                },
            )
        }
    }
}
