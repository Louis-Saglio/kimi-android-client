package fr.louissaglio.kimi.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // Document/photo picker for composer attachments (ticket 033): the Uri is resolved
    // eagerly into name/size/mime/bytes so chips can render and the send path uploads.
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val resolved = withContext(Dispatchers.IO) { resolveAttachment(context.contentResolver, uri) }
                if (resolved != null) {
                    viewModel.onAttachmentPicked(resolved.name, resolved.sizeBytes, resolved.mimeType, resolved.bytes)
                } else {
                    snackbarHostState.showSnackbar("Could not read the picked file")
                }
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
                    // Model picker (ticket 031): hidden while the models list is loading
                    // or after a failed fetch — sending never depends on it.
                    uiState.currentModelLabel?.let { label ->
                        AssistChip(
                            onClick = { showModelPicker = true },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.semantics { contentDescription = "Model: $label" },
                        )
                        Spacer(Modifier.width(4.dp))
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
                                    .clip(RoundedCornerShape(3.dp))
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
                ChatInputBar(
                    text = uiState.inputText,
                    isSending = uiState.isSending,
                    isWorking = uiState.isWorking,
                    attachments = uiState.attachments,
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No messages yet", style = MaterialTheme.typography.bodyLarge)
                    }
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
}

/** Model picker bottom sheet (ticket 031): every server model with its context size,
 * a "Default" label on the server-wide default, and a check on the effective one
 * (the session's choice, else the default). Below it, the thinking-effort section
 * (ticket 032) lists the current model's efforts — hidden entirely for models without
 * `support_efforts` — with a check on the stored choice, or a "Default" hint on the
 * model's `default_effort` when nothing is stored. A tap persists and dismisses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    uiState: ChatViewModel.UiState,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentModelId = uiState.sessionModel ?: uiState.serverDefaultModel
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            uiState.availableModels.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            onModelSelected(model.model)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = model.displayName.ifBlank { model.model },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (model.model == uiState.serverDefaultModel) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = formatContextSize(model.maxContextSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.model == currentModelId) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (uiState.supportedEfforts.isNotEmpty()) {
                Text(
                    text = "Thinking effort",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.supportedEfforts.forEach { effort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onEffortSelected(effort)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = effort,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (uiState.selectedEffort == null && effort == uiState.defaultEffort) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (effort == uiState.selectedEffort) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Context window for the picker rows: "1M context", "262k context". */
private fun formatContextSize(size: Long): String = when {
    size >= 1_000_000 -> "${size / 1_000_000}M context"
    size >= 1_000 -> "${size / 1_000}k context"
    else -> "$size tokens"
}

/** Near-bottom slop: a fling that stops a few pixels above the end still counts
 * as "at the bottom" for auto-scroll purposes (ticket 030). */
private val AutoScrollThreshold = 32.dp

