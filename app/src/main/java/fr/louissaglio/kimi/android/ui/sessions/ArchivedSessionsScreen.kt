package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.sessions.ArchivedSession
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.workspaceFolder
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.common.icons.RestoreIcon

/**
 * The archived sessions screen (ticket 132): a read-only list with a per-row Restore
 * action. Row tap and long-press deliberately do nothing — opening an archived
 * session's chat is deferred until the chat screen handles archived sessions.
 * [onSessionRestored] is the nav-result flag setter for the main list's return heal.
 */
@Composable
fun ArchivedSessionsScreen(
    onNavigateBack: () -> Unit = {},
    onSessionRestored: () -> Unit = {},
    viewModel: ArchivedSessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // The row is already gone; the snackbar is plain (no undo — re-archiving
                // is one gesture away in the main list).
                ArchivedSessionsViewModel.Effect.SessionRestored -> {
                    onSessionRestored()
                    snackbarHostState.showSnackbar("Session restored")
                }
                is ArchivedSessionsViewModel.Effect.RefreshFailed ->
                    snackbarHostState.showSnackbar(effect.message)
                is ArchivedSessionsViewModel.Effect.RestoreFailed ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }
    ArchivedSessionsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::onRetry,
        onPullToRefresh = viewModel::onPullToRefresh,
        onEndReached = viewModel::onEndReached,
        onRestore = viewModel::onRestoreSession,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedSessionsContent(
    uiState: ArchivedSessionsViewModel.UiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onPullToRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onRestore: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
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
                uiState.sessions == null && uiState.error != null ->
                    // verticalScroll makes the otherwise static state reach pull-to-refresh's nested scroll.
                    ErrorState(
                        uiState.error,
                        onRetry,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                uiState.sessions == null ->
                    LoadingState()
                uiState.sessions!!.isEmpty() ->
                    EmptyState("No archived sessions", modifier = Modifier.verticalScroll(rememberScrollState()))
                else -> ArchivedSessionList(uiState, onEndReached, onRestore)
            }
        }
    }
}

@Composable
private fun ArchivedSessionList(
    uiState: ArchivedSessionsViewModel.UiState,
    onEndReached: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val sessions = uiState.sessions.orEmpty()
    val listState = rememberLazyListState()
    // The main list's end-reached trigger: fires within a few rows of the end; the
    // ViewModel no-ops unless another page is actually available.
    val endReached by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisibleIndex >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(endReached) { if (endReached) onEndReached() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(sessions, key = ArchivedSession::id, contentType = { it::class }) { session ->
            ArchivedSessionRow(session, onRestore = { onRestore(session.id) })
        }
        if (uiState.isLoadingMore || uiState.loadMoreError != null) {
            item(key = "load_more_footer", contentType = "footer") {
                LoadMoreFooter(error = uiState.loadMoreError, onRetry = onEndReached)
            }
        }
    }
}

/**
 * One archived row: the title with the main list's fallback chain, then
 * "<workspace folder> · Archived <time>" (archived_at, updated_at when absent), and
 * the trailing Restore button. No click or long-press handling — read-only.
 */
@Composable
private fun ArchivedSessionRow(session: ArchivedSession, onRestore: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = session.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    session.workspaceFolder,
                    "Archived ${formatTimestamp(session.archiveTime)}",
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onRestore) {
                Icon(RestoreIcon, contentDescription = "Restore")
            }
        },
    )
}
