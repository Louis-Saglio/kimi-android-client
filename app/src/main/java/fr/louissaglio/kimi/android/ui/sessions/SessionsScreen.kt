package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SessionsScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToChat: (String) -> Unit = {},
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SessionListViewModel.Effect.RefreshFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is SessionListViewModel.Effect.CreateFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is SessionListViewModel.Effect.SessionCreated ->
                    onNavigateToChat(effect.sessionId)
            }
        }
    }
    // Fires on first composition and whenever the screen becomes visible again
    // (back from chat/settings, app foregrounded).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    SessionsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateToSettings = onNavigateToSettings,
        onSessionClick = onNavigateToChat,
        onRetry = viewModel::onRetry,
        onPullToRefresh = viewModel::onPullToRefresh,
        onEndReached = viewModel::onEndReached,
        onNewSessionClick = viewModel::onNewSessionClick,
        onNewSessionCwdChange = viewModel::onNewSessionCwdChange,
        onNewSessionTitleChange = viewModel::onNewSessionTitleChange,
        onCreateDialogDismiss = viewModel::onCreateDialogDismiss,
        onCreateSessionConfirm = viewModel::onCreateSessionConfirm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsContent(
    uiState: SessionListViewModel.UiState,
    snackbarHostState: SnackbarHostState,
    onNavigateToSettings: () -> Unit,
    onSessionClick: (String) -> Unit,
    onRetry: () -> Unit,
    onPullToRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onNewSessionClick: () -> Unit,
    onNewSessionCwdChange: (String) -> Unit,
    onNewSessionTitleChange: (String) -> Unit,
    onCreateDialogDismiss: () -> Unit,
    onCreateSessionConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kimi Code") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewSessionClick) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val pullRefreshState = rememberPullToRefreshState()
        if (pullRefreshState.isRefreshing) {
            LaunchedEffect(true) { onPullToRefresh() }
        }
        LaunchedEffect(uiState.isRefreshing) {
            if (!uiState.isRefreshing) pullRefreshState.endRefresh()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
            when {
                uiState.sessions == null && uiState.error != null ->
                    ErrorState(uiState.error, onRetry, enabled = !uiState.isLoading)
                uiState.sessions == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.sessions.isEmpty() -> EmptyState()
                else -> SessionList(uiState, onSessionClick, onEndReached)
            }
            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
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
                            modifier = Modifier.menuAnchor(),
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
    onSessionClick: (String) -> Unit,
    onEndReached: () -> Unit,
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
        items(sessions, key = Session::id) { session ->
            SessionRow(session, onClick = { onSessionClick(session.id) })
        }
        if (uiState.isLoadingMore || uiState.loadMoreError != null) {
            item(key = "load_more_footer") {
                LoadMoreFooter(error = uiState.loadMoreError, onRetry = onEndReached)
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(error: String?, onRetry: () -> Unit) {
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

@Composable
internal fun SessionRow(session: Session, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = session.title ?: session.cwd ?: session.id,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(formatTimestamp(session.updatedAt)) },
        trailingContent = {
            when {
                // Approval-pending reads as "needs me" and wins over "working": a session
                // blocked on an approval is not making progress.
                session.isAwaitingApproval -> ApprovalBadge()
                session.isRunning -> Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "Session running" },
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ApprovalBadge() {
    Badge(modifier = Modifier.semantics { contentDescription = "Approval needed" }) {
        Text("!")
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, enabled: Boolean) {
    // verticalScroll makes the otherwise static state reach pull-to-refresh's nested scroll.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, enabled = enabled) {
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "No sessions yet", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatTimestamp(instant: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(instant)

/** How close (in rows) to the list end the scroll must get before the next page loads. */
private const val LOAD_MORE_THRESHOLD = 3

@Preview
@Composable
private fun SessionsContentPreview() {
    KimiTheme {
        SessionsContent(
            uiState = SessionListViewModel.UiState(
                sessions = listOf(
                    Session("session_1", "Build an Android clone", "/home/ubuntu/kimi-android-client", Instant.now(), isRunning = true, isAwaitingApproval = true),
                    Session("session_2", null, "/tmp/spike", Instant.now(), isRunning = false, isAwaitingApproval = false),
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateToSettings = {},
            onSessionClick = {},
            onRetry = {},
            onPullToRefresh = {},
            onEndReached = {},
            onNewSessionClick = {},
            onNewSessionCwdChange = {},
            onNewSessionTitleChange = {},
            onCreateDialogDismiss = {},
            onCreateSessionConfirm = {},
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
