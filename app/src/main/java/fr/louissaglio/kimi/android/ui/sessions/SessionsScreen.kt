package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
                else -> SessionList(uiState.sessions, onSessionClick)
            }
            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun SessionList(sessions: List<Session>, onSessionClick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(sessions, key = Session::id) { session ->
            SessionRow(session, onClick = { onSessionClick(session.id) })
        }
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
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
            if (session.isRunning) {
                Box(
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

@Preview
@Composable
private fun SessionsContentPreview() {
    KimiTheme {
        SessionsContent(
            uiState = SessionListViewModel.UiState(
                sessions = listOf(
                    Session("session_1", "Build an Android clone", "/home/ubuntu/kimi-android-client", Instant.now(), isRunning = true),
                    Session("session_2", null, "/tmp/spike", Instant.now(), isRunning = false),
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateToSettings = {},
            onSessionClick = {},
            onRetry = {},
            onPullToRefresh = {},
        )
    }
}