@Composable
internal fun MessageList(
    items: List<ChatItem>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onImageClick: (ChatMessage.Part.Image) -> Unit = {},
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit = {},
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { null },
) {
    // reverseLayout alone does NOT pin the viewport to the newest row: with stable
    // keys, LazyList re-anchors the scroll position to the key of the first visible
    // item, so any tail insertion (tool row, new bubble) silently moves the anchor
    // off the bottom and the follow stops (ticket 030). Track at-bottom continuously
    // and re-pin on every insertion; growth of the bottom item needs no handling
    // (a (0,0) pin survives it), and a scrolled-up reader is never yanked.
    val thresholdPx = with(LocalDensity.current) { AutoScrollThreshold.toPx() }
    var isAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= thresholdPx
        }.collect { isAtBottom = it }
    }
    LaunchedEffect(items.size) {
        // Reads the pre-insertion value: this effect runs after composition apply,
        // before the layout pass that would move firstVisibleItemIndex off 0.
        if (isAtBottom) listState.scrollToItem(0)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.asReversed(), key = ChatItem::id) { item ->
            when (item) {
                is ChatItem.UserMessage ->
                    UserBubble(
                        item,
                        onImageClick = onImageClick,
                        onOpenAttachment = onOpenAttachment,
                        loadImageBytes = loadImageBytes,
                    )
                is ChatItem.AssistantMessage -> AssistantBubble(item)
                is ChatItem.ToolActivity -> ToolRow(item)
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ChatViewModel.PendingApproval,
    queueSize: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("Permission request")
                    if (queueSize > 1) append(" — 1 of $queueSize")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(text = approval.toolName, style = MaterialTheme.typography.titleSmall)
            if (approval.summary.isNotBlank()) {
                Text(
                    text = approval.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** One item per step (ticket 035): stacking every item pushed the card past the screen
 * for multi-item questions, clipping the top options. The wizard paginates the items —
 * Back/Next walk them, Submit replaces Next on the last one — and the scrollable,
 * height-capped item area is the safety net for a single item with a long option list. */
@Composable
private fun QuestionCard(
    question: PendingQuestion,
    queueSize: Int,
    step: Int,
    selections: Map<String, String>,
    otherTexts: Map<String, String>,
    isStepAnswered: Boolean,
    isSubmittable: Boolean,
    onOptionPicked: (itemId: String, optionId: String) -> Unit,
    onOtherTextChange: (itemId: String, text: String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val item = question.items.getOrNull(step) ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("Question from Kimi")
                    if (queueSize > 1) append(" — 1 of $queueSize")
                    if (question.items.size > 1) append(" · item ${step + 1} of ${question.items.size}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.header.isNotBlank()) {
                    Text(text = item.header, style = MaterialTheme.typography.labelMedium)
                }
                Text(text = item.text, style = MaterialTheme.typography.titleSmall)
                item.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOptionPicked(item.id, option.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selections[item.id] == option.id,
                            onClick = { onOptionPicked(item.id, option.id) },
                        )
                        Column {
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                            option.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (item.allowOther) {
                    OutlinedTextField(
                        value = otherTexts[item.id].orEmpty(),
                        onValueChange = { onOtherTextChange(item.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Other answer…") },
                        singleLine = true,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                if (step > 0) {
                    OutlinedButton(onClick = onBack) { Text("‹ Back") }
                }
                if (step < question.items.lastIndex) {
                    Button(onClick = onNext, enabled = isStepAnswered) { Text("Next ›") }
                } else {
                    Button(onClick = onSubmit, enabled = isSubmittable) { Text("Submit") }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    isSending: Boolean,
    isWorking: Boolean,
    attachments: List<ChatViewModel.PendingAttachment>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onAttachmentRemoved: (String) -> Unit,
) {
    // The composer stays live mid-turn: while the agent works, the send action steers the
    // message into the running turn instead of starting a new prompt (docs/server-api.md,
    // Steer) — the button swaps to a Steer label so the user knows which one will fire.
    // Attachments alone are sendable (ticket 033); text alone unchanged.
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending
    // imePadding docks the bar above the keyboard (the manifest sets adjustResize).
    Surface(modifier = Modifier.imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachments, key = ChatViewModel.PendingAttachment::localId) { attachment ->
                        AttachmentChip(attachment = attachment, onRemove = { onAttachmentRemoved(attachment.localId) })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = !isSending,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .semantics { contentDescription = "Attach a file" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Message input" },
                    placeholder = { Text(if (isWorking) "Steer the running turn…" else "Message Kimi…") },
                    // Grows with content up to 5 lines, then scrolls internally.
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )
                if (isWorking) {
                    Button(onClick = onSend, enabled = canSend) {
                        Text("Steer")
                    }
                } else {
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
    }
}

/** One staged attachment above the input (ticket 033): a bounded thumbnail for images,
 * a generic sheet glyph otherwise, then name + size. A spinner marks the in-flight
 * upload, an error tint a failed one (the send snackbar's Retry re-tries); the ×
 * removes the chip before the send. */
@Composable
private fun AttachmentChip(
    attachment: ChatViewModel.PendingAttachment,
    onRemove: () -> Unit,
) {
    val failed = attachment.status == ChatViewModel.PendingAttachment.Status.Failed
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentThumbnail(attachment)
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (attachment.status == ChatViewModel.PendingAttachment.Status.Uploading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .semantics { contentDescription = "Uploading ${attachment.name}" },
                    strokeWidth = 1.5.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove ${attachment.name}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Image chips decode a bounded thumbnail off the main thread; other types get the
 * same generic sheet glyph the timeline uses for copy actions. */
@Composable
private fun AttachmentThumbnail(attachment: ChatViewModel.PendingAttachment) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, attachment.bytes) {
        if (attachment.isImage) {
            value = withContext(Dispatchers.Default) { decodeSampledThumbnail(attachment.bytes, THUMBNAIL_PX) }
        }
    }
    val bitmap = thumbnail
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        CopyGlyph(
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            background = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .padding(8.dp),
        )
    }
}

private const val THUMBNAIL_PX = 128

/** Two-pass decode: bounds first to pick a power-of-two sample size, then the pixels —
 * a 12 MP photo never lands in memory at full size for a 36dp chip. */
private fun decodeSampledThumbnail(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** "340 kB", "1.2 MB" — decimal units, like the web client's chip labels. */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Message-body markdown (ticket 020), themed for web parity (ticket 021):
 * inline code as monospace on a rounded chip, fenced code as monospace on a
 * darker inset surface (horizontally scrollable, built into the library's
 * `MarkdownCode`), heading scale capped so a `#`/`##` does not fill the screen,
 * accent-colored underlined links.
 *
 * Streaming cost: the library parses inside composition on every recomposition
 * of its scope (verified in the 0.26.0 source — there is no internal memoize),
 * so the mitigation lives here: this wrapper takes only stable params, which
 * keeps it skippable and bounds re-parsing to one parse per text snapshot.
 */
@Composable
private fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    // Alpha-black so chips/code surfaces read as a subtle darkening of whatever
    // bubble they sit in — a fixed dark color was a hard near-black blob.
    val codeSurface = Color.Black.copy(alpha = 0.3f)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = LocalContentColor.current,
            codeText = LocalContentColor.current,
            linkText = MaterialTheme.colorScheme.primary,
            codeBackground = codeSurface,
            inlineCodeBackground = codeSurface,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineSmall,
            h2 = MaterialTheme.typography.titleLarge,
            h3 = MaterialTheme.typography.titleMedium,
            h4 = MaterialTheme.typography.titleSmall,
            h5 = MaterialTheme.typography.titleSmall,
            h6 = MaterialTheme.typography.titleSmall,
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
        ),
        padding = markdownPadding(indentList = 16.dp),
        // A fresh ExtendedSpans per text node, as the library intends: the class
        // holds per-node draw state, so a shared instance scrambles chip positions
        // across paragraphs.
        extendedSpans = markdownExtendedSpans(
            extendedSpans = { ExtendedSpans(RoundedCornerSpanPainter()) },
        ),
    )
}

@Composable
internal fun UserBubble(
    message: ChatItem.UserMessage,
    onImageClick: (ChatMessage.Part.Image) -> Unit = {},
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit = {},
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { null },
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.text.isNotBlank()) MarkdownBody(text = message.text)
                message.images.forEach { image ->
                    HistoryImageThumbnail(
                        image = image,
                        loadImageBytes = loadImageBytes,
                        onClick = { onImageClick(image) },
                    )
                }
                message.attachments.forEach { attachment ->
                    AttachmentChipRow(attachment = attachment, onClick = { onOpenAttachment(attachment) })
                }
            }
        }
    }
}

/** An image of a history user message (ticket 034): a bounded-decode thumbnail —
 * the bytes come from the inline base64 or, for file-referenced sources, from a
 * /files fetch. A failed resolve falls back to the generic sheet glyph; a tap opens
 * the full-screen viewer. */
@Composable
private fun HistoryImageThumbnail(
    image: ChatMessage.Part.Image,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onClick: () -> Unit,
) {
    var resolved by remember(image) { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, image) {
        val bytes = loadImageBytes(image)
        if (bytes != null) {
            value = withContext(Dispatchers.Default) { decodeSampledThumbnail(bytes, THUMBNAIL_PX) }
        }
        resolved = true
    }
    val bitmap = thumbnail
    val shape = RoundedCornerShape(8.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Attached image",
            modifier = Modifier
                .size(120.dp)
                .clip(shape)
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    } else if (resolved) {
        Box(
            Modifier
                .size(120.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CopyGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        Box(
            Modifier
                .size(120.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** A non-image attachment inside a history bubble (ticket 034): generic sheet glyph,
 * name and size. Tappable only when the fileId was recovered from the notice — the
 * tap downloads the bytes and opens them in another app. */
@Composable
private fun AttachmentChipRow(attachment: ChatMessage.Part.Attachment, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = attachment.fileId != null, onClick = onClick)
            .semantics { contentDescription = "Attachment ${attachment.name}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CopyGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tap-to-dismiss full-screen image viewer (ticket 034): the bytes resolve like the
 * thumbnail's, decoded bounded so a megapixel photo never lands in memory at full size. */
@Composable
private fun FullScreenImageViewer(
    image: ChatMessage.Part.Image,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, image) {
                val bytes = loadImageBytes(image)
                if (bytes != null) {
                    value = withContext(Dispatchers.Default) { decodeSampledThumbnail(bytes, FULL_IMAGE_PX) }
                }
            }
            val resolved = bitmap
            if (resolved != null) {
                Image(
                    bitmap = resolved,
                    contentDescription = "Attached image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

private const val FULL_IMAGE_PX = 2048

/** Web-parity assistant row (ticket 024): full-width plain markdown on the chat
 * background — no bubble — with a small copy action underneath that puts the raw
 * markdown source on the clipboard. Live and final messages share this path, so
 * streaming deltas get the same layout. Thinking stays above the reply, dimmed. */
@Composable
internal fun AssistantBubble(message: ChatItem.AssistantMessage) {
    Column(Modifier.fillMaxWidth()) {
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
            MarkdownBody(text = message.text)
            CopyMessageAction(rawMarkdown = message.text)
        }
    }
}

/** Subtle copy affordance under each assistant message (ticket 024): a small
 * outlined-sheet glyph that swaps to a green check for a moment on tap. */
@Composable
private fun CopyMessageAction(rawMarkdown: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                clipboard.setText(AnnotatedString(rawMarkdown))
                copied = true
            }
            .semantics { contentDescription = "Copy message" },
        contentAlignment = Alignment.Center,
    ) {
        if (copied) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                color = SuccessGreen,
            )
        } else {
            CopyGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** material-icons-core has no copy icon: two overlapping outlined sheets, the
 * front one filled with the chat background so the strokes do not cross. */
@Composable
private fun CopyGlyph(tint: Color, background: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.2.dp.toPx())
        val radius = CornerRadius(2.dp.toPx())
        val sheet = Size(size.width * 0.62f, size.height * 0.62f)
        // Back sheet, top-right.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width - sheet.width, 0f),
            size = sheet,
            cornerRadius = radius,
            style = stroke,
        )
        // Front sheet, bottom-left, filled to punch out the back sheet's stroke.
        drawRoundRect(
            color = background,
            topLeft = Offset(0f, size.height - sheet.height),
            size = sheet,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, size.height - sheet.height),
            size = sheet,
            cornerRadius = radius,
            style = stroke,
        )
    }
}

/** Web-style compact tool row (ticket 022): leading tool icon, name, one-line
 * monospace preview of the input with end-ellipsis, and the status as a glyph —
 * spinner while running, green check on success, red cross on error.
 *
 * Tapping a row that has details expands it (ticket 023, chevron rotates per web):
 * the full call input as pretty-printed JSON, then the result output — monospace,
 * preformatted, in a capped-height region that scrolls internally so a huge test
 * log does not tunnel the timeline. Error output sits on an error-tinted surface. */
@Composable
internal fun ToolRow(activity: ChatItem.ToolActivity) {
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val expandable = activity.input.isNotBlank() || activity.output.isNotBlank()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // M3: the whole row is the tap target, at least 48dp tall.
                .heightIn(min = 48.dp)
                .clickable(enabled = expandable) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Terminal glyph for shell calls (material-icons-core has no terminal icon),
            // a generic tool icon otherwise.
            if (activity.name == "Bash") {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = detailColor,
                )
            }
            Text(
                text = activity.name.ifBlank { "tool" },
                style = MaterialTheme.typography.labelMedium,
                color = detailColor,
            )
            if (activity.preview.isNotBlank()) {
                Text(
                    text = activity.preview,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            when (activity.status) {
                ToolStatus.Running -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(12.dp)
                        .semantics { contentDescription = "Tool running" },
                    strokeWidth = 1.5.dp,
                )
                // No success slot in the M3 scheme: a literal green, readable on both themes.
                ToolStatus.Done -> Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = SuccessGreen,
                )
                ToolStatus.Error -> Text(
                    text = "✗",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (expandable) {
                val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                    tint = detailColor,
                )
            }
        }
        if (expanded && expandable) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (activity.input.isNotBlank()) {
                    ToolDetailBlock(text = activity.input, color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (activity.output.isNotBlank()) {
                    ToolDetailBlock(
                        text = activity.output,
                        color = if (activity.status == ToolStatus.Error) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        // Live tool.progress chunks (ticket 025): keep the tail in view.
                        pinnedToBottom = activity.status == ToolStatus.Running,
                    )
                }
            }
        }
    }
}

/** One preformatted monospace block of the expanded tool row. The height is capped
 * and scrolls internally, so a very long output never stretches the timeline.
 * [pinnedToBottom] (a running tool's live output, ticket 025) re-scrolls to the tail
 * every time the text grows. */
@Composable
private fun ToolDetailBlock(text: String, color: Color, pinnedToBottom: Boolean = false) {
    val scrollState = rememberScrollState()
    if (pinnedToBottom) {
        LaunchedEffect(text) { scrollState.scrollTo(scrollState.maxValue) }
    }
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private val SuccessGreen = Color(0xFF4CAF50)

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
                    ChatItem.UserMessage("msg_1", "Why did the build fail? Reply **briefly**."),
                    ChatItem.AssistantMessage(
                        "msg_2",
                        "### Root cause\n\nMissing `Content-Type` header in `PostRequest`.\n\n" +
                            "- Add the header\n- Retry the call\n\n" +
                            "1. First check logs\n2. Then retry\n\n" +
                            "See [the docs](https://example.com/docs) for details.\n\n" +
                            "```\ncurl -X POST /retry\n```",
                        thinking = "Let me look at the failure output.",
                    ),
                    ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, preview = "curl -X POST /retry"),
                    ChatItem.AssistantMessage("live_0", "Streaming reply…", thinking = ""),
                    ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Running, preview = "app/build.gradle.kts"),
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
