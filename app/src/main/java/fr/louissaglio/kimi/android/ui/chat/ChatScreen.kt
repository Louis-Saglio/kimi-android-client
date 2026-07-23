package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import kotlinx.coroutines.delay

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
        onSubmitQuestion = viewModel::onSubmitQuestion,
        onDismissQuestion = viewModel::onDismissQuestion,
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
    onSubmitQuestion: () -> Unit,
    onDismissQuestion: () -> Unit,
) {
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
                        selections = uiState.questionSelections,
                        otherTexts = uiState.questionOtherTexts,
                        isSubmittable = uiState.isQuestionSubmittable,
                        onOptionPicked = onQuestionOptionPicked,
                        onOtherTextChange = onQuestionOtherTextChange,
                        onSubmit = onSubmitQuestion,
                        onDismiss = onDismissQuestion,
                    )
                }
                ChatInputBar(
                    text = uiState.inputText,
                    isSending = uiState.isSending,
                    isWorking = uiState.isWorking,
                    onTextChange = onInputChange,
                    onSend = onSend,
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

@Composable
private fun QuestionCard(
    question: PendingQuestion,
    queueSize: Int,
    selections: Map<String, String>,
    otherTexts: Map<String, String>,
    isSubmittable: Boolean,
    onOptionPicked: (itemId: String, optionId: String) -> Unit,
    onOtherTextChange: (itemId: String, text: String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            question.items.forEach { item ->
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
                Button(onClick = onSubmit, enabled = isSubmittable) { Text("Submit") }
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
    // The composer stays live mid-turn: while the agent works, the send action steers the
    // message into the running turn instead of starting a new prompt (docs/server-api.md,
    // Steer) — the button swaps to a Steer label so the user knows which one will fire.
    val canSend = text.isNotBlank() && !isSending
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
internal fun UserBubble(message: ChatItem.UserMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            MarkdownBody(
                text = message.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

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
                        ),
                    ),
                ),
                questionSelections = mapOf("q_0" to "opt_0_1"),
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
            onSubmitQuestion = {},
            onDismissQuestion = {},
        )
    }
}
