package fr.louissaglio.kimi.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Document/photo picker for composer attachments (ticket 033): each Uri is resolved
    // eagerly into name/size/mime/bytes so chips can render and the send path uploads.
    // GetMultipleContents (not GetContent) so multi-selected files all come back via
    // ClipData instead of being silently dropped down to the last one.
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var failed = 0
                for (uri in uris) {
                    val resolved = withContext(Dispatchers.IO) { resolveAttachment(context.contentResolver, uri) }
                    if (resolved != null) {
                        viewModel.onAttachmentPicked(resolved.name, resolved.sizeBytes, resolved.mimeType, resolved.bytes)
                    } else {
                        failed++
                    }
                }
                if (failed > 0) snackbarHostState.showSnackbar("Could not read $failed picked file(s)")
            }
        }
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
            }
        }
    }
    // The socket is held only while the screen is visible (ON_START/ON_STOP pair).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    // History attachments (ticket 034): images resolve their bytes inline (base64) or
    // via /files; tapping a thumbnail opens the full-screen viewer, tapping a file
    // chip downloads the bytes and hands them to another app through a FileProvider.
    var fullScreenImage by remember { mutableStateOf<ChatMessage.Part.Image?>(null) }
    val loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { image ->
        resolveImageBytes(image) { fileId -> viewModel.downloadFile(fileId).getOrNull()?.bytes }
    }
    val onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit = open@{ attachment ->
        val fileId = attachment.fileId ?: return@open
        scope.launch {
            val download = viewModel.downloadFile(fileId).getOrNull()
            if (download == null) {
                snackbarHostState.showSnackbar("Could not download ${attachment.name}")
                return@launch
            }
            val uri = withContext(Dispatchers.IO) { writeToCache(context, attachment.name, download.bytes) }
            if (uri == null || !openWithOtherApp(context, uri, attachment.mimeType)) {
                snackbarHostState.showSnackbar("No app can open ${attachment.name}")
            }
        }
    }
    ChatContent(
        uiState = uiState,
        sessionId = sessionId,
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
        onAttach = { attachLauncher.launch("*/*") },
        onAttachmentRemoved = viewModel::onAttachmentRemoved,
        onImageClick = { fullScreenImage = it },
        onOpenAttachment = onOpenAttachment,
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

/** The bytes of a history image (ticket 034): decoded from the inline base64, or
 * fetched via `GET /api/v1/files/{id}` for a file-referenced source. */
private suspend fun resolveImageBytes(
    image: ChatMessage.Part.Image,
    downloadFile: suspend (String) -> ByteArray?,
): ByteArray? =
    image.base64Data?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        ?: image.fileId?.let { downloadFile(it) }

/** Writes downloaded bytes to the cache dir and returns a FileProvider content Uri
 * other apps may read (ticket 034); null on any failure. */
private fun writeToCache(context: Context, name: String, bytes: ByteArray): Uri? = runCatching {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
    val file = File(dir, safeName).apply { writeBytes(bytes) }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

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

/** A picked document resolved to upload-ready bytes (ticket 033); null on any read
 * failure. The display name/size come from the provider's OpenableColumns, the MIME
 * type from the ContentResolver (the server re-sniffs it from the bytes anyway). */
private class ResolvedAttachment(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val bytes: ByteArray,
)

private fun resolveAttachment(contentResolver: ContentResolver, uri: Uri): ResolvedAttachment? {
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    var name: String? = null
    var size: Long = -1
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                ?.let { name = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                ?.let { size = cursor.getLong(it) }
        }
    }
    return ResolvedAttachment(
        name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment",
        sizeBytes = if (size >= 0) size else bytes.size.toLong(),
        mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
        bytes = bytes,
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
) {
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showTodos by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle ?: sessionId,
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
                    // Context ring first (ticket 041), then working spinner and stop
                    // (ticket 038): screen-level chrome, ordered so the ring doesn't
                    // shift when the spinner appears and disappears. Hidden until the
                    // first /status or live status event delivers the usage.
                    uiState.contextUsage?.let { usage ->
                        ContextUsageRing(usage)
                    }
                    if (uiState.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = "Agent working" },
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        // No stop glyph in material-icons-core: a plain filled square.
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.semantics { contentDescription = "Stop" },
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
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
                    onModelClick = { showModelPicker = true },
                    onTextChange = onInputChange,
                    onSend = onSend,
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
            when {
                uiState.items.isEmpty() && uiState.error != null ->
                    ErrorState(uiState.error, onRetry, enabled = !uiState.isLoading)
                uiState.items.isEmpty() && uiState.isLoading ->
                    LoadingState()
                uiState.items.isEmpty() ->
                    EmptyState("No messages yet")
                else -> MessageList(
                    uiState.items,
                    Modifier.weight(1f),
                    onImageClick = onImageClick,
                    onOpenAttachment = onOpenAttachment,
                    loadImageBytes = loadImageBytes,
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
                isLoading = false,
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
            sessionId = "session_86df049a-e22b-480e-842e-488b77c5469b",
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
