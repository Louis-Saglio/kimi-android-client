package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.Workspace
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.common.icons.ArchiveIcon
import fr.louissaglio.kimi.android.ui.common.icons.ForkIcon
import fr.louissaglio.kimi.android.ui.common.icons.SearchIcon
import fr.louissaglio.kimi.android.ui.common.icons.WorkspaceIcon
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import fr.louissaglio.kimi.android.ui.theme.warningColor
import java.time.Instant

@Composable
fun SessionsScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToChat: (Session) -> Unit = {},
    onNavigateToArchived: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    /** Nav-result flag (ticket 132): true when a restore on the archived screen is
     * pending the main list's heal refresh. Consumed into a silentRefresh, then reset. */
    restoredFromArchive: Boolean = false,
    onRestoredFromArchiveConsumed: () -> Unit = {},
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The archived screen's restore pushes no WS event, and the socket's
    // ON_STOP/ON_START cycle does not heal it (a fresh collection's first connect
    // emits no Reconnected), so the nav-result flag turns into the same silent
    // re-sync a real reconnect runs.
    LaunchedEffect(restoredFromArchive) {
        if (restoredFromArchive) {
            viewModel.onRestoreFromArchived()
            onRestoredFromArchiveConsumed()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SessionListViewModel.Effect.RefreshFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is SessionListViewModel.Effect.CreateFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is SessionListViewModel.Effect.SessionCreated ->
                    onNavigateToChat(effect.session)
                // Ticket 128: the forked row is already inserted; the user stays in
                // the list and gets a confirmation (failures carry the server's message).
                is SessionListViewModel.Effect.SessionForked ->
                    snackbarHostState.showSnackbar("Session forked")
                is SessionListViewModel.Effect.ForkFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                // Ticket 131: no confirmation dialog on archive — the row is already
                // gone; the undo snackbar is the safety net (the app's first undo
                // precedent). An action snackbar gets the Long duration so the Undo
                // tap stays reachable.
                is SessionListViewModel.Effect.SessionArchived -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Session archived",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onUndoArchive(effect.sessionId)
                }
                is SessionListViewModel.Effect.ArchiveFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is SessionListViewModel.Effect.RestoreFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                // Ticket 151: delete has no undo — a failure is the only snackbar the
                // flow emits (success and already-gone 40401 just drop the row).
                is SessionListViewModel.Effect.DeleteFailed ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }
    // Fires on first composition and whenever the screen becomes visible again
    // (back from chat/settings, app foregrounded).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    // The live-status socket (ticket 087) only runs while the list is visible:
    // chat open or app backgrounded → ON_STOP closes it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    SessionsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToArchived = onNavigateToArchived,
        onNavigateToSearch = onNavigateToSearch,
        onSessionClick = { session ->
            viewModel.clearUnread(session.id)
            onNavigateToChat(session)
        },
        onRetry = viewModel::onRetry,
        onPullToRefresh = viewModel::onPullToRefresh,
        onEndReached = viewModel::onEndReached,
        onViewModeToggle = viewModel::onViewModeToggle,
        onGroupShowMore = viewModel::onGroupShowMore,
        onGroupShowLess = viewModel::onGroupShowLess,
        onGroupFoldToggle = viewModel::onGroupFoldToggle,
        onNewSessionClick = viewModel::onNewSessionClick,
        onNewSessionCwdChange = viewModel::onNewSessionCwdChange,
        onNewSessionTitleChange = viewModel::onNewSessionTitleChange,
        onCreateDialogDismiss = viewModel::onCreateDialogDismiss,
        onCreateSessionConfirm = viewModel::onCreateSessionConfirm,
        onForkSession = viewModel::onForkSession,
        onArchiveSession = viewModel::onArchiveSession,
        onDeleteSession = viewModel::onDeleteSession,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsContent(
    uiState: SessionListViewModel.UiState,
    snackbarHostState: SnackbarHostState,
    onNavigateToSettings: () -> Unit,
    onNavigateToArchived: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onSessionClick: (Session) -> Unit,
    onRetry: () -> Unit,
    onPullToRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onViewModeToggle: () -> Unit,
    onGroupShowMore: (String) -> Unit,
    onGroupShowLess: (String) -> Unit,
    onGroupFoldToggle: (String) -> Unit,
    onNewSessionClick: (String?) -> Unit,
    onNewSessionCwdChange: (String) -> Unit,
    onNewSessionTitleChange: (String) -> Unit,
    onCreateDialogDismiss: () -> Unit,
    onCreateSessionConfirm: () -> Unit,
    onForkSession: (Session) -> Unit,
    onArchiveSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kimi Code") },
                actions = {
                    // Global message search (ticket 146).
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(SearchIcon, contentDescription = "Search messages")
                    }
                    // Flat ↔ grouped toggle (ticket 115): the icon is the mode a tap
                    // switches TO — a list glyph while grouped, the workspace folder
                    // while flat. The icon carries the state; the tint stays neutral.
                    IconButton(onClick = onViewModeToggle) {
                        Icon(
                            imageVector =
                                if (uiState.groupedView) Icons.AutoMirrored.Filled.List else WorkspaceIcon,
                            contentDescription =
                                if (uiState.groupedView) "Show flat session list" else "Group sessions by workspace",
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    // Session management lives next to the session list (ticket 132):
                    // the overflow hosts the archived sessions entry; Settings stays
                    // about connection and server config. The chat overflow's idiom.
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Archived sessions") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToArchived()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNewSessionClick(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onPullToRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !uiState.hasContent && uiState.error != null ->
                    // verticalScroll makes the otherwise static state reach pull-to-refresh's nested scroll.
                    ErrorState(
                        uiState.error,
                        onRetry,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                !uiState.hasContent ->
                    LoadingState()
                uiState.groupedView && uiState.groups!!.isEmpty() ->
                    EmptyState("No sessions yet", modifier = Modifier.verticalScroll(rememberScrollState()))
                !uiState.groupedView && uiState.sessions!!.isEmpty() ->
                    EmptyState("No sessions yet", modifier = Modifier.verticalScroll(rememberScrollState()))
                uiState.groupedView -> GroupedSessionList(
                    uiState,
                    onSessionClick,
                    onGroupShowMore,
                    onGroupShowLess,
                    onGroupFoldToggle,
                    onNewSessionClick,
                    onForkSession,
                    onArchiveSession,
                    onDeleteSession,
                )
                else -> SessionList(uiState, onSessionClick, onEndReached, onForkSession, onArchiveSession, onDeleteSession)
            }
        }
        if (uiState.showCreateDialog) {
            CreateSessionDialog(
                uiState = uiState,
                onCwdChange = onNewSessionCwdChange,
                onTitleChange = onNewSessionTitleChange,
                onDismiss = onCreateDialogDismiss,
                onConfirm = onCreateSessionConfirm,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSessionDialog(
    uiState: SessionListViewModel.UiState,
    onCwdChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val workspaces = uiState.workspaces
                if (workspaces == null) {
                    OutlinedTextField(
                        value = uiState.newSessionCwd,
                        onValueChange = onCwdChange,
                        label = { Text("Working directory") },
                        isError = uiState.newSessionCwdError != null,
                        supportingText = { uiState.newSessionCwdError?.let { Text(it) } },
                        singleLine = true,
                    )
                } else {
                    // The field stays freely editable; the dropdown only offers the
                    // known workspaces as shortcuts.
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = uiState.newSessionCwd,
                            onValueChange = onCwdChange,
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                            label = { Text("Working directory") },
                            isError = uiState.newSessionCwdError != null,
                            supportingText = { uiState.newSessionCwdError?.let { Text(it) } },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            workspaces.forEach { workspace ->
                                DropdownMenuItem(
                                    text = { WorkspaceMenuItem(workspace) },
                                    onClick = {
                                        onCwdChange(workspace.root)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = uiState.newSessionTitle,
                    onValueChange = onTitleChange,
                    label = { Text("Title (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !uiState.isCreating) {
                if (uiState.isCreating) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uiState.isCreating) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun WorkspaceMenuItem(workspace: Workspace) {
    Column {
        // Empty names fall back to the root for the label (observed live: root "/").
        Text(
            text = workspace.name.ifBlank { workspace.root },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = workspace.root,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionList(
    uiState: SessionListViewModel.UiState,
    onSessionClick: (Session) -> Unit,
    onEndReached: () -> Unit,
    onForkSession: (Session) -> Unit,
    onArchiveSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val sessions = uiState.sessions.orEmpty()
    val listState = rememberLazyListState()
    // Fires when scrolling within a few rows of the end; the ViewModel no-ops unless
    // another page is actually available, so repeated triggers near the end are cheap.
    val endReached by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisibleIndex >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(endReached) { if (endReached) onEndReached() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(sessions, key = Session::id, contentType = { it::class }) { session ->
            SessionRow(
                session,
                workspaceLabel = session.cwd?.let(uiState.workspaceLabels::get),
                hasUnread = session.id in uiState.unreadIds,
                onClick = { onSessionClick(session) },
                onFork = { onForkSession(session) },
                onArchive = { onArchiveSession(session.id) },
                onDelete = { onDeleteSession(session.id) },
            )
        }
        if (uiState.isLoadingMore || uiState.loadMoreError != null) {
            item(key = "load_more_footer", contentType = "footer") {
                LoadMoreFooter(error = uiState.loadMoreError, onRetry = onEndReached)
            }
        }
    }
}

/**
 * The grouped view (ticket 115): one header per workspace group — its 114 label plus
 * the group's full server-side count — followed by the group's rows, indented to read
 * as the header's children. A group holding fewer rows than its total gets a footer
 * (ticket 116) that pages the rest of the workspace five at a time; once rows beyond
 * the first page are on screen the footer also offers show less. Tapping the header
 * folds the group away, leaving only the header.
 */
@Composable
private fun GroupedSessionList(
    uiState: SessionListViewModel.UiState,
    onSessionClick: (Session) -> Unit,
    onGroupShowMore: (String) -> Unit,
    onGroupShowLess: (String) -> Unit,
    onGroupFoldToggle: (String) -> Unit,
    onNewSessionClick: (String?) -> Unit,
    onForkSession: (Session) -> Unit,
    onArchiveSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        uiState.groups.orEmpty().forEach { group ->
            val folded = group.workspaceId in uiState.foldedGroupIds
            item(key = "header_${group.workspaceId}", contentType = "header") {
                GroupHeader(
                    label = uiState.groupLabels[group.cwd] ?: group.cwd,
                    total = group.total,
                    folded = folded,
                    onToggleFold = { onGroupFoldToggle(group.workspaceId) },
                    onNewSession = { onNewSessionClick(group.cwd) },
                )
            }
            if (!folded) {
                items(group.sessions, key = Session::id, contentType = { it::class }) { session ->
                    // The header is the workspace label, so the row keeps the bare date.
                    SessionRow(
                        session,
                        workspaceLabel = null,
                        hasUnread = session.id in uiState.unreadIds,
                        onClick = { onSessionClick(session) },
                        onFork = { onForkSession(session) },
                        onArchive = { onArchiveSession(session.id) },
                        onDelete = { onDeleteSession(session.id) },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                // The grouped fetch serves only the first 5 sessions per workspace; the
                // footer's show more pages the rest. Show less appears once expanded
                // rows are on screen and stays after the group is fully loaded.
                val canShowMore = group.sessions.size < group.total
                val canShowLess = group.sessions.size > SessionListViewModel.GROUP_PAGE_SIZE
                if (canShowMore || canShowLess) {
                    item(key = "footer_${group.workspaceId}", contentType = "footer") {
                        val expansion = uiState.groupExpansions[group.workspaceId]
                        GroupFooter(
                            canShowMore = canShowMore,
                            isLoading = expansion?.isLoading == true,
                            error = expansion?.error,
                            onShowMore = { onGroupShowMore(group.workspaceId) },
                            onShowLess = if (canShowLess) ({ onGroupShowLess(group.workspaceId) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A workspace group's header: the workspace icon and label (plain text color —
 * primary read as a link), the group's total, a new-session button pre-filling the
 * dialog with this workspace, and a fold chevron. The row itself is the fold/unfold
 * tap target.
 */
@Composable
private fun GroupHeader(
    label: String,
    total: Int,
    folded: Boolean,
    onToggleFold: () -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleFold)
            .padding(start = 16.dp, top = 8.dp, end = 4.dp),
    ) {
        Icon(WorkspaceIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = "$label ($total)",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "New session in $label")
        }
        Icon(
            imageVector = if (folded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (folded) "Unfold $label" else "Fold $label",
        )
    }
}

@Composable
internal fun LoadMoreFooter(error: String?, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error == null) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/**
 * A group's footer (ticket 116), mirroring the flat list's [LoadMoreFooter]: spinner
 * while the next page loads, error with retry when the last attempt failed, the
 * show-more button while the group has more sessions server-side. [onShowLess] is
 * non-null once rows beyond the first page are on screen — show less hides the last
 * five and survives the group being fully loaded.
 */
@Composable
private fun GroupFooter(
    canShowMore: Boolean,
    isLoading: Boolean,
    error: String?,
    onShowMore: () -> Unit,
    onShowLess: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isLoading -> CircularProgressIndicator(Modifier.size(24.dp))
            error != null -> {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onShowMore) { Text("Retry") }
            }
            canShowMore -> TextButton(onClick = onShowMore) { Text("Show more") }
        }
        if (onShowLess != null) {
            TextButton(onClick = onShowLess) { Text("Show less") }
        }
    }
}

@Composable
internal fun SessionRow(
    session: Session,
    workspaceLabel: String?,
    hasUnread: Boolean,
    onClick: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row ellipsizes the name to one line; long-press reveals it whole (ticket
    // 117) and offers the fork (ticket 128), archive (ticket 131) and delete
    // (ticket 151) actions.
    var fullNameShown by remember { mutableStateOf(false) }
    // Delete is the one action that asks again (ticket 151): archive has its undo
    // snackbar, deletion has none server-side.
    var deleteConfirmShown by remember { mutableStateOf(false) }
    if (fullNameShown) {
        SessionFullNameDialog(
            session = session,
            onFork = onFork,
            onArchive = onArchive,
            onDelete = { deleteConfirmShown = true },
            onDismiss = { fullNameShown = false },
        )
    }
    if (deleteConfirmShown) {
        DeleteSessionConfirmDialog(
            session = session,
            onConfirm = {
                deleteConfirmShown = false
                onDelete()
            },
            onDismiss = { deleteConfirmShown = false },
        )
    }
    ListItem(
        headlineContent = {
            Text(
                text = session.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // "<label> · <date>" when the session's cwd yields a workspace label
            // (ticket 114), the bare date when it has none.
            Text(
                text = listOfNotNull(workspaceLabel, formatTimestamp(session.updatedAt)).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            // Web parity (ticket 145): running is a spinner; the blue dot is the
            // web's unread marker — a turn finished that the user hasn't opened yet.
            when {
                // A pending interaction reads as "needs me" and wins over "working": a
                // session blocked on an approval or question is not making progress.
                session.isAwaitingInteraction -> NeedsAttentionBadge()
                // The spinner wins while running; the interrupted badge (ticket 159)
                // shows on an idle row only, ahead of the unread dot (web order).
                session.isRunning -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .semantics { contentDescription = "Session running" },
                    strokeWidth = 2.dp,
                )
                session.isLastTurnInterrupted -> InterruptedBadge()
                hasUnread -> Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "Session has unread activity" },
                )
            }
        },
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { fullNameShown = true },
        ),
    )
}

/**
 * The long-press row dialog (ticket 117): the full selectable name, then one action
 * row per session action — Fork (ticket 128), Archive (ticket 131, the undo
 * snackbar's entry point), Delete (ticket 151, the destructive one — it swaps to a
 * confirmation dialog instead of firing directly). Actions dismiss the dialog
 * before firing.
 */
@Composable
private fun SessionFullNameDialog(
    session: Session,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                // Selectable so the name can be copied.
                SelectionContainer {
                    // displayName's fallback minus the basename shortening: the title
                    // when present, otherwise the full cwd path, the id last.
                    Text(session.title?.takeIf { it.isNotBlank() } ?: session.cwd ?: session.id)
                }
                // Forking a running session always fails server-side (40901 — ticket
                // 127), so the action pre-disables, like the chat overflow item.
                // Archive stays enabled: the server has no busy guard (verified live
                // 2026-09-14 on 0.42.0 — ticket 131).
                DialogActionRow(
                    icon = ForkIcon,
                    label = "Fork session",
                    enabled = !session.isRunning,
                    onClick = {
                        onDismiss()
                        onFork()
                    },
                )
                DialogActionRow(
                    icon = ArchiveIcon,
                    label = "Archive",
                    onClick = {
                        onDismiss()
                        onArchive()
                    },
                )
                // Delete stays enabled too: a running session's work is drained
                // server-side first (docs/server-api.md — the call may take a moment).
                DialogActionRow(
                    icon = Icons.Filled.Delete,
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        onDismiss()
                        onDelete()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Delete's second step (ticket 151): deletion is final — the server has no restore
 * and the app offers no undo — so unlike archive the action confirms first. The
 * confirm button carries the destructive error color (the job screen's Stop
 * precedent).
 */
@Composable
private fun DeleteSessionConfirmDialog(
    session: Session,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("Delete session \"${session.displayName}\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DialogActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    // onSurface at 38% is the M3 disabled content color; destructive actions take
    // the scheme's error color.
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> LocalContentColor.current
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, color = color)
    }
}

@Composable
private fun NeedsAttentionBadge() {
    Badge(modifier = Modifier.semantics { contentDescription = "Needs attention" }) {
        Text("!")
    }
}

/**
 * The interrupted-last-turn badge (ticket 159): a small warning-toned glyph meaning the
 * last turn did not complete. A triage hint on a crowded row, not an alarm — icon-only,
 * the 135 amber so it never reads as an error.
 */
@Composable
private fun InterruptedBadge() {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = "Last turn did not complete",
        tint = MaterialTheme.warningColor,
        modifier = Modifier.size(14.dp),
    )
}

/** How close (in rows) to the list end the scroll must get before the next page loads. */
internal const val LOAD_MORE_THRESHOLD = 3

@Preview
@Composable
private fun SessionsContentPreview() {
    KimiTheme {
        SessionsContent(
            uiState = SessionListViewModel.UiState(
                sessions = listOf(
                    Session("session_1", "Build an Android clone", "/home/ubuntu/kimi-android-client", Instant.now(), isRunning = true, isAwaitingInteraction = true),
                    Session("session_2", null, "/tmp/spike", Instant.now(), isRunning = false, isAwaitingInteraction = false),
                    Session("session_3", "Nightly protocol drift check", "/home/ubuntu/kimi-android-client", Instant.now(), isRunning = true, isAwaitingInteraction = false),
                    Session("session_4", "Aborted migration run", "/tmp/spike", Instant.now(), isRunning = false, isAwaitingInteraction = false, isLastTurnInterrupted = true),
                ),
                unreadIds = setOf("session_2"),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateToSettings = {},
            onNavigateToArchived = {},
            onNavigateToSearch = {},
            onSessionClick = {},
            onRetry = {},
            onPullToRefresh = {},
            onEndReached = {},
            onViewModeToggle = {},
            onGroupShowMore = {},
            onGroupShowLess = {},
            onGroupFoldToggle = {},
            onNewSessionClick = {},
            onNewSessionCwdChange = {},
            onNewSessionTitleChange = {},
            onCreateDialogDismiss = {},
            onCreateSessionConfirm = {},
            onForkSession = {},
            onArchiveSession = {},
            onDeleteSession = {},
        )
    }
}

@Preview
@Composable
private fun CreateSessionDialogPreview() {
    KimiTheme {
        CreateSessionDialog(
            uiState = SessionListViewModel.UiState(
                showCreateDialog = true,
                newSessionCwd = "/home/ubuntu",
                newSessionCwdError = "Path must be absolute",
            ),
            onCwdChange = {},
            onTitleChange = {},
            onDismiss = {},
            onConfirm = {},
        )
    }
}
